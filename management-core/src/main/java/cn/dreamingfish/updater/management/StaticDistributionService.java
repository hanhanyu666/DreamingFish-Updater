package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.Hex;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PlayerMusicTrack;
import cn.dreamingfish.updater.protocol.PlayerPresentation;
import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import cn.dreamingfish.updater.protocol.ReleaseHistoryEntry;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds an ordinary static directory that mirrors the public update protocol. */
public final class StaticDistributionService {
    private static final int EXPORT_SCHEMA_VERSION = 1;
    private static final String MARKER_FILE = ".dreamingfish-static-export.json";

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final ObjectStore objects;
    private final PlayerProgramService playerPrograms;
    private final ProjectKeyStore keys;

    public StaticDistributionService(ManagementPaths paths,
                                     ManagementDatabase database,
                                     JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.json = json;
        this.objects = new ObjectStore(paths);
        this.playerPrograms = new PlayerProgramService(paths, database, json);
        this.keys = new ProjectKeyStore(paths);
    }

    public StaticDistributionExportResult exportProject(String projectId,
                                                         Path selectedOutputDirectory) {
        ProjectRecord project = database.requireProject(projectId);
        StoredRelease latest = database.latestRelease(projectId)
                .orElseThrow(() -> new ManagementException(
                        "请先为项目发布至少一个整合包版本，再导出外部托管目录"));
        Path output = prepareOutputDirectory(project, selectedOutputDirectory);
        Instant generatedAt = Instant.now();
        ExportMarker previous = readMarker(output);
        Instant createdAt = previous == null ? generatedAt : previous.createdAt();
        writeJson(output, MARKER_FILE, new ExportMarker(
                EXPORT_SCHEMA_VERSION, projectId, createdAt, generatedAt, "IN_PROGRESS"));

        try {
            Map<String, Long> objectSizes = new LinkedHashMap<>();
            Map<String, byte[]> releasePayloads = new LinkedHashMap<>();
            List<StoredRelease> releases = database.listReleases(projectId);
            PublicKey publicKey = CryptoSupport.decodePublicKey(project.publicKey());

            for (StoredRelease release : releases) {
                byte[] payload = verifiedReleasePayload(release, publicKey);
                ReleaseManifest manifest = json.read(payload, ReleaseManifest.class);
                if (!projectId.equals(manifest.projectId())
                        || !release.releaseId().equals(manifest.releaseId())) {
                    throw new ManagementException(
                            "发布清单与项目或版本不匹配：" + release.releaseId());
                }
                releasePayloads.put(release.releaseId(), payload);
                registerReleaseObjects(objectSizes, manifest);
                String route = "v1/projects/" + projectId + "/releases/"
                        + release.releaseId() + "/manifest";
                writeImmutable(output, route, payload, release.manifestSha256());
                writeSignature(output, route + ".sig", release.signature(), true);
            }

            byte[] latestPayload = releasePayloads.get(latest.releaseId());
            if (latestPayload == null) {
                throw new ManagementException("找不到最新发布清单：" + latest.releaseId());
            }
            String projectRoot = "v1/projects/" + projectId + "/";

            SignedPayload presentation = signedPresentation(project);
            registerBrandingObjects(objectSizes, project.branding());

            int programCount = 0;
            Map<String, SignedPayload> currentPrograms = new LinkedHashMap<>();
            for (String platform : playerPrograms.listPlatforms(projectId)) {
                List<StoredPlayerProgram> programs = playerPrograms.list(projectId, platform);
                for (StoredPlayerProgram stored : programs) {
                    StoredPlayerProgram verified = playerPrograms.read(
                            projectId, platform, stored.version());
                    byte[] payload = Files.readAllBytes(verified.manifestPath());
                    if (!CryptoSupport.sha256(payload).equals(verified.manifestSha256())) {
                        throw new ManagementException(
                                "玩家端程序清单已损坏：" + stored.version());
                    }
                    PlayerProgramManifest manifest = json.read(
                            payload, PlayerProgramManifest.class);
                    for (PlayerProgramFile file : manifest.files()) {
                        registerObject(objectSizes, file.sha256(), file.size());
                    }
                    String route = projectRoot + "player/" + platform
                            + "/versions/" + stored.version() + "/manifest";
                    writeImmutable(output, route, payload, verified.manifestSha256());
                    writeSignature(output, route + ".sig", verified.signature(), true);
                    programCount++;
                }
                StoredPlayerProgram current = playerPrograms.latest(projectId, platform)
                        .orElseThrow(() -> new ManagementException(
                                "玩家端平台没有最新版本指针：" + platform));
                byte[] currentPayload = Files.readAllBytes(current.manifestPath());
                currentPrograms.put(platform,
                        new SignedPayload(currentPayload, current.signature()));
            }

            CopyStats copyStats = materializeObjects(output, objectSizes);

            // Commit mutable pointers only after every object and immutable manifest is ready.
            // This keeps an export directory safe even while it is being served directly.
            writeMutable(output, projectRoot + "latest", latestPayload);
            writeSignature(output, projectRoot + "latest.sig", latest.signature(), false);
            writeJson(output, projectRoot + "history", releaseHistory(projectId, releases));
            writeMutable(output, projectRoot + "presentation", presentation.payload());
            writeSignature(output, projectRoot + "presentation.sig",
                    presentation.signature(), false);
            for (Map.Entry<String, SignedPayload> current : currentPrograms.entrySet()) {
                String route = projectRoot + "player/" + current.getKey() + "/latest";
                writeMutable(output, route, current.getValue().payload());
                writeSignature(output, route + ".sig",
                        current.getValue().signature(), false);
            }
            writeMutable(output, "healthz",
                    "{\n  \"status\" : \"ok\"\n}\n".getBytes(StandardCharsets.UTF_8));
            writeSupportFiles(output, projectId);
            writeJson(output, MARKER_FILE, new ExportMarker(
                    EXPORT_SCHEMA_VERSION, projectId, createdAt, generatedAt, "COMPLETE"));

            return new StaticDistributionExportResult(
                    projectId, output, generatedAt, releases.size(), programCount,
                    objectSizes.size(), copyStats.copiedCount(), copyStats.reusedCount(),
                    copyStats.totalBytes(), copyStats.copiedBytes());
        } catch (IOException e) {
            throw new ManagementException("导出外部托管目录失败", e);
        }
    }

    private Path prepareOutputDirectory(ProjectRecord project, Path selected) {
        if (selected == null) {
            throw new ManagementException("请选择静态分发目录");
        }
        Path output = selected.toAbsolutePath().normalize();
        if (output.getParent() == null) {
            throw new ManagementException("不能把磁盘根目录作为静态分发目录");
        }
        if (overlaps(output, paths.root())) {
            throw new ManagementException(
                    "静态分发目录不能包含管理端 data；否则可能泄露签名私钥和管理数据");
        }
        if (overlaps(output, project.sourceDirectory().toAbsolutePath().normalize())) {
            throw new ManagementException(
                    "静态分发目录不能与要管理的整合包目录互相包含");
        }
        try {
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(output)) {
                    throw new ManagementException("静态分发位置不是安全的普通目录");
                }
                Path marker = output.resolve(MARKER_FILE);
                boolean empty;
                try (var stream = Files.list(output)) {
                    empty = stream.findAny().isEmpty();
                }
                if (!empty && !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ManagementException(
                            "所选目录不是空目录，也不是本工具以前创建的静态分发目录；为防止覆盖文件，已停止导出");
                }
                ExportMarker existing = readMarker(output);
                if (existing != null && !project.id().equals(existing.projectId())) {
                    throw new ManagementException(
                            "此静态分发目录属于另一个项目：" + existing.projectId());
                }
            } else {
                Files.createDirectories(output);
            }
            return output;
        } catch (IOException e) {
            throw new ManagementException("无法准备静态分发目录：" + output, e);
        }
    }

    private ExportMarker readMarker(Path output) {
        Path marker = output.resolve(MARKER_FILE);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(marker)) {
            throw new ManagementException("静态分发目录标记文件不安全");
        }
        try {
            ExportMarker value = json.read(marker, ExportMarker.class);
            if (value.schemaVersion() != EXPORT_SCHEMA_VERSION
                    || value.projectId() == null || value.createdAt() == null) {
                throw new ManagementException("静态分发目录标记文件无效");
            }
            return value;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ManagementException management) throw management;
            throw new ManagementException("无法读取静态分发目录标记文件", e);
        }
    }

    private byte[] verifiedReleasePayload(StoredRelease release, PublicKey publicKey)
            throws IOException {
        byte[] payload = Files.readAllBytes(release.manifestPath());
        if (!CryptoSupport.sha256(payload).equals(release.manifestSha256())) {
            throw new ManagementException("发布清单已损坏：" + release.releaseId());
        }
        try {
            if (!CryptoSupport.verify(payload,
                    Base64.getDecoder().decode(release.signature()), publicKey)) {
                throw new ManagementException(
                        "发布清单签名无效：" + release.releaseId());
            }
        } catch (IllegalArgumentException e) {
            throw new ManagementException(
                    "发布清单签名格式无效：" + release.releaseId(), e);
        }
        return payload;
    }

    private SignedPayload signedPresentation(ProjectRecord project) {
        PlayerPresentation presentation = new PlayerPresentation(
                ProtocolConstants.PLAYER_PRESENTATION_SCHEMA_VERSION,
                project.id(), project.branding());
        ManifestValidator.validatePlayerPresentation(presentation);
        byte[] payload = json.writePretty(presentation);
        String signature = Base64.getEncoder().encodeToString(
                CryptoSupport.sign(payload, keys.load(project)));
        return new SignedPayload(payload, signature);
    }

    private ReleaseHistory releaseHistory(String projectId, List<StoredRelease> releases) {
        return new ReleaseHistory(
                ProtocolConstants.RELEASE_HISTORY_SCHEMA_VERSION,
                projectId,
                releases.stream().map(release -> new ReleaseHistoryEntry(
                        release.releaseId(), release.sequence(), release.displayVersion(),
                        release.createdAt(), release.changelog())).toList());
    }

    private void registerReleaseObjects(Map<String, Long> objects,
                                        ReleaseManifest manifest) {
        for (ManifestFile file : manifest.files()) {
            registerObject(objects, file.sha256(), file.size());
        }
        registerBrandingObjects(objects, manifest.branding());
    }

    private void registerBrandingObjects(Map<String, Long> target, Branding branding) {
        if (branding == null) return;
        if (branding.coverObject() != null && !branding.coverObject().isBlank()) {
            registerObject(target, branding.coverObject(), -1);
        }
        if (branding.musicTracks() != null) {
            for (PlayerMusicTrack track : branding.musicTracks()) {
                registerObject(target, track.sha256(), track.size());
            }
        }
    }

    private void registerObject(Map<String, Long> target, String hash, long size) {
        if (!Hex.isSha256(hash) || size < -1) {
            throw new ManagementException("清单包含无效的内容对象信息");
        }
        target.merge(hash, size, (existing, incoming) -> {
            if (existing >= 0 && incoming >= 0 && !existing.equals(incoming)) {
                throw new ManagementException("同一内容对象出现了不同的文件大小：" + hash);
            }
            return existing >= 0 ? existing : incoming;
        });
    }

    private CopyStats materializeObjects(Path output, Map<String, Long> objectSizes)
            throws IOException {
        int copied = 0;
        int reused = 0;
        long totalBytes = 0;
        long copiedBytes = 0;
        for (Map.Entry<String, Long> entry : objectSizes.entrySet()) {
            String hash = entry.getKey();
            Path source = objects.require(hash);
            long size = entry.getValue() >= 0 ? entry.getValue() : Files.size(source);
            objects.verify(source, hash, size);
            totalBytes += size;
            Path target = safeTarget(output, "v1/objects/sha256/" + hash);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(target)) {
                    throw new ManagementException("静态分发对象路径不是安全文件：" + target);
                }
                if (Files.size(target) == size
                        && (Files.isSameFile(source, target)
                        || CryptoSupport.sha256(target).equals(hash))) {
                    reused++;
                    continue;
                }
            }
            copyReplace(source, target);
            copied++;
            copiedBytes += size;
        }
        return new CopyStats(copied, reused, totalBytes, copiedBytes);
    }

    private void copyReplace(Path source, Path target) throws IOException {
        ensureSafeParent(target);
        AtomicFiles.copyReplace(source, target);
    }

    private void writeJson(Path output, String relative, Object value) {
        writeMutable(output, relative, json.writePretty(value));
    }

    private void writeSignature(Path output, String relative, String signature,
                                boolean immutable) {
        byte[] payload = (signature + "\n").getBytes(StandardCharsets.US_ASCII);
        if (immutable) {
            writeImmutable(output, relative, payload, CryptoSupport.sha256(payload));
        } else {
            writeMutable(output, relative, payload);
        }
    }

    private void writeMutable(Path output, String relative, byte[] payload) {
        Path target = safeTarget(output, relative);
        try {
            ensureSafeParent(target);
            if (Files.isSymbolicLink(target)
                    || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
                throw new ManagementException("静态分发文件路径不安全：" + target);
            }
            AtomicFiles.write(target, payload);
        } catch (IOException e) {
            throw new ManagementException("无法写入静态分发文件：" + target, e);
        }
    }

    private void writeImmutable(Path output, String relative, byte[] payload,
                                String expectedHash) {
        Path target = safeTarget(output, relative);
        try {
            ensureSafeParent(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(target)) {
                    throw new ManagementException("静态分发文件路径不安全：" + target);
                }
                if (CryptoSupport.sha256(target).equals(expectedHash)) return;
            }
            AtomicFiles.write(target, payload);
        } catch (IOException e) {
            throw new ManagementException("无法写入静态分发文件：" + target, e);
        }
    }

    private Path safeTarget(Path output, String relative) {
        Path target = output.resolve(relative.replace('/', java.io.File.separatorChar))
                .toAbsolutePath().normalize();
        if (!target.startsWith(output)) {
            throw new ManagementException("静态分发相对路径越界");
        }
        return target;
    }

    private void ensureSafeParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new ManagementException("静态分发文件没有父目录");
        Path current = parent.getRoot();
        for (Path component : parent) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(current)) {
                    throw new ManagementException("静态分发目录包含不安全路径：" + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private void writeSupportFiles(Path output, String projectId) {
        String headers = """
                /v1/objects/sha256/*
                  Cache-Control: public, max-age=31536000, immutable
                /v1/projects/%s/releases/*
                  Cache-Control: public, max-age=31536000, immutable
                /v1/projects/%s/player/*/versions/*
                  Cache-Control: public, max-age=31536000, immutable
                /v1/projects/%s/latest
                  Cache-Control: no-cache, max-age=0
                /v1/projects/%s/latest.sig
                  Cache-Control: no-cache, max-age=0
                /v1/projects/%s/presentation
                  Cache-Control: no-cache, max-age=0
                /v1/projects/%s/presentation.sig
                  Cache-Control: no-cache, max-age=0
                /v1/projects/%s/history
                  Cache-Control: no-cache, max-age=0
                /v1/projects/%s/player/*/latest
                  Cache-Control: no-cache, max-age=0
                /v1/projects/%s/player/*/latest.sig
                  Cache-Control: no-cache, max-age=0
                /healthz
                  Cache-Control: no-cache, max-age=0
                """.formatted(projectId, projectId, projectId, projectId,
                projectId, projectId, projectId, projectId, projectId);
        writeMutable(output, "_headers", headers.getBytes(StandardCharsets.UTF_8));
        String readme = """
                DreamingFish Updater 静态分发目录

                1. 请把这个目录中的全部文件原样上传到 HTTP、对象存储或 CDN 的公开根目录。
                2. 不要改名，也不要只上传 latest；对象、清单和 .sig 签名文件必须一起上传。
                3. 玩家端地址填写公开根地址，例如：https://update.example.com/
                4. 对象文件建议长期缓存；latest、presentation 及对应 .sig 不要长期缓存。
                5. _headers 是缓存规则示例；托管平台不支持时，请在平台控制台设置等效规则。
                6. 更新整合包、玩家端程序或个性化内容后，请重新导出并上传。

                此目录不包含管理端密码、对象存储密码或项目签名私钥。
                """;
        writeMutable(output, "外部托管说明.txt", readme.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    public record ExportMarker(
            int schemaVersion,
            String projectId,
            Instant createdAt,
            Instant updatedAt,
            String status
    ) {
    }

    private record SignedPayload(byte[] payload, String signature) {
    }

    private record CopyStats(
            int copiedCount,
            int reusedCount,
            long totalBytes,
            long copiedBytes
    ) {
    }
}
