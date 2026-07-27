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
import java.util.regex.Pattern;

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
        SemanticVersion targetVersion = SemanticVersion.parse(version);
        SemanticVersion.parse(minimumBootstrapVersion);
        String normalizedLauncher = PathSafety.normalizeManifestPath(launchPath);
        Path sourceRoot = sourceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceRoot)) {
            throw new ManagementException("Player program source directory does not exist or is a symbolic link");
        }
        ProjectRecord project = database.requireProject(projectId);
        Path lockPath = paths.locks().resolve(projectId + ".player-" + platform + ".lock");
        try (ProjectLock ignored = ProjectLock.acquire(lockPath)) {
            latest(projectId, platform).ifPresent(current -> {
                if (targetVersion.compareTo(SemanticVersion.parse(current.version())) <= 0) {
                    throw new ManagementException("Player program version must be newer than " + current.version());
                }
            });
            Path targetDirectory = paths.playerProgramDirectory(projectId, platform, version);
            if (Files.exists(targetDirectory)) {
                throw new ManagementException("Player program version already exists: " + version);
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
                    version,
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
        byte[] bytes = json.write(manifest);
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
