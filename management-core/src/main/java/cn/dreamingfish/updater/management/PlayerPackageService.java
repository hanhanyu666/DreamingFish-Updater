package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Inspects a bounded ZIP before the owner explicitly publishes its signed program. */
public final class PlayerPackageService {
    private static final long MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024;
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;

    public PlayerPackageService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths; this.database = database; this.json = json;
    }

    public PackageView stage(String projectId, String fileName, InputStream input, long length) {
        database.requireProject(projectId);
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new ManagementException("请选择玩家端发行 ZIP");
        String id = UUID.randomUUID().toString();
        Path directory = directory(projectId, id);
        try {
            Files.createDirectories(directory);
            Path zip = directory.resolve("package.zip");
            SourceTransferService.copyLimited(input, zip, length, SourceFileService.MAX_UPLOAD_BYTES);
            Path extracted = directory.resolve("bundle");
            extract(zip, extracted);
            var resolved = PlayerProgramService.resolveSource(extracted, "", "");
            String platform = resolved.launcher().toLowerCase(Locale.ROOT).endsWith(".exe") ? "windows-x64" : "linux-x64";
            String relative = extracted.relativize(resolved.root()).toString().replace('\\', '/');
            boolean newer = new PlayerProgramService(paths, database, json).latest(projectId, platform)
                    .map(current -> SemanticVersion.parse(resolved.version()).compareTo(SemanticVersion.parse(current.version())) > 0).orElse(true);
            PackageView view = new PackageView(id, resolved.version(), platform, resolved.launcher(),
                    Files.size(zip), fileName, relative, newer, newer ? "" : "此版本不高于当前选中的玩家端程序，不能重复发布。");
            AtomicFiles.write(directory.resolve("package.json"), json.write(view));
            return view;
        } catch (IOException | RuntimeException error) {
            discard(projectId, id);
            throw error instanceof ManagementException known ? known
                    : new ManagementException("无法读取玩家端 ZIP：" + error.getMessage(), error);
        }
    }

    public StoredPlayerProgram publish(String projectId, String id, String minimumBootstrapVersion) {
        Path directory = directory(projectId, id);
        try {
            PackageView view = json.read(Files.readAllBytes(directory.resolve("package.json")), PackageView.class);
            Path root = directory.resolve("bundle");
            if (!view.sourceRelative().isEmpty()) root = PathSafety.resolveInside(root, view.sourceRelative());
            return new PlayerProgramService(paths, database, json).publish(projectId, view.platform(), view.version(),
                    root, view.launcher(), minimumBootstrapVersion == null ? "0.1.2" : minimumBootstrapVersion);
        } catch (IOException error) { throw new ManagementException("玩家端暂存包不存在，请重新上传", error); }
    }

    public void discard(String projectId, String id) {
        Path directory = directory(projectId, id);
        try {
            if (Files.exists(directory)) try (var stream = Files.walk(directory)) {
                for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        } catch (IOException ignored) { /* Leave failed temporary cleanup for operator recovery. */ }
    }

    static void extract(Path zip, Path root) throws IOException {
        PathSafety.createSafeDirectories(root);
        Set<String> names = new HashSet<>();
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                if (++entries > 20_000) throw new ManagementException("ZIP 文件数量过多");
                String raw = entry.getName();
                String normalized = PathSafety.normalizeManifestPath(entry.isDirectory()
                        ? raw.replaceAll("/+$", "") : raw);
                if (!names.add(ManagedPaths.fold(normalized)))
                    throw new ManagementException("ZIP 有重复路径：" + normalized);
                Path target = PathSafety.resolveInside(root, normalized);
                if (entry.isDirectory()) { PathSafety.createSafeDirectories(target); continue; }
                PathSafety.createSafeDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[128 * 1024];
                    for (int lengthRead; (lengthRead = input.read(buffer)) >= 0;) {
                        expanded += lengthRead;
                        if (expanded > MAX_EXPANDED_BYTES) throw new ManagementException("ZIP 解压后超过 4 GiB");
                        output.write(buffer, 0, lengthRead);
                    }
                }
            }
        }
        if (entries == 0) throw new ManagementException("ZIP 中没有玩家端文件");
    }

    private Path directory(String projectId, String id) {
        database.requireProject(projectId);
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new ManagementException("玩家端暂存包不存在");
        try { return PathSafety.resolveInside(paths.root().resolve("player-imports"), projectId + "/" + id); }
        catch (IOException error) { throw new ManagementException("玩家端暂存路径无效", error); }
    }
    public record PackageView(String id, String version, String platform, String launcher,
                              long size, String fileName, String sourceRelative, boolean publishable, String problem) {}
}
