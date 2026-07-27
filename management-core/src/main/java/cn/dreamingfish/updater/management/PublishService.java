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
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PublishService {
    private static final DateTimeFormatter RELEASE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final ScanService scanner;
    private final ObjectStore objects;
    private final ProjectKeyStore keys;
    private final JsonCodec json;

    public PublishService(ManagementPaths paths, ManagementDatabase database,
                          ScanService scanner, JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.scanner = scanner;
        this.objects = new ObjectStore(paths);
        this.keys = new ProjectKeyStore(paths);
        this.json = json;
    }

    public StoredRelease publish(String projectId, String displayVersion,
                                 String minimumPlayerVersion, String changelog) {
        try (ProjectLock ignored = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
            ProjectRecord project = database.requireProject(projectId);
            PublishPreview preview = scanner.load(projectId);
            ensurePreviewBaseIsCurrent(preview);

            for (ScannedFile file : preview.files()) {
                Path source = PathSafety.resolveInside(project.sourceDirectory(), file.path());
                objects.importExpected(source, file.sha256(), file.size());
            }
            if (project.branding().coverObject() != null) {
                objects.require(project.branding().coverObject());
            }

            List<ScannedFile> finalScan = scanner.scan(project);
            if (!preview.files().equals(finalScan)) {
                throw new ManagementException("The standard modpack directory changed after the preview; scan again");
            }

            Instant now = Instant.now();
            long sequence = project.nextSequence();
            String releaseId = releaseId(sequence, now);
            ReleaseManifest manifest = new ReleaseManifest(
                    ProtocolConstants.RELEASE_SCHEMA_VERSION,
                    projectId,
                    releaseId,
                    sequence,
                    now,
                    displayVersion,
                    minimumPlayerVersion,
                    changelog == null ? "" : changelog,
                    requiredCapabilities(project.rules()),
                    project.rules().forcedSyncDirectories(),
                    project.branding(),
                    toManifestFiles(finalScan, project.rules().forcedSyncDirectories())
            );
            ManifestValidator.validateRelease(manifest,
                    Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC));
            StoredRelease release = persistSignedManifest(project, manifest);
            scanner.remove(projectId);
            return release;
        } catch (IOException e) {
            throw new ManagementException("Unable to publish project " + projectId, e);
        }
    }

    public StoredRelease rollback(String projectId, String targetReleaseId,
                                  String displayVersion, String changelog) {
        try (ProjectLock ignored = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
            ProjectRecord project = database.requireProject(projectId);
            StoredRelease target = database.findRelease(projectId, targetReleaseId)
                    .orElseThrow(() -> new ManagementException("Unknown release: " + targetReleaseId));
            ReleaseManifest old = database.readManifest(target);
            for (ManifestFile file : old.files()) {
                Path object = objects.require(file.sha256());
                objects.verify(object, file.sha256(), file.size());
            }
            if (old.branding().coverObject() != null) {
                objects.require(old.branding().coverObject());
            }

            Instant now = Instant.now();
            long sequence = project.nextSequence();
            ReleaseManifest rollback = new ReleaseManifest(
                    ProtocolConstants.RELEASE_SCHEMA_VERSION,
                    projectId,
                    releaseId(sequence, now),
                    sequence,
                    now,
                    displayVersion,
                    old.minimumPlayerVersion(),
                    changelog == null ? "Rollback to " + target.displayVersion() : changelog,
                    old.requiredCapabilities(),
                    old.forcedSyncDirectories(),
                    old.branding(),
                    old.files()
            );
            ManifestValidator.validateRelease(rollback,
                    Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC));
            return persistSignedManifest(project, rollback);
        } catch (IOException e) {
            throw new ManagementException("Unable to roll back project " + projectId, e);
        }
    }

    private StoredRelease persistSignedManifest(ProjectRecord project, ReleaseManifest manifest) {
        byte[] manifestBytes = json.write(manifest);
        PrivateKey privateKey = keys.load(project);
        String signature = Base64.getEncoder().encodeToString(CryptoSupport.sign(manifestBytes, privateKey));
        String manifestHash = CryptoSupport.sha256(manifestBytes);

        Path finalDirectory = paths.manifestDirectory(manifest.projectId(), manifest.releaseId());
        if (Files.exists(finalDirectory)) {
            throw new ManagementException("Release directory already exists: " + finalDirectory);
        }
        Path temporaryDirectory;
        try {
            temporaryDirectory = Files.createTempDirectory(paths.temporary(), "release-");
            AtomicFiles.write(temporaryDirectory.resolve("manifest.json"), manifestBytes);
            AtomicFiles.write(temporaryDirectory.resolve("manifest.sig"),
                    signature.getBytes(StandardCharsets.US_ASCII));
            Files.createDirectories(finalDirectory.getParent());
            AtomicFiles.moveReplace(temporaryDirectory, finalDirectory);
        } catch (IOException e) {
            throw new ManagementException("Unable to store signed release manifest", e);
        }

        Path manifestPath = finalDirectory.resolve("manifest.json");
        try {
            database.commitRelease(manifest, signature, manifestHash, manifestPath);
        } catch (RuntimeException e) {
            try {
                AtomicFiles.deleteRecursively(finalDirectory);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        return database.findRelease(manifest.projectId(), manifest.releaseId())
                .orElseThrow(() -> new ManagementException("Committed release cannot be read back"));
    }

    private void ensurePreviewBaseIsCurrent(PublishPreview preview) {
        String current = database.latestRelease(preview.projectId())
                .map(StoredRelease::releaseId)
                .orElse(null);
        if (!java.util.Objects.equals(current, preview.baseReleaseId())) {
            throw new ManagementException("A newer release was published after this preview; scan again");
        }
    }

    private static List<ManifestFile> toManifestFiles(List<ScannedFile> files,
                                                      List<String> forcedSyncDirectories) {
        List<ManifestFile> result = new ArrayList<>();
        for (ScannedFile file : files) {
            cn.dreamingfish.updater.protocol.FilePolicy policy = forcedSyncDirectories.stream()
                    .anyMatch(directory -> insideDirectory(file.path(), directory))
                    ? cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED
                    : file.policy();
            result.add(new ManifestFile(file.path(), file.sha256(), file.size(), policy, file.executable()));
        }
        result.sort(Comparator.comparing(ManifestFile::path));
        return List.copyOf(result);
    }

    private static boolean insideDirectory(String path, String directory) {
        return path.toLowerCase(java.util.Locale.ROOT)
                .startsWith(directory.toLowerCase(java.util.Locale.ROOT) + "/");
    }

    private static Set<String> requiredCapabilities(ProjectRules rules) {
        return rules.forcedSyncDirectories().isEmpty()
                ? Set.of()
                : Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC);
    }

    private static String releaseId(long sequence, Instant createdAt) {
        return "r%06d-%s-%s".formatted(
                sequence,
                RELEASE_TIME.format(createdAt),
                UUID.randomUUID().toString().substring(0, 8)
        );
    }
}
