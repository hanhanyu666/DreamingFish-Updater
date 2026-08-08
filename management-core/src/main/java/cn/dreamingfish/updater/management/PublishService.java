package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.PlayerMusicTrack;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
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
            ensureRemovalDecisions(preview, project.rules());

            for (ScannedFile file : preview.files()) {
                Path source = PathSafety.resolveInside(project.sourceDirectory(), file.path());
                objects.importExpected(source, file.sha256(), file.size());
            }
            if (project.branding().coverObject() != null) {
                objects.require(project.branding().coverObject());
            }
            verifyMusicObjects(project.branding().musicTracks());

            List<ScannedFile> finalScan = scanner.scan(project);
            if (!preview.files().equals(finalScan)) {
                throw new ManagementException("The standard modpack directory changed after the preview; scan again");
            }

            Instant now = Instant.now();
            long sequence = project.nextSequence();
            String releaseId = releaseId(sequence, now);
            List<String> releasedPaths = releasedPaths(
                    preview, finalScan, project.rules());
            ReleaseManifest manifest = new ReleaseManifest(
                    ProtocolConstants.RELEASE_SCHEMA_VERSION,
                    projectId,
                    releaseId,
                    sequence,
                    now,
                    displayVersion,
                    minimumPlayerVersion,
                    changelog == null ? "" : changelog,
                    requiredCapabilities(project.rules(), releasedPaths),
                    project.rules().forcedSyncDirectories(),
                    project.rules().forcedSyncFiles(),
                    releasedPaths,
                    project.branding(),
                    toManifestFiles(finalScan, project.rules())
            );
            ManifestValidator.validateRelease(manifest,
                    supportedCapabilities());
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
            verifyMusicObjects(old.branding().musicTracks());

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
                    old.forcedSyncFiles(),
                    old.releasedPaths(),
                    old.branding(),
                    old.files()
            );
            ManifestValidator.validateRelease(rollback,
                    supportedCapabilities());
            return persistSignedManifest(project, rollback);
        } catch (IOException e) {
            throw new ManagementException("Unable to roll back project " + projectId, e);
        }
    }

    private StoredRelease persistSignedManifest(ProjectRecord project, ReleaseManifest manifest) {
        byte[] manifestBytes = json.writePretty(manifest);
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

    private static List<ManifestFile> toManifestFiles(
            List<ScannedFile> files, ProjectRules rules) {
        Set<String> forcedFiles = rules.forcedSyncFiles().stream()
                .map(PublishService::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<ManifestFile> result = new ArrayList<>();
        for (ScannedFile file : files) {
            cn.dreamingfish.updater.protocol.FilePolicy policy =
                    rules.forcedSyncDirectories().stream()
                    .anyMatch(directory -> insideDirectory(file.path(), directory))
                    || forcedFiles.contains(fold(file.path()))
                    ? cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED
                    : file.policy();
            result.add(new ManifestFile(file.path(), file.sha256(), file.size(), policy,
                    file.executable(), file.componentId(), file.displayName()));
        }
        result.sort(Comparator.comparing(ManifestFile::path));
        return List.copyOf(result);
    }

    private static boolean insideDirectory(String path, String directory) {
        return path.toLowerCase(java.util.Locale.ROOT)
                .startsWith(directory.toLowerCase(java.util.Locale.ROOT) + "/");
    }

    private List<String> releasedPaths(
            PublishPreview preview, List<ScannedFile> finalScan, ProjectRules rules) {
        Set<String> result = new TreeSet<>();
        if (preview.baseReleaseId() != null) {
            StoredRelease base = database.findRelease(
                            preview.projectId(), preview.baseReleaseId())
                    .orElseThrow(() -> new ManagementException(
                            "The preview base release no longer exists"));
            result.addAll(database.readManifest(base).releasedPaths());
        }
        Set<String> managed = finalScan.stream()
                .map(ScannedFile::path)
                .map(PublishService::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        result.removeIf(path -> managed.contains(fold(path)));
        preview.changes().stream()
                .filter(change -> change.kind() == ChangeKind.REMOVED)
                .filter(change -> change.removalAction() == RemovalAction.RELEASE)
                .map(PreviewChange::path)
                .forEach(result::add);

        Set<String> forcedFiles = rules.forcedSyncFiles().stream()
                .map(PublishService::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        result.removeIf(path -> forcedFiles.contains(fold(path))
                || rules.forcedSyncDirectories().stream()
                .anyMatch(directory -> insideDirectory(path, directory)));
        return List.copyOf(result);
    }

    private static void ensureRemovalDecisions(
            PublishPreview preview, ProjectRules rules) {
        for (PreviewChange change : preview.changes()) {
            if (change.kind() != ChangeKind.REMOVED) continue;
            if (change.removalAction() == null) {
                throw new ManagementException(
                        "Choose delete or release management for every removed file before publishing");
            }
            if (change.removalAction() == RemovalAction.RELEASE
                    && rules.forcedSyncDirectories().stream()
                    .anyMatch(directory -> insideDirectory(change.path(), directory))) {
                throw new ManagementException(
                        "Files inside a forced sync directory cannot be released: "
                                + change.path());
            }
        }
    }

    private static Set<String> requiredCapabilities(
            ProjectRules rules, List<String> releasedPaths) {
        Set<String> capabilities = new HashSet<>();
        if (!rules.forcedSyncDirectories().isEmpty()) {
            capabilities.add(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC);
        }
        if (!rules.forcedSyncFiles().isEmpty()) {
            capabilities.add(ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC);
        }
        if (!releasedPaths.isEmpty()) {
            capabilities.add(ProtocolConstants.CAPABILITY_RELEASED_PATHS);
        }
        return Set.copyOf(capabilities);
    }

    private static Set<String> supportedCapabilities() {
        return Set.of(
                ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC,
                ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
                ProtocolConstants.CAPABILITY_RELEASED_PATHS);
    }

    private static String fold(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private void verifyMusicObjects(List<PlayerMusicTrack> tracks) {
        if (tracks == null) return;
        for (PlayerMusicTrack track : tracks) {
            Path object = objects.require(track.sha256());
            try {
                objects.verify(object, track.sha256(), track.size());
            } catch (IOException e) {
                throw new ManagementException("Unable to verify music object: " + track.title(), e);
            }
        }
    }

    private static String releaseId(long sequence, Instant createdAt) {
        return "r%06d-%s-%s".formatted(
                sequence,
                RELEASE_TIME.format(createdAt),
                UUID.randomUUID().toString().substring(0, 8)
        );
    }
}
