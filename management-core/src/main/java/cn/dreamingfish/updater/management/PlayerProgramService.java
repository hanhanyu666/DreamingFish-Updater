package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.SemanticVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

public final class PlayerProgramService {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final ObjectStore objects;
    private final JsonCodec json;
    private final ProjectKeyStore keys;

    public PlayerProgramService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.objects = new ObjectStore(paths);
        this.json = json;
        this.keys = new ProjectKeyStore(paths);
    }

    public StoredPlayerProgram publish(String projectId, String platform, String version,
                                       Path sourceDirectory, String launchPath,
                                       String minimumBootstrapVersion) {
        validateIdentifier(platform, "platform");
        ResolvedSource resolved = resolveSource(sourceDirectory, version, launchPath);
        String effectiveVersion = resolved.version();
        String effectiveLauncher = resolved.launcher();
        SemanticVersion targetVersion = SemanticVersion.parse(effectiveVersion);
        SemanticVersion.parse(minimumBootstrapVersion);
        String normalizedLauncher = PathSafety.normalizeManifestPath(effectiveLauncher);
        Path sourceRoot = resolved.root();
        ProjectRecord project = database.requireProject(projectId);
        Path lockPath = paths.locks().resolve(projectId + ".player-" + platform + ".lock");
        try (ProjectLock ignored = ProjectLock.acquire(lockPath)) {
            latest(projectId, platform).ifPresent(current -> {
                if (targetVersion.compareTo(SemanticVersion.parse(current.version())) <= 0) {
                    throw new ManagementException("Player program version must be newer than " + current.version());
                }
            });
            Path targetDirectory = paths.playerProgramDirectory(projectId, platform, effectiveVersion);
            if (Files.exists(targetDirectory)) {
                throw new ManagementException("Player program version already exists: " + effectiveVersion);
            }

            List<SourceFile> sources = scanAndImport(sourceRoot);
            if (sources.stream().noneMatch(file -> file.manifest().path().equals(normalizedLauncher))) {
                throw new ManagementException("Player program launcher is not present in the source directory");
            }
            verifySourcesUnchanged(sources);
            PlayerProgramManifest manifest = new PlayerProgramManifest(
                    ProtocolConstants.PLAYER_PROGRAM_SCHEMA_VERSION,
                    projectId,
                    platform,
                    effectiveVersion,
                    Instant.now(),
                    normalizedLauncher,
                    minimumBootstrapVersion,
                    Set.of(),
                    sources.stream().map(SourceFile::manifest).toList()
            );
            ManifestValidator.validatePlayerProgram(manifest, Set.of());
            return persist(project, manifest, targetDirectory);
        } catch (IOException e) {
            throw new ManagementException("Unable to publish player program", e);
        }
    }

    /** Publishes a player bundle by selecting its extracted outer directory. */
    public StoredPlayerProgram publishAuto(String projectId, String platform,
                                           Path selectedDirectory,
                                           String minimumBootstrapVersion) {
        return publish(projectId, platform, "", selectedDirectory, "",
                minimumBootstrapVersion);
    }

    /** Resolves common ZIP/app-image directory layouts without importing arbitrary siblings. */
    static ResolvedSource resolveSource(Path selectedDirectory, String requestedVersion,
                                        String requestedLauncher) {
        Path selected = selectedDirectory == null
                ? null : selectedDirectory.toAbsolutePath().normalize();
        if (selected == null || !Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(selected)) {
            throw new ManagementException("玩家端程序目录不存在或不是安全的普通目录");
        }
        String version = blankToNull(requestedVersion);
        if (version != null) {
            SemanticVersion.parse(version);
        } else {
            version = detectVersion(selected);
        }
        String launcher = blankToNull(requestedLauncher);
        if (launcher != null) {
            launcher = normalizeLauncher(launcher);
        }

        List<Path> candidates = new ArrayList<>();
        addCandidate(candidates, selected);
        if (version != null) {
            addCandidate(candidates, selected.resolve(version));
            addCandidate(candidates, selected.resolve("app").resolve(version));
            addCandidate(candidates, selected.resolve("DreamingFishUpdater")
                    .resolve("app").resolve(version));
        }
        addVersionDirectories(candidates, selected.resolve("app"));
        addVersionDirectories(candidates, selected.resolve("DreamingFishUpdater/app"));
        if (version == null) {
            version = detectVersionFromCandidates(candidates);
        }
        if (version == null) {
            throw new ManagementException(
                    "无法读取玩家端版本号；请选择包含 DreamingFishUpdater/state/active-player.properties 的解压根目录");
        }

        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate)) continue;
            String candidateLauncher = launcher;
            if (candidateLauncher == null) {
                candidateLauncher = findLauncher(candidate);
            }
            if (candidateLauncher == null) continue;
            Path launcherPath;
            try {
                launcherPath = PathSafety.resolveInside(candidate, candidateLauncher);
            } catch (IOException e) {
                continue;
            }
            if (Files.isRegularFile(launcherPath, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(launcherPath)) {
                return new ResolvedSource(candidate, version, candidateLauncher);
            }
        }
        throw new ManagementException(
                "未找到玩家端启动程序。请选择直接包含 DreamingFishUpdater.exe、app 和 runtime 的玩家端目录，或其解压后的外层目录");
    }

    private static String detectVersion(Path selected) {
        List<Path> stateFiles = List.of(
                selected.resolve("DreamingFishUpdater/state/active-player.properties"),
                selected.resolve("state/active-player.properties"),
                selected.resolve("../state/active-player.properties").normalize());
        for (Path state : stateFiles) {
            String value = readStateVersion(state);
            if (value != null) return value;
        }
        String name = selected.getFileName() == null
                ? null : selected.getFileName().toString();
        if (isSemanticVersion(name)) return name;
        return null;
    }

    private static String detectVersionFromCandidates(List<Path> candidates) {
        for (Path candidate : candidates) {
            Path name = candidate.getFileName();
            if (name != null && isSemanticVersion(name.toString())) {
                return name.toString();
            }
            Path metadata = Files.isRegularFile(candidate.resolve("app/.jpackage.xml"),
                    LinkOption.NOFOLLOW_LINKS)
                    ? candidate.resolve("app/.jpackage.xml")
                    : candidate.resolve(".jpackage.xml");
            if (Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature(
                            "http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setExpandEntityReferences(false);
                    factory.setXIncludeAware(false);
                    var document = factory.newDocumentBuilder().parse(metadata.toFile());
                    var elements = document.getElementsByTagName("app-version");
                    if (elements.getLength() > 0) {
                        String value = elements.item(0).getTextContent().trim();
                        if (isSemanticVersion(value)) return value;
                    }
                } catch (Exception ignored) {
                    // The state file or directory name remains authoritative.
                }
            }
        }
        return null;
    }

    private static void addVersionDirectories(List<Path> candidates, Path parent) {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) return;
        try (var stream = Files.list(parent)) {
            stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> isSemanticVersion(path.getFileName().toString()))
                    .sorted(Comparator.comparing(
                            (Path path) -> SemanticVersion.parse(
                                    path.getFileName().toString())).reversed())
                    .forEach(path -> addCandidate(candidates, path));
        } catch (IOException ignored) {
            // The final Chinese error explains which directory shape is required.
        }
    }

    private static void addCandidate(List<Path> candidates, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!candidates.contains(normalized)) candidates.add(normalized);
    }

    private static String findLauncher(Path root) {
        Path preferred = root.resolve("DreamingFishUpdater.exe");
        if (Files.isRegularFile(preferred, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(preferred)) return "DreamingFishUpdater.exe";
        try (var stream = Files.list(root)) {
            List<Path> executables = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".exe"))
                    .toList();
            return executables.size() == 1
                    ? executables.getFirst().getFileName().toString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String readStateVersion(Path state) {
        if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(state)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(state)) {
            properties.load(input);
            String version = properties.getProperty("version");
            return isSemanticVersion(version) ? version.trim() : null;
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeLauncher(String value) {
        try {
            return PathSafety.normalizeManifestPath(value);
        } catch (RuntimeException e) {
            throw new ManagementException("玩家端启动程序路径无效", e);
        }
    }

    private static boolean isSemanticVersion(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            SemanticVersion.parse(value.trim());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record ResolvedSource(Path root, String version, String launcher) {
    }

    public java.util.Optional<StoredPlayerProgram> latest(String projectId, String platform) {
        validateIdentifier(projectId, "project ID");
        validateIdentifier(platform, "platform");
        Path pointer = paths.playerProgramLatest(projectId, platform);
        if (!Files.exists(pointer)) return java.util.Optional.empty();
        try {
            String version = Files.readString(pointer, StandardCharsets.US_ASCII).trim();
            return java.util.Optional.of(read(projectId, platform, version));
        } catch (IOException e) {
            throw new ManagementException("Unable to read latest player program pointer", e);
        }
    }

    public StoredPlayerProgram read(String projectId, String platform, String version) {
        validateIdentifier(projectId, "project ID");
        validateIdentifier(platform, "platform");
        SemanticVersion.parse(version);
        ProjectRecord project = database.requireProject(projectId);
        Path manifestPath = paths.playerProgramDirectory(projectId, platform, version).resolve("manifest.json");
        Path signaturePath = manifestPath.resolveSibling("manifest.sig");
        try {
            byte[] bytes = Files.readAllBytes(manifestPath);
            String signature = Files.readString(signaturePath, StandardCharsets.US_ASCII).trim();
            PlayerProgramManifest manifest = json.read(bytes, PlayerProgramManifest.class);
            ManifestValidator.validatePlayerProgram(manifest, Set.of());
            if (!manifest.projectId().equals(projectId) || !manifest.platform().equals(platform)
                    || !manifest.version().equals(version)) {
                throw new ManagementException("Stored player program manifest identity does not match its path");
            }
            PublicKey publicKey = CryptoSupport.decodePublicKey(project.publicKey());
            if (!CryptoSupport.verify(bytes, Base64.getDecoder().decode(signature), publicKey)) {
                throw new ManagementException("Stored player program signature is invalid");
            }
            return new StoredPlayerProgram(projectId, platform, version, manifest.createdAt(),
                    CryptoSupport.sha256(bytes), signature, manifestPath);
        } catch (IOException | IllegalArgumentException | ProtocolException e) {
            throw new ManagementException("Unable to read player program " + version, e);
        }
    }

    public List<StoredPlayerProgram> list(String projectId, String platform) {
        validateIdentifier(projectId, "project ID");
        validateIdentifier(platform, "platform");
        Path versions = paths.playerPrograms().resolve(projectId).resolve(platform).resolve("versions");
        if (!Files.isDirectory(versions)) return List.of();
        try (var stream = Files.list(versions)) {
            return stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> read(projectId, platform, path.getFileName().toString()))
                    .sorted(Comparator.comparing(program -> SemanticVersion.parse(program.version())))
                    .toList();
        } catch (IOException e) {
            throw new ManagementException("Unable to list player program versions", e);
        }
    }

    public List<String> listPlatforms(String projectId) {
        validateIdentifier(projectId, "project ID");
        Path projectRoot = paths.playerPrograms().resolve(projectId);
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(projectRoot)) {
            return List.of();
        }
        try (var stream = Files.list(projectRoot)) {
            return stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString())
                    .filter(platform -> IDENTIFIER.matcher(platform).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ManagementException("Unable to list player program platforms", e);
        }
    }

    public void verifyAllPublishedPrograms() {
        for (ProjectRecord project : database.listProjects()) {
            Path projectRoot = paths.playerPrograms().resolve(project.id());
            if (!Files.isDirectory(projectRoot)) continue;
            try (var platforms = Files.list(projectRoot)) {
                for (Path platformPath : platforms.filter(Files::isDirectory).toList()) {
                    String platform = platformPath.getFileName().toString();
                    for (StoredPlayerProgram stored : list(project.id(), platform)) {
                        PlayerProgramManifest manifest = json.read(stored.manifestPath(), PlayerProgramManifest.class);
                        for (PlayerProgramFile file : manifest.files()) {
                            Path object = objects.require(file.sha256());
                            objects.verify(object, file.sha256(), file.size());
                        }
                    }
                    latest(project.id(), platform).orElseThrow(() ->
                            new ManagementException("Player program platform has no latest pointer: " + platform));
                }
            } catch (IOException e) {
                throw new ManagementException("Unable to verify player programs for " + project.id(), e);
            }
        }
    }

    private List<SourceFile> scanAndImport(Path sourceRoot) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(sourceRoot)) {
            List<Path> all = stream.sorted().toList();
            for (Path path : all) {
                if (Files.isSymbolicLink(path)) {
                    throw new ManagementException("Player program source contains a symbolic link: " + path);
                }
            }
            files = all.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        List<String> pathsToValidate = new ArrayList<>();
        List<SourceFile> result = new ArrayList<>();
        for (Path file : files) {
            String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
            relative = PathSafety.normalizeManifestPath(relative);
            pathsToValidate.add(relative);
            ObjectStore.ObjectInfo object = objects.importFile(file);
            result.add(new SourceFile(file, new PlayerProgramFile(relative,
                    object.sha256(), object.size(), Files.isExecutable(file))));
        }
        result.sort(Comparator.comparing(source -> source.manifest().path()));
        PathSafety.validateDistinctPaths(pathsToValidate);
        return result;
    }

    private void verifySourcesUnchanged(List<SourceFile> sources) {
        for (SourceFile source : sources) {
            try {
                if (Files.size(source.source()) != source.manifest().size()
                        || !CryptoSupport.sha256(source.source()).equals(source.manifest().sha256())) {
                    throw new ManagementException("Player program source changed while publishing: " + source.source());
                }
            } catch (IOException e) {
                throw new ManagementException("Unable to recheck player program source", e);
            }
        }
    }

    private StoredPlayerProgram persist(ProjectRecord project, PlayerProgramManifest manifest,
                                        Path targetDirectory) throws IOException {
        byte[] bytes = json.writePretty(manifest);
        String signature = Base64.getEncoder().encodeToString(
                CryptoSupport.sign(bytes, keys.load(project)));
        String hash = CryptoSupport.sha256(bytes);
        Path temporary = Files.createTempDirectory(paths.temporary(), "player-program-");
        boolean moved = false;
        try {
            AtomicFiles.write(temporary.resolve("manifest.json"), bytes);
            AtomicFiles.write(temporary.resolve("manifest.sig"),
                    (signature + "\n").getBytes(StandardCharsets.US_ASCII));
            Files.createDirectories(targetDirectory.getParent());
            AtomicFiles.moveReplace(temporary, targetDirectory);
            moved = true;
            try {
                AtomicFiles.write(paths.playerProgramLatest(manifest.projectId(), manifest.platform()),
                        (manifest.version() + "\n").getBytes(StandardCharsets.US_ASCII));
            } catch (IOException pointerFailure) {
                AtomicFiles.deleteRecursively(targetDirectory);
                throw pointerFailure;
            }
            return new StoredPlayerProgram(manifest.projectId(), manifest.platform(), manifest.version(),
                    manifest.createdAt(), hash, signature, targetDirectory.resolve("manifest.json"));
        } finally {
            if (!moved) AtomicFiles.deleteRecursively(temporary);
        }
    }

    private static void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new ManagementException("Invalid " + label);
        }
    }

    private record SourceFile(Path source, PlayerProgramFile manifest) {
    }
}
