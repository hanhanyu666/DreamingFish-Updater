package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

/** Prepares the signed historical release baseline carried by a distributable instance. */
public final class BundledReleasePreparer {
    public static final String BASELINE_DIRECTORY = ".dreamingfish-bootstrap/bundled-release";

    private static final List<String> RUNTIME_STATE_FILES = List.of(
            "verified-installation.json",
            "trust-state.json",
            "release-manifest.json",
            "release-manifest.sig",
            "local-mod-preferences.json",
            "local-file-preferences.json",
            "release-history.json",
            "instance.lock",
            "background-music-muted"
    );

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final ObjectStore objects;
    private final JsonCodec json;

    public BundledReleasePreparer(ManagementPaths paths, ManagementDatabase database,
                                  JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.objects = new ObjectStore(paths);
        this.json = json;
    }

    public PreparedBundledRelease prepare(String projectId, String releaseId,
                                           Path instanceRoot, Path playerHome) {
        Path instance = instanceRoot.toAbsolutePath().normalize();
        Path home = playerHome.toAbsolutePath().normalize();
        requireSafeDirectory(instance, "Minecraft instance directory");
        requireSafeDirectory(home, "Player updater directory");

        ProjectRecord project = database.requireProject(projectId);
        StoredRelease stored = database.findRelease(projectId, releaseId)
                .orElseThrow(() -> new ManagementException("Unknown release: " + releaseId));
        SignedManifest signed = readAndVerify(project, stored);
        validateProtectedPaths(instance, home, signed.manifest());

        List<PreparedFile> files = preflightFiles(instance, signed.manifest());
        rejectFilesManagedByOtherReleases(instance, signed.manifest());
        rejectForcedDirectoryExtras(instance, signed.manifest());
        clearRuntimeState(instance, home);
        int materialized = materializeFiles(files);
        createForcedDirectories(instance, signed.manifest());
        writeBaseline(instance, signed);
        return new PreparedBundledRelease(stored.releaseId(), stored.displayVersion(),
                stored.sequence(), signed.sha256(), files.size(), materialized,
                instance.resolve(BASELINE_DIRECTORY.replace('/', java.io.File.separatorChar)));
    }

    private SignedManifest readAndVerify(ProjectRecord project, StoredRelease stored) {
        try {
            if (!Files.isRegularFile(stored.manifestPath(), LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(stored.manifestPath())) {
                throw new ManagementException("Stored release manifest is missing or unsafe");
            }
            byte[] bytes = Files.readAllBytes(stored.manifestPath());
            String hash = CryptoSupport.sha256(bytes);
            if (!hash.equals(stored.manifestSha256())) {
                throw new ManagementException("Stored release manifest is corrupt");
            }
            PublicKey publicKey = CryptoSupport.decodePublicKey(project.publicKey());
            byte[] signature = Base64.getDecoder().decode(stored.signature());
            if (!CryptoSupport.verify(bytes, signature, publicKey)) {
                throw new ManagementException("Stored release signature is invalid");
            }
            ReleaseManifest manifest = json.read(bytes, ReleaseManifest.class);
            ManifestValidator.validateRelease(manifest,
                    Set.of(
                            ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC,
                            ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
                            ProtocolConstants.CAPABILITY_RELEASED_PATHS));
            if (!manifest.projectId().equals(project.id())
                    || !manifest.releaseId().equals(stored.releaseId())
                    || manifest.sequence() != stored.sequence()) {
                throw new ManagementException("Stored release identity does not match its database record");
            }
            return new SignedManifest(manifest, bytes, stored.signature(), hash);
        } catch (ManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new ManagementException("Unable to verify bundled release " + stored.releaseId(), e);
        }
    }

    private List<PreparedFile> preflightFiles(Path instance, ReleaseManifest manifest) {
        return manifest.files().stream().map(file -> {
            try {
                Path object = objects.require(file.sha256());
                objects.verify(object, file.sha256(), file.size());
                Path target = PathSafety.resolveInside(instance, file.path());
                boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (exists && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(target)
                        || Files.size(target) != file.size()
                        || !CryptoSupport.sha256(target).equals(file.sha256()))) {
                    throw new ManagementException("Instance file does not match selected release "
                            + manifest.displayVersion() + ": " + file.path());
                }
                return new PreparedFile(file, object, target, exists);
            } catch (IOException e) {
                throw new ManagementException("Unable to verify instance file: " + file.path(), e);
            }
        }).toList();
    }

    private void rejectFilesManagedByOtherReleases(Path instance, ReleaseManifest selected) {
        Set<String> selectedPaths = selected.files().stream()
                .map(ManifestFile::path)
                .map(BundledReleasePreparer::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, String> otherManagedPaths = new HashMap<>();
        for (StoredRelease release : database.listReleases(selected.projectId())) {
            for (ManifestFile file : database.readManifest(release).files()) {
                String folded = fold(file.path());
                if (!selectedPaths.contains(folded)) {
                    otherManagedPaths.putIfAbsent(folded, file.path());
                }
            }
        }
        for (String managedPath : otherManagedPaths.values()) {
            try {
                Path target = PathSafety.resolveInside(instance, managedPath);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ManagementException(
                            "Instance contains a file managed by another release but not by selected release "
                                    + selected.displayVersion() + ": " + managedPath);
                }
            } catch (IOException e) {
                throw new ManagementException(
                        "Unable to verify files from other project releases: " + managedPath, e);
            }
        }
    }

    private void rejectForcedDirectoryExtras(Path instance, ReleaseManifest selected) {
        Set<String> expected = selected.files().stream()
                .map(ManifestFile::path)
                .map(BundledReleasePreparer::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String directory : selected.forcedSyncDirectories()) {
            try {
                Path root = PathSafety.resolveInside(instance, directory);
                if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
                requireSafeDirectory(root, "Forced sync instance directory");
                try (var stream = Files.walk(root)) {
                    for (Path path : stream.toList()) {
                        if (path.equals(root) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                        String relative = instance.relativize(path).toString().replace('\\', '/');
                        if (Files.isSymbolicLink(path)
                                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            throw new ManagementException(
                                    "Forced sync directory contains an unsafe entry: " + relative);
                        }
                        if (!expected.contains(fold(relative))) {
                            throw new ManagementException(
                                    "Forced sync directory contains a file outside selected release "
                                            + selected.displayVersion() + ": " + relative);
                        }
                    }
                }
            } catch (IOException e) {
                throw new ManagementException(
                        "Unable to verify forced sync instance directory: " + directory, e);
            }
        }
    }

    private int materializeFiles(List<PreparedFile> files) {
        int materialized = 0;
        for (PreparedFile prepared : files) {
            if (prepared.existed()) continue;
            try {
                AtomicFiles.copyReplace(prepared.object(), prepared.target());
                setExecutable(prepared.target(), prepared.file().executable());
                materialized++;
            } catch (IOException e) {
                throw new ManagementException("Unable to materialize release file: "
                        + prepared.file().path(), e);
            }
        }
        return materialized;
    }

    private void createForcedDirectories(Path instance, ReleaseManifest manifest) {
        for (String directory : manifest.forcedSyncDirectories()) {
            try {
                Path target = PathSafety.resolveInside(instance, directory);
                Files.createDirectories(target);
                requireSafeDirectory(target, "Forced sync instance directory");
            } catch (IOException e) {
                throw new ManagementException("Unable to prepare forced sync directory: " + directory, e);
            }
        }
    }

    private void writeBaseline(Path instance, SignedManifest signed) {
        try {
            Path directory = PathSafety.resolveInside(instance, BASELINE_DIRECTORY);
            Files.createDirectories(directory);
            requireSafeDirectory(directory, "Bundled release directory");
            AtomicFiles.write(directory.resolve("manifest.json"), signed.bytes());
            AtomicFiles.write(directory.resolve("manifest.sig"),
                    (signed.signature() + "\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new ManagementException("Unable to write bundled release baseline", e);
        }
    }

    private void clearRuntimeState(Path instance, Path playerHome) {
        try {
            for (String directory : List.of("cache", "staging", "logs", "backups", "local-mods")) {
                deleteInside(playerHome, playerHome.resolve(directory));
            }
            deleteInside(playerHome, playerHome.resolve("state/transactions"));
            for (String file : RUNTIME_STATE_FILES) {
                Path target = playerHome.resolve("state").resolve(file).normalize();
                if (!target.startsWith(playerHome)) {
                    throw new ManagementException("Runtime state path escapes the player updater directory");
                }
                Files.deleteIfExists(target);
            }
            Path gameLock = instance.resolve(".dreamingfish-bootstrap/game.lock").normalize();
            if (!gameLock.startsWith(instance)) {
                throw new ManagementException("Game lock path escapes the instance");
            }
            Files.deleteIfExists(gameLock);
        } catch (IOException e) {
            throw new ManagementException("Unable to clear player runtime state before packaging", e);
        }
    }

    private void deleteInside(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new ManagementException("Refusing to remove a path outside the player updater directory");
        }
        AtomicFiles.deleteRecursively(normalizedTarget);
    }

    private void validateProtectedPaths(Path instance, Path playerHome, ReleaseManifest manifest) {
        String relativeHome = playerHome.startsWith(instance) && !playerHome.equals(instance)
                ? fold(instance.relativize(playerHome).toString())
                : null;
        for (ManifestFile file : manifest.files()) {
            requireUnprotected(file.path(), relativeHome);
        }
        for (String directory : manifest.forcedSyncDirectories()) {
            requireUnprotected(directory, relativeHome);
        }
    }

    private void requireUnprotected(String path, String relativeHome) {
        String folded = fold(path);
        if (inside(folded, ".dreamingfish-bootstrap")
                || (relativeHome != null && inside(folded, relativeHome))) {
            throw new ManagementException("Release attempts to manage a protected instance path: " + path);
        }
    }

    private static boolean inside(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static String fold(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static void requireSafeDirectory(Path path, String label) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new ManagementException(label + " is missing or unsafe: " + path);
        }
    }

    private static void setExecutable(Path path, boolean executable) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return;
        Set<PosixFilePermission> permissions = new HashSet<>(view.readAttributes().permissions());
        Set<PosixFilePermission> execute = Set.of(PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
        if (executable) permissions.addAll(execute);
        else permissions.removeAll(execute);
        view.setPermissions(permissions);
    }

    private record SignedManifest(ReleaseManifest manifest, byte[] bytes,
                                  String signature, String sha256) {
        private SignedManifest {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record PreparedFile(ManifestFile file, Path object, Path target, boolean existed) {
    }

    public record PreparedBundledRelease(
            String releaseId,
            String displayVersion,
            long sequence,
            String manifestSha256,
            int managedFiles,
            int materializedFiles,
            Path baselineDirectory
    ) {
    }
}
