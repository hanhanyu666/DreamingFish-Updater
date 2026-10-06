package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Correction;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.SemanticVersion;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.PlayerMusicTrack;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import cn.dreamingfish.updater.protocol.Withdrawal;
import cn.dreamingfish.updater.protocol.WithdrawalItem;

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

    // Noninteractive setup within this package; public adapters must bind explicit confirmation.
    StoredRelease publish(String projectId, String displayVersion,
                                 String minimumPlayerVersion, String changelog) {
        PublishPreview preview = scanner.load(projectId);
        return publish(projectId, displayVersion, minimumPlayerVersion, changelog,
                preview.previewId(), preview.confirmationDigest());
    }

    public StoredRelease publish(String projectId, String displayVersion,
                                  String minimumPlayerVersion, String changelog,
                                  String previewId, String previewDigest) {
        try (ProjectLock ignored = ProjectLock.acquire(PathSafety.resolveInside(paths.locks(), projectId + ".lock"))) {
            ProjectRecord project = database.requireProject(projectId);
            PublishPreview preview = scanner.requireConfirmation(projectId, previewId, previewDigest);
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
            scanner.requireConfirmation(projectId, previewId, previewDigest);

            Instant now = Instant.now();
            long sequence = project.nextSequence();
            String releaseId = releaseId(sequence, now);
            ProjectRules rules = project.rules();
            ReleaseManifest base = preview.baseReleaseId() == null ? null
                    : database.readManifest(database.findRelease(projectId, preview.baseReleaseId())
                    .orElseThrow(() -> new ManagementException("The preview base release no longer exists")));
            Set<String> published = folded(finalScan.stream().map(ScannedFile::path).toList());
            List<ManifestFile> targetFiles = toManifestFiles(finalScan);
            List<Withdrawal> withdrawals = enrichRemovals(projectId,
                    RemovalPolicies.compile(rules, base, targetFiles, preview.changes(), now));
            List<String> newlyReleased = releasedAliases(projectId, base, rules, preview);
            List<String> releasedPaths = carriedPaths(base == null ? List.of() : base.releasedPaths(),
                    newlyReleased, published, rules.cleanupDirectories(),
                    List.of());
            List<String> retainedPaths = carriedPaths(
                    base == null ? List.of() : base.retainedSelfManagedPaths(),
                    removalsWith(preview, RemovalAction.DELETE_KEEP_SELF_MANAGED), published,
                    rules.cleanupDirectories(), releasedPaths);
            List<Correction> corrections = rules.corrections().stream()
                    .filter(correction -> published.contains(fold(correction.path())))
                    .toList();
            ReleaseManifest manifest = new ReleaseManifest(
                    ProtocolConstants.RELEASE_SCHEMA_VERSION,
                    projectId,
                    releaseId,
                    sequence,
                    now,
                    displayVersion,
                    atLeastPolicyPlayer(minimumPlayerVersion),
                    changelog == null ? "" : changelog,
                    requiredCapabilities(releasedPaths),
                    List.of(),
                    List.of(),
                    releasedPaths,
                    project.branding(),
                    targetFiles,
                    rules.cleanupDirectories(),
                    ScanService.publishedGroups(finalScan, rules),
                    retainedPaths,
                    withdrawals,
                    corrections
            );
            validate(manifest);
            StoredRelease release = persistSignedManifest(project, manifest, rules.withWithdrawals(withdrawals));
            scanner.remove(projectId);
            return release;
        } catch (IOException e) {
            throw new ManagementException("Unable to publish project " + projectId, e);
        }
    }

    public StoredRelease rollback(String projectId, String targetReleaseId,
                                  String displayVersion, String changelog) {
        try (ProjectLock ignored = ProjectLock.acquire(PathSafety.resolveInside(paths.locks(), projectId + ".lock"))) {
            ProjectRecord project = database.requireProject(projectId);
            StoredRelease target = database.findRelease(projectId, targetReleaseId)
                    .orElseThrow(() -> new ManagementException("Unknown release: " + targetReleaseId));
            ReleaseManifest old = database.readManifest(target);
            if (old.files().stream().anyMatch(
                    file -> file.policy()
                            == cn.dreamingfish.updater.protocol.FilePolicy.LEGACY_MISSING_ONLY)) {
                throw new ManagementException(
                        "This historical release uses the removed DEFAULT file policy and cannot be republished; "
                                + "restore its files in the managed source directory and create a normal release instead");
            }
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
            MaintenanceModel model = MaintenanceModel.of(old);
            ReleaseManifest current = database.latestRelease(projectId)
                    .map(database::readManifest).orElse(null);
            Set<String> published = folded(old.files().stream().map(ManifestFile::path).toList());
            List<String> released = new ArrayList<>(old.releasedPaths());
            if (current != null) released.addAll(current.releasedPaths());
            List<String> releasedPaths = carriedPaths(released, List.of(), published,
                    model.cleanupDirectories(), List.of());
            List<String> retained = new ArrayList<>(old.retainedSelfManagedPaths());
            if (current != null) retained.addAll(current.retainedSelfManagedPaths());
            List<String> retainedPaths = carriedPaths(retained, List.of(), published,
                    model.cleanupDirectories(), releasedPaths);
            ProjectRules rules = project.rules();
            MaintenanceModel withdrawals = MaintenanceModel.of(new ReleaseManifest(
                    ProtocolConstants.RELEASE_SCHEMA_VERSION, projectId, "probe", 1, now, "probe",
                    "0.1.0", "", Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY),
                    List.of(), List.of(), List.of(), old.branding(), List.of(), List.of(), List.of(),
                    List.of(), rules.withdrawals(), List.of()));
            for (ManifestFile file : old.files()) {
                if (withdrawals.withdrawalFor(file.path(), file.sha256(), file.componentId(),
                        file.version()).isPresent()) {
                    throw new ManagementException("回滚目标包含已撤回的版本“" + file.path()
                            + "”；请先撤销这条撤回，或选择其他历史版本。");
                }
            }
            List<ManifestFile> files = old.files().stream()
                    .map(file -> new ManifestFile(file.path(), file.sha256(), file.size(),
                            cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, file.executable(),
                            file.componentId(), file.displayName(),
                            presetOf(model.behaviorOf(file)), file.optionalGroup(), file.version()))
                    .toList();
            List<PreviewChange> rollbackChanges = current == null ? List.of() : current.files().stream()
                    .filter(file -> !published.contains(fold(file.path())))
                    .map(file -> new PreviewChange(ChangeKind.REMOVED, file.path(), file.sha256(), null, 0)
                            .withRemovalAction(releasedPaths.stream().anyMatch(path -> path.equalsIgnoreCase(file.path()))
                                    ? RemovalAction.RELEASE : RemovalAction.DELETE))
                    .toList();
            List<Withdrawal> rollbackWithdrawals = enrichRemovals(projectId,
                    RemovalPolicies.compile(rules, current, files, rollbackChanges, now));
            List<Correction> corrections = rules.corrections().stream()
                    .filter(correction -> published.contains(fold(correction.path())))
                    .toList();
            ReleaseManifest rollback = new ReleaseManifest(
                    ProtocolConstants.RELEASE_SCHEMA_VERSION,
                    projectId,
                    releaseId(sequence, now),
                    sequence,
                    now,
                    displayVersion,
                    atLeastPolicyPlayer(old.minimumPlayerVersion()),
                    changelog == null ? "Rollback to " + target.displayVersion() : changelog,
                    requiredCapabilities(releasedPaths),
                    List.of(),
                    List.of(),
                    releasedPaths,
                    old.branding(),
                    files,
                    model.cleanupDirectories(),
                    old.optionalGroups(),
                    retainedPaths,
                    rollbackWithdrawals,
                    corrections
            );
            validate(rollback);
            return persistSignedManifest(project, rollback, rules.withWithdrawals(rollbackWithdrawals));
        } catch (IOException e) {
            throw new ManagementException("Unable to roll back project " + projectId, e);
        }
    }

    private StoredRelease persistSignedManifest(ProjectRecord project, ReleaseManifest manifest) {
        return persistSignedManifest(project, manifest, null);
    }

    private StoredRelease persistSignedManifest(ProjectRecord project, ReleaseManifest manifest, ProjectRules publishedRules) {
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
            database.commitRelease(manifest, signature, manifestHash, manifestPath, publishedRules);
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

    private static List<ManifestFile> toManifestFiles(List<ScannedFile> files) {
        List<ManifestFile> result = new ArrayList<>();
        for (ScannedFile file : files) {
            result.add(new ManifestFile(file.path(), file.sha256(), file.size(),
                    cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, file.executable(),
                    file.componentId(), file.displayName(), file.preset(), file.optionalGroup(),
                    file.version()));
        }
        result.sort(Comparator.comparing(ManifestFile::path));
        return List.copyOf(result);
    }

    /**
     * Ownership statements that every later complete target must carry: the
     * previous statements plus new decisions, minus paths that are published
     * again, paths inside cleanup directories and paths already listed elsewhere.
     */
    private static List<String> carriedPaths(List<String> previous, List<String> added,
                                             Set<String> published, List<String> cleanupDirectories,
                                             List<String> excluded) {
        java.util.Map<String, String> result = new java.util.TreeMap<>();
        previous.forEach(path -> result.put(fold(path), path));
        added.forEach(path -> result.put(fold(path), path));
        Set<String> skip = folded(excluded);
        result.keySet().removeIf(key -> published.contains(key) || skip.contains(key)
                || cleanupDirectories.stream().anyMatch(directory -> ManagedPaths.isBelow(key, directory)));
        return result.values().stream().sorted().toList();
    }

    private static List<String> removalsWith(PublishPreview preview, RemovalAction action) {
        return preview.changes().stream()
                .filter(change -> change.kind() == ChangeKind.REMOVED)
                .filter(change -> change.removalAction() == action)
                .map(PreviewChange::path)
                .toList();
    }

    private static void ensureRemovalDecisions(
            PublishPreview preview, ProjectRules rules) {
        for (PreviewChange change : preview.changes()) {
            if (change.kind() != ChangeKind.REMOVED) continue;
            if (change.removalAction() == null) {
                throw new ManagementException(
                        "Choose delete or release management for every removed file before publishing");
            }
            if (change.removalAction() == RemovalAction.DELETE_KEEP_SELF_MANAGED) {
                throw new ManagementException("请重新选择：移除玩家副本，或停止维护、留给玩家");
            }
            if (change.removalAction() != RemovalAction.DELETE
                    && rules.insideCleanupDirectory(change.path())) {
                throw new ManagementException(
                        "Files inside a forced sync directory cannot be released: "
                                + change.path());
            }
        }
    }

    private static Set<String> requiredCapabilities(List<String> releasedPaths) {
        Set<String> capabilities = new HashSet<>();
        capabilities.add(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY);
        capabilities.add(ProtocolConstants.CAPABILITY_SIMPLIFIED_MAINTENANCE);
        if (!releasedPaths.isEmpty()) {
            capabilities.add(ProtocolConstants.CAPABILITY_RELEASED_PATHS);
        }
        return Set.copyOf(capabilities);
    }

    private static Set<String> supportedCapabilities() {
        return ProtocolConstants.RELEASE_CAPABILITIES;
    }

    private static void validate(ReleaseManifest manifest) {
        try {
            ManifestValidator.validateRelease(manifest, supportedCapabilities());
        } catch (cn.dreamingfish.updater.protocol.ProtocolException invalid) {
            throw new ManagementException("发布内容与维护规则冲突：" + invalid.getMessage(), invalid);
        }
    }

    /** Releases using the maintenance policy cannot be interpreted by older player programs. */
    private static String atLeastPolicyPlayer(String requested) {
        SemanticVersion policy = SemanticVersion.parse(ScanService.POLICY_PLAYER_VERSION);
        if (requested == null || requested.isBlank()) return ScanService.POLICY_PLAYER_VERSION;
        return SemanticVersion.parse(requested).compareTo(policy) < 0
                ? ScanService.POLICY_PLAYER_VERSION : requested;
    }

    private List<String> releasedAliases(String projectId, ReleaseManifest base, ProjectRules rules,
                                         PublishPreview preview) {
        List<String> paths = new ArrayList<>(removalsWith(preview, RemovalAction.RELEASE));
        List<WithdrawalItem> items = new ArrayList<>();
        if (base != null) {
            for (ManifestFile file : base.files()) {
                if (paths.stream().anyMatch(path -> path.equalsIgnoreCase(file.path()))) {
                    items.add(new WithdrawalItem(file.sha256(), file.size(), file.path(), file.componentId(), file.version()));
                }
            }
            // Explicitly ending a persistent removal also releases old signed baselines.
            for (Withdrawal rule : base.withdrawals()) {
                if (rule.removal() && rules.withdrawals().stream().noneMatch(current -> current.id().equals(rule.id()))) {
                    items.addAll(rule.items());
                }
            }
        }
        for (WithdrawalItem item : items) {
            paths.add(item.path());
            for (StoredRelease release : database.listReleases(projectId)) {
                for (ManifestFile file : database.readManifest(release).files()) {
                    if (RemovalPolicies.sameResource(item, file)) paths.add(file.path());
                }
            }
        }
        for (String explicit : removalsWith(preview, RemovalAction.RELEASE)) {
            paths.removeIf(path -> path.equalsIgnoreCase(explicit));
            paths.add(explicit);
        }
        return paths.stream().distinct().toList();
    }

    private List<Withdrawal> enrichRemovals(String projectId, List<Withdrawal> instructions) {
        List<ManifestFile> history = database.listReleases(projectId).stream()
                .flatMap(release -> database.readManifest(release).files().stream()).toList();
        List<Withdrawal> result = new ArrayList<>();
        for (Withdrawal rule : instructions) {
            if (!rule.removal()) { result.add(rule); continue; }
            java.util.Map<String, WithdrawalItem> items = new java.util.LinkedHashMap<>();
            for (WithdrawalItem item : rule.items()) {
                items.put(fold(item.path()) + "|" + item.sha256(), item);
                for (ManifestFile old : history) {
                    if (RemovalPolicies.sameResource(item, old)) {
                        items.put(fold(old.path()) + "|" + old.sha256(), new WithdrawalItem(old.sha256(),
                                old.size(), old.path(), old.componentId(), old.version()));
                    }
                }
            }
            result.add(new Withdrawal(rule.id(), rule.reason(), rule.createdAt(), List.copyOf(items.values()), rule.kind()));
        }
        return List.copyOf(result);
    }

    private static MaintenancePreset presetOf(MaintenanceModel.Behavior behavior) {
        return switch (behavior) {
            case REQUIRED -> MaintenancePreset.REQUIRED;
            case SYNC -> MaintenancePreset.SYNC;
            case INITIAL -> MaintenancePreset.INITIAL;
            case DEFAULT_CONFIG -> MaintenancePreset.INITIAL;
            case LEGACY_MISSING_ONLY -> throw new ManagementException(
                    "This historical release uses the removed DEFAULT file policy and cannot be republished");
        };
    }

    private static Set<String> folded(List<String> paths) {
        Set<String> result = new HashSet<>();
        paths.forEach(path -> result.add(fold(path)));
        return result;
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
