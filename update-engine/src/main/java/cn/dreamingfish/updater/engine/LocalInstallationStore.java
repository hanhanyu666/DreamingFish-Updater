package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class LocalInstallationStore {
    private final JsonCodec json = new JsonCodec();

    Optional<LocalInstallation> loadMetadata(EnginePaths paths, ProjectBinding binding,
                                             PublicKey publicKey, Set<String> capabilities) {
        List<Path> files = List.of(paths.installationState(), paths.trustState(),
                paths.installedManifest(), paths.installedSignature());
        long present = files.stream()
                .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                .count();
        if (present == 0) return Optional.empty();
        if (present != files.size()) {
            throw invalid("Local installation metadata is incomplete", null);
        }
        for (Path file : files) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                throw invalid("Local installation metadata contains an unsafe file", null);
            }
        }
        try {
            byte[] manifestBytes = Files.readAllBytes(paths.installedManifest());
            String signature = Files.readString(paths.installedSignature(), StandardCharsets.US_ASCII).trim();
            verifySignature(manifestBytes, signature, publicKey);
            ReleaseManifest manifest = json.read(manifestBytes, ReleaseManifest.class);
            ManifestValidator.validateRelease(manifest, capabilities);
            if (!manifest.projectId().equals(binding.projectId())) {
                throw invalid("Stored manifest belongs to another project", null);
            }

            VerifiedInstallation installation = json.read(paths.installationState(), VerifiedInstallation.class);
            TrustState trust = json.read(paths.trustState(), TrustState.class);
            String hash = CryptoSupport.sha256(manifestBytes);
            validateMetadata(manifest, hash, installation, trust, binding.projectId());
            return Optional.of(new LocalInstallation(
                    new SignedRelease(manifest, manifestBytes, signature, hash), installation, trust,
                    false, loadState(paths, binding.projectId())));
        } catch (UpdateException e) {
            throw e;
        } catch (IOException | ProtocolException e) {
            throw invalid("Unable to read trusted local installation metadata", e);
        }
    }

    LocalInstallation loadBundledBaseline(EnginePaths paths, ProjectBinding binding,
                                          PublicKey publicKey, Set<String> capabilities) {
        List<Path> files = List.of(paths.bundledManifest(), paths.bundledSignature());
        long present = files.stream()
                .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                .count();
        if (present == 0) {
            throw invalid("This instance is missing its signed bundled release baseline", null);
        }
        if (present != files.size()) {
            throw invalid("Bundled release baseline is incomplete", null);
        }
        for (Path file : files) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                throw invalid("Bundled release baseline contains an unsafe file", null);
            }
        }
        try {
            byte[] manifestBytes = Files.readAllBytes(paths.bundledManifest());
            String signature = Files.readString(
                    paths.bundledSignature(), StandardCharsets.US_ASCII).trim();
            verifySignature(manifestBytes, signature, publicKey);
            ReleaseManifest manifest = json.read(manifestBytes, ReleaseManifest.class);
            ManifestValidator.validateRelease(manifest, capabilities);
            if (!manifest.projectId().equals(binding.projectId())) {
                throw invalid("Bundled release baseline belongs to another project", null);
            }
            String hash = CryptoSupport.sha256(manifestBytes);
            SignedRelease release = new SignedRelease(
                    manifest, manifestBytes, signature, hash);
            VerifiedInstallation installation = installationFor(release);
            TrustState trust = trustFor(release);
            return new LocalInstallation(release, installation, trust, true);
        } catch (UpdateException e) {
            throw e;
        } catch (IOException | ProtocolException e) {
            throw invalid("Unable to read the signed bundled release baseline", e);
        }
    }

    boolean verifyFiles(EnginePaths paths, LocalInstallation local, ProgressListener listener,
                        LocalFileOverrides overrides, LocalFileIndex index,
                        CancellationToken cancellationToken) {
        cn.dreamingfish.updater.protocol.MaintenanceModel model =
                cn.dreamingfish.updater.protocol.MaintenanceModel.of(local.release().manifest());
        List<ManifestFile> checked = local.release().manifest().files().stream()
                .filter(file -> TransactionInstaller.verification(model, overrides, file)
                        != TransactionInstaller.Verification.NONE)
                .toList();
        long total = checked.stream()
                .filter(file -> TransactionInstaller.verification(model, overrides, file)
                        == TransactionInstaller.Verification.CONTENT)
                .mapToLong(ManifestFile::size).sum();
        long complete = 0;
        for (ManifestFile file : checked) {
            cancellationToken.throwIfCancelled();
            Path target;
            try {
                target = cn.dreamingfish.updater.protocol.PathSafety.resolveInside(paths.instanceRoot(), file.path());
            } catch (IOException | ProtocolException e) {
                return false;
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false;
            if (TransactionInstaller.verification(model, overrides, file)
                    == TransactionInstaller.Verification.CONTENT) {
                try {
                    if (!index.matches(target, file.path(), file.sha256(), file.size())) return false;
                } catch (IOException e) {
                    return false;
                }
                complete += file.size();
                listener.onProgress(new ProgressEvent(UpdateStage.VERIFYING,
                        "正在校验已安装文件", file.path(), complete, total));
            }
        }
        return true;
    }

    void save(EnginePaths paths, SignedRelease release, MaintenanceState state) throws IOException {
        VerifiedInstallation installation = installationFor(release);
        TrustState trust = trustFor(release);
        AtomicFileSupport.write(paths.installedManifest(), release.bytes());
        AtomicFileSupport.write(paths.installedSignature(),
                (release.signature() + "\n").getBytes(StandardCharsets.US_ASCII));
        AtomicFileSupport.write(paths.installationState(), json.writePretty(installation));
        AtomicFileSupport.write(paths.trustState(), json.writePretty(trust));
        MaintenanceState stored = state == null
                ? MaintenanceState.empty(release.manifest().projectId()) : state;
        AtomicFileSupport.write(paths.maintenanceState(), json.writePretty(stored));
    }

    /** Reads the per-instance maintenance memory; it is optional for installations from older versions. */
    MaintenanceState loadState(EnginePaths paths, String projectId) {
        Path file = paths.maintenanceState();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return MaintenanceState.empty(projectId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw invalid("Local maintenance state is not a safe regular file", null);
        }
        try {
            MaintenanceState state = json.read(file, MaintenanceState.class);
            if (state.schemaVersion() != MaintenanceState.SCHEMA_VERSION
                    || !projectId.equals(state.projectId())) {
                throw invalid("Local maintenance state belongs to another project or version", null);
            }
            return state;
        } catch (UpdateException e) {
            throw e;
        } catch (IOException | ProtocolException e) {
            throw invalid("Unable to read local maintenance state", e);
        }
    }

    private VerifiedInstallation installationFor(SignedRelease release) {
        List<InstalledFileState> files = release.manifest().files().stream()
                .map(file -> new InstalledFileState(file.path(), file.sha256(), file.size(), file.policy()))
                .toList();
        return new VerifiedInstallation(
                VerifiedInstallation.SCHEMA_VERSION,
                release.manifest().projectId(),
                release.manifest().releaseId(),
                release.manifest().sequence(),
                release.sha256(),
                Instant.now(),
                files
        );
    }

    private TrustState trustFor(SignedRelease release) {
        return new TrustState(
                TrustState.SCHEMA_VERSION,
                release.manifest().projectId(),
                release.manifest().sequence(),
                release.manifest().releaseId(),
                release.sha256(),
                Instant.now()
        );
    }

    private void validateMetadata(ReleaseManifest manifest, String hash,
                                  VerifiedInstallation installation, TrustState trust, String projectId) {
        if (installation.schemaVersion() != VerifiedInstallation.SCHEMA_VERSION
                || trust.schemaVersion() != TrustState.SCHEMA_VERSION) {
            throw invalid("Unsupported local installation metadata version", null);
        }
        if (!projectId.equals(installation.projectId()) || !projectId.equals(trust.projectId())) {
            throw invalid("Local installation metadata belongs to another project", null);
        }
        if (!installation.releaseId().equals(manifest.releaseId())
                || installation.sequence() != manifest.sequence()
                || !installation.manifestSha256().equals(hash)) {
            throw invalid("Local installation record does not match its signed manifest", null);
        }
        if (trust.highestSequence() != manifest.sequence()) {
            throw invalid("Local trust state does not match the installed release sequence", null);
        }
        if (!trust.releaseId().equals(manifest.releaseId())
                || !trust.manifestSha256().equals(hash)) {
            throw invalid("Local trust state conflicts with the installed release", null);
        }

        List<InstalledFileState> expected = manifest.files().stream()
                .map(file -> new InstalledFileState(file.path(), file.sha256(), file.size(), file.policy()))
                .toList();
        if (!installation.files().equals(expected)) {
            throw invalid("Local installation file list does not match its signed manifest", null);
        }
        Set<String> unique = new HashSet<>();
        for (InstalledFileState file : installation.files()) {
            if (!unique.add(file.path())) {
                throw invalid("Local installation contains duplicate paths", null);
            }
        }
    }

    static void verifySignature(byte[] bytes, String signature, PublicKey publicKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(signature);
            if (!CryptoSupport.verify(bytes, decoded, publicKey)) {
                throw new UpdateException(UpdateErrorCode.INVALID_SIGNATURE,
                        "Release manifest signature is invalid");
            }
        } catch (IllegalArgumentException | ProtocolException e) {
            throw new UpdateException(UpdateErrorCode.INVALID_SIGNATURE,
                    "Release manifest signature is malformed or cannot be verified", e);
        }
    }

    private static UpdateException invalid(String message, Throwable cause) {
        return cause == null
                ? new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, message)
                : new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, message, cause);
    }
}
