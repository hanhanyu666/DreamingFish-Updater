package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.SemanticVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerProgramUpdater {
    private static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    private final JsonCodec json = new JsonCodec();
    private final PlayerStorageMaintenance storageMaintenance = new PlayerStorageMaintenance();

    public PlayerProgramUpdateResult checkAndInstall(UpdateRequest request, String bootstrapVersion,
                                                     String platform, ProgressListener listener) {
        ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
        PublicKey publicKey;
        try {
            publicKey = ManifestValidator.validateBinding(request.binding());
        } catch (ProtocolException e) {
            throw new UpdateException(UpdateErrorCode.INVALID_BINDING, "Project binding is invalid", e);
        }
        String normalizedPlatform = platform == null ? currentPlatform() : platform;
        SignedPlayerProgram latest;
        try {
            latest = fetch(request, publicKey, normalizedPlatform);
        } catch (PlayerProgramNotPublished e) {
            return new PlayerProgramUpdateResult(PlayerProgramUpdateOutcome.NOT_PUBLISHED, null, 0);
        } catch (UpdateException e) {
            if (e.code() == UpdateErrorCode.NETWORK_UNAVAILABLE) {
                return new PlayerProgramUpdateResult(PlayerProgramUpdateOutcome.CHECK_UNAVAILABLE, null, 0);
            }
            throw e;
        }

        requireCompatibleVersions(request.playerVersion(), bootstrapVersion, latest.manifest());
        SemanticVersion current = SemanticVersion.parse(request.playerVersion());
        SemanticVersion target = SemanticVersion.parse(latest.manifest().version());

        EnginePaths paths = EnginePaths.of(request.instanceRoot(), request.playerHome());
        try {
            paths.createDirectories();
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to create player program directories", e);
        }
        try (InstanceUpdateLock ignored = InstanceUpdateLock.acquire(paths.instanceLock())) {
            storageMaintenance.cleanExpiredStaging(paths);
            ActivePlayerState accepted = ActivePlayerState.load(paths.playerHome()).orElse(null);
            if (accepted != null) {
                SemanticVersion acceptedVersion = SemanticVersion.parse(accepted.version());
                if (target.compareTo(acceptedVersion) < 0) {
                    throw new UpdateException(UpdateErrorCode.REPLAY_DETECTED,
                            "Update service returned a player program older than the accepted version");
                }
                if (target.compareTo(acceptedVersion) == 0
                        && !accepted.manifestSha256().isBlank()
                        && !accepted.manifestSha256().equals(latest.sha256())) {
                    throw new UpdateException(UpdateErrorCode.REPLAY_DETECTED,
                            "Update service changed a previously accepted player program version");
                }
            }
            ActivePlayerState previous = ActivePlayerState.loadForRunningVersion(
                    paths.playerHome(), request.playerVersion()).orElse(null);
            if (target.compareTo(current) == 0 && previous != null
                    && verifyActive(paths.playerHome(), previous, latest.manifest())) {
                if (!latest.sha256().equals(previous.manifestSha256())) {
                    ActivePlayerState verified = new ActivePlayerState(previous.version(), previous.launcher(),
                            previous.programRoot(), latest.sha256(), previous.arguments(),
                            previous.timeoutSeconds());
                    try {
                        storeSignedManifest(paths, latest);
                        ActivePlayerState.activate(paths.playerHome(), verified, null);
                    } catch (IOException e) {
                        throw new UpdateException(UpdateErrorCode.TRANSACTION_FAILED,
                                "Unable to record the verified player program", e);
                    }
                }
                storageMaintenance.cleanSupersededPrograms(paths);
                storageMaintenance.cleanObjectCache(paths);
                return new PlayerProgramUpdateResult(PlayerProgramUpdateOutcome.CURRENT,
                        latest.manifest(), 0);
            }

            progress.onProgress(new ProgressEvent(UpdateStage.DOWNLOADING,
                    "Downloading player updater", null, 0, 0));
            Map<String, Long> objects = objectMap(latest.manifest());
            long downloaded = new ObjectDownloader().download(request, paths, objects, progress);
            Path programRoot = installVersion(paths, latest);
            String relativeRoot = paths.playerHome().relativize(programRoot).toString().replace('\\', '/');
            String launcher = relativeRoot + "/" + latest.manifest().launchPath();
            ActivePlayerState next = new ActivePlayerState(latest.manifest().version(), launcher,
                    relativeRoot, latest.sha256(), List.of(),
                    previous == null ? 3600 : previous.timeoutSeconds());
            try {
                storeSignedManifest(paths, latest);
                ActivePlayerState.activate(paths.playerHome(), next, previous);
            } catch (IOException e) {
                throw new UpdateException(UpdateErrorCode.TRANSACTION_FAILED,
                        "Unable to activate the new player program", e);
            }
            storageMaintenance.cleanSupersededPrograms(paths);
            storageMaintenance.cleanObjectCache(paths);
            return new PlayerProgramUpdateResult(PlayerProgramUpdateOutcome.INSTALLED_RESTART_REQUIRED,
                    latest.manifest(), downloaded);
        }
    }

    public static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        if (!x64) {
            throw new UpdateException(UpdateErrorCode.UNSUPPORTED_PLAYER_VERSION,
                    "This player updater build supports x64 systems only");
        }
        if (os.contains("win")) return "windows-x64";
        if (os.contains("linux")) return "linux-x64";
        throw new UpdateException(UpdateErrorCode.UNSUPPORTED_PLAYER_VERSION,
                "Unsupported player updater platform: " + os);
    }

    public static void requireCompatibleVersions(String playerVersion, String bootstrapVersion,
                                                  PlayerProgramManifest manifest) {
        SemanticVersion current = SemanticVersion.parse(playerVersion);
        SemanticVersion target = SemanticVersion.parse(manifest.version());
        if (target.compareTo(current) < 0) {
            throw new UpdateException(UpdateErrorCode.REPLAY_DETECTED,
                    "Update service returned an older player program");
        }
        if (SemanticVersion.parse(bootstrapVersion)
                .compareTo(SemanticVersion.parse(manifest.minimumBootstrapVersion())) < 0) {
            throw new UpdateException(UpdateErrorCode.UNSUPPORTED_PLAYER_VERSION,
                    "Bootstrap Agent is older than required by the player program");
        }
    }

    private SignedPlayerProgram fetch(UpdateRequest request, PublicKey publicKey, String platform) {
        URI uri = ManifestFetcher.endpoint(request.binding(), "v1/projects/"
                + request.binding().projectId() + "/player/" + platform + "/latest");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri).GET()
                .timeout(request.requestTimeout())
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .build();
        try {
            HttpResponse<InputStream> response = ManifestFetcher.client(request)
                    .send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() == 404) throw new PlayerProgramNotPublished();
                if (response.statusCode() >= 500 || response.statusCode() == 408
                        || response.statusCode() == 429) {
                    throw new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE,
                            "Player program service is temporarily unavailable");
                }
                if (response.statusCode() != 200) {
                    throw new UpdateException(UpdateErrorCode.HTTP_ERROR,
                            "Player program request failed with HTTP " + response.statusCode());
                }
                byte[] bytes = readLimited(input);
                String signature = SignedPayloadSupport.resolveSignature(
                                ManifestFetcher.client(request), response, uri,
                                request.requestTimeout())
                        .orElseThrow(() -> new UpdateException(UpdateErrorCode.INVALID_SIGNATURE,
                                "Player program manifest has no signature"));
                LocalInstallationStore.verifySignature(bytes, signature, publicKey);
                PlayerProgramManifest manifest;
                try {
                    manifest = json.read(bytes, PlayerProgramManifest.class);
                    ManifestValidator.validatePlayerProgram(manifest, request.supportedCapabilities());
                } catch (ProtocolException e) {
                    throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                            "Player program manifest is invalid", e);
                }
                if (!manifest.projectId().equals(request.binding().projectId())
                        || !manifest.platform().equals(platform)) {
                    throw new UpdateException(UpdateErrorCode.WRONG_PROJECT,
                            "Player program manifest identity does not match this instance");
                }
                return new SignedPlayerProgram(manifest, bytes, signature, CryptoSupport.sha256(bytes));
            }
        } catch (PlayerProgramNotPublished | UpdateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.CANCELLED, "Player program check was interrupted", e);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE,
                    "Unable to check the player program version", e);
        }
    }

    private Path installVersion(EnginePaths paths, SignedPlayerProgram program) {
        String directoryName = program.manifest().version() + "-" + program.sha256().substring(0, 12);
        Path finalDirectory = paths.playerHome().resolve("app").resolve(directoryName);
        if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (verifyProgram(finalDirectory, program.manifest())) return finalDirectory;
            finalDirectory = paths.playerHome().resolve("app").resolve(
                    directoryName + "-repair-" + UUID.randomUUID().toString().substring(0, 8));
        }
        Path staging = paths.playerHome().resolve("staging/player-program/" + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            for (PlayerProgramFile file : program.manifest().files()) {
                Path source = paths.cacheObject(file.sha256());
                if (!isValid(source, file.sha256(), file.size())) {
                    throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                            "Player program cache object is invalid: " + file.sha256());
                }
                Path target = PathSafety.resolveInside(staging, file.path());
                AtomicFileSupport.copyReplace(source, target);
                setExecutable(target, file.executable());
            }
            if (!verifyProgram(staging, program.manifest())) {
                throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                        "Installed player program failed verification");
            }
            Files.createDirectories(finalDirectory.getParent());
            AtomicFileSupport.moveReplace(staging, finalDirectory);
            return finalDirectory;
        } catch (UpdateException e) {
            throw e;
        } catch (IOException | ProtocolException e) {
            throw new UpdateException(UpdateErrorCode.TRANSACTION_FAILED,
                    "Unable to install player program", e);
        } finally {
            try {
                deleteTree(staging);
            } catch (IOException ignored) {
            }
        }
    }

    private boolean verifyActive(Path playerHome, ActivePlayerState state, PlayerProgramManifest manifest) {
        try {
            Path root;
            if (!state.programRoot().isBlank()) {
                root = PathSafety.resolveInside(playerHome, state.programRoot());
            } else {
                Path launcher = PathSafety.resolveInside(playerHome, state.launcher());
                root = launcher;
                for (String ignored : manifest.launchPath().split("/")) root = root.getParent();
            }
            return verifyProgram(root, manifest);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean verifyProgram(Path root, PlayerProgramManifest manifest) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return false;
        for (PlayerProgramFile file : manifest.files()) {
            try {
                Path path = PathSafety.resolveInside(root, file.path());
                if (!isValid(path, file.sha256(), file.size())) return false;
            } catch (IOException | ProtocolException e) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Long> objectMap(PlayerProgramManifest manifest) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (PlayerProgramFile file : manifest.files()) {
            Long existing = result.putIfAbsent(file.sha256(), file.size());
            if (existing != null && existing.longValue() != file.size()) {
                throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                        "Player program object has conflicting sizes");
            }
        }
        return result;
    }

    private void storeSignedManifest(EnginePaths paths, SignedPlayerProgram program) throws IOException {
        Path directory = paths.state().resolve("player-programs").resolve(program.sha256());
        AtomicFileSupport.write(directory.resolve("manifest.json"), program.bytes());
        AtomicFileSupport.write(directory.resolve("manifest.sig"),
                (program.signature() + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    private boolean isValid(Path path, String hash, long size) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(path) == size && CryptoSupport.sha256(path).equals(hash);
        } catch (IOException e) {
            return false;
        }
    }

    private void setExecutable(Path path, boolean executable) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return;
        Set<PosixFilePermission> permissions = new HashSet<>(view.readAttributes().permissions());
        Set<PosixFilePermission> execute = Set.of(PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
        if (executable) permissions.addAll(execute);
        else permissions.removeAll(execute);
        view.setPermissions(permissions);
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > MAX_MANIFEST_BYTES) {
                throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                        "Player program manifest exceeds the size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class PlayerProgramNotPublished extends RuntimeException {
    }
}
