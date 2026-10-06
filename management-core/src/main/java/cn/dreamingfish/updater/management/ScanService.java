package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Correction;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.OptionalGroup;
import cn.dreamingfish.updater.protocol.SemanticVersion;
import cn.dreamingfish.updater.protocol.Withdrawal;
import cn.dreamingfish.updater.protocol.WithdrawalItem;
import cn.dreamingfish.updater.protocol.ModMetadata;
import cn.dreamingfish.updater.protocol.ModMetadataReader;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class ScanService {
    public static final int PREVIEW_SCHEMA_VERSION = 5;
    /** First player program version that understands {@code maintenance-policy-v2}. */
    public static final String POLICY_PLAYER_VERSION = "0.2.0";

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;

    public ScanService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.json = json;
    }

    public PublishPreview createPreview(String projectId) {
        try (ProjectLock ignored = projectLock(projectId)) {
            return createPreviewLocked(projectId);
        } catch (IOException error) { throw new ManagementException("Unable to lock publish preview", error); }
    }

    private PublishPreview createPreviewLocked(String projectId) {
        ProjectRecord project = database.requireProject(projectId);
        if (!project.rules().simplified()) {
            List<ReleaseManifest> history = database.listReleases(projectId).stream()
                    .sorted(java.util.Comparator.comparingLong(StoredRelease::sequence)).map(database::readManifest).toList();
            ReleaseManifest latest = history.isEmpty() ? null : history.getLast();
            ProjectRules migrated = RemovalPolicies.migrate(project.rules(), latest, history);
            database.updateProject(projectId, project.displayName(), project.sourceDirectory(),
                    project.publicBaseUrl(), project.branding(), migrated);
            project = database.requireProject(projectId);
        }
        List<ScannedFile> files = scan(project);
        StoredRelease latest = database.latestRelease(projectId).orElse(null);
        ReleaseManifest previousManifest = latest == null ? null : database.readManifest(latest);
        List<PreviewChange> changes = differences(previousManifest, files);
        PublishPreview existing = loadIfCompatible(projectId,
                latest == null ? null : latest.releaseId());
        if (existing != null) {
            Map<String, RemovalAction> priorActions = new HashMap<>();
            existing.changes().stream()
                    .filter(change -> change.kind() == ChangeKind.REMOVED)
                    .filter(change -> change.removalAction() != null)
                    .forEach(change -> priorActions.put(
                            fold(change.path()), change.removalAction()));
            changes = changes.stream()
                    .map(change -> change.kind() == ChangeKind.REMOVED
                            && priorActions.containsKey(fold(change.path()))
                            ? change.withRemovalAction(
                            priorActions.get(fold(change.path())))
                            : change)
                    .toList();
        }
        changes = changes.stream().map(change -> change.kind() == ChangeKind.REMOVED && change.removalAction() == null
                ? change.withRemovalAction(RemovalAction.DELETE) : change).toList();
        long total = files.stream().mapToLong(ScannedFile::size).sum();
        long download = changes.stream().mapToLong(PreviewChange::downloadSize).sum();
        ReleaseManifest previous = previousManifest;
        PublishPreview preview = new PublishPreview(
                PREVIEW_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                projectId,
                latest == null ? null : latest.releaseId(),
                Instant.now(),
                files,
                changes,
                total,
                download,
                project.rules(),
                projectDigest(project),
                policyChanges(previous, files, project.rules()),
                warnings(project, previous, files, changes)
        );
        save(preview);
        return preview;
    }

    public PublishPreview decideRemovals(
            String projectId, List<RemovalDecision> decisions) {
        PublishPreview preview = load(projectId);
        return decideRemovals(projectId, preview.previewId(), preview.confirmationDigest(), decisions);
    }

    public PublishPreview decideRemovals(String projectId, String previewId, String digest,
                                         List<RemovalDecision> decisions) {
        try (ProjectLock ignored = projectLock(projectId)) {
            return decideRemovalsLocked(projectId, previewId, digest, decisions);
        } catch (IOException error) { throw new ManagementException("Unable to lock removal decisions", error); }
    }

    private PublishPreview decideRemovalsLocked(String projectId, String previewId, String digest,
                                                List<RemovalDecision> decisions) {
        PublishPreview preview = requireConfirmation(projectId, previewId, digest);
        List<RemovalDecision> values = decisions == null ? List.of() : decisions;
        Map<String, RemovalAction> requested = new LinkedHashMap<>();
        for (RemovalDecision decision : values) {
            if (decision == null || decision.action() == null) {
                throw new ManagementException("Removal decision is incomplete");
            }
            final String normalized;
            try {
                normalized = PathSafety.normalizeManifestPath(decision.path());
            } catch (RuntimeException e) {
                throw new ManagementException(
                        "Invalid removal decision path: " + decision.path(), e);
            }
            if (requested.putIfAbsent(fold(normalized), decision.action()) != null) {
                throw new ManagementException(
                        "Duplicate removal decision: " + normalized);
            }
        }

        Set<String> removed = preview.changes().stream()
                .filter(change -> change.kind() == ChangeKind.REMOVED)
                .map(PreviewChange::path)
                .map(ScanService::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String requestedPath : requested.keySet()) {
            if (!removed.contains(requestedPath)) {
                throw new ManagementException(
                        "Removal decision does not belong to this preview: "
                                + requestedPath);
            }
        }
        List<PreviewChange> changes = preview.changes().stream()
                .map(change -> change.kind() == ChangeKind.REMOVED
                        && requested.containsKey(fold(change.path()))
                        ? change.withRemovalAction(requested.get(fold(change.path())))
                        : change)
                .toList();
        PublishPreview updated = preview.withChanges(changes);
        save(updated);
        return updated;
    }

    public PublishPreview load(String projectId) {
        Path path = previewPath(projectId);
        if (!Files.isRegularFile(path)) {
            throw new ManagementException("No publish preview exists for project " + projectId + "; run scan first");
        }
        try {
            PublishPreview preview = json.read(path, PublishPreview.class);
            if (preview.schemaVersion() != PREVIEW_SCHEMA_VERSION || !preview.projectId().equals(projectId)
                    || preview.rules() == null || preview.projectDigest() == null) {
                throw new ManagementException("Publish preview is incompatible or belongs to another project");
            }
            return preview;
        } catch (IOException e) {
            throw new ManagementException("Unable to read publish preview for " + projectId, e);
        }
    }

    public void remove(String projectId) {
        try {
            Files.deleteIfExists(previewPath(projectId));
        } catch (IOException e) {
            throw new ManagementException("Unable to remove the completed publish preview", e);
        }
    }

    /** Whether a published player program understands maintenance-policy releases. */
    public boolean policyPlayerPublished(String projectId) {
        String newest = newestPlayerProgram(projectId);
        return newest != null && SemanticVersion.parse(newest)
                .compareTo(SemanticVersion.parse(POLICY_PLAYER_VERSION)) >= 0;
    }

    /** Whether publishing this preview would be refused because the project or its releases changed. */
    public boolean isStale(PublishPreview preview) {
        ProjectRecord project = database.requireProject(preview.projectId());
        String latest = database.latestRelease(project.id())
                .map(StoredRelease::releaseId).orElse(null);
        return !preview.projectDigest().equals(projectDigest(project))
                || !Objects.equals(latest, preview.baseReleaseId());
    }

    public PublishPreview requireConfirmation(String projectId, String previewId, String digest) {
        PublishPreview preview = load(projectId);
        if (!java.util.Objects.equals(preview.previewId(), previewId)
                || !java.util.Objects.equals(preview.confirmationDigest(), digest)) {
            throw new ManagementException("Publish preview changed after confirmation; refresh and confirm again");
        }
        if (!preview.projectDigest().equals(projectDigest(database.requireProject(projectId)))) {
            throw new ManagementException("Project configuration changed after the preview; scan again");
        }
        return preview;
    }

    static String projectDigest(ProjectRecord project) {
        return CryptoSupport.sha256(new JsonCodec().write(java.util.Map.of(
                "name", project.displayName(), "source", project.sourceDirectory().toString(),
                "publicBaseUrl", project.publicBaseUrl(), "publicKey", project.publicKey(),
                "branding", project.branding(), "rules", project.rules())));
    }

    private ProjectLock projectLock(String projectId) throws IOException {
        if (projectId == null || !projectId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new ManagementException("Invalid project ID");
        }
        return ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"));
    }

    /** Maintenance differences between the latest release and the scanned target. */
    static List<PolicyChange> policyChanges(ReleaseManifest previous, List<ScannedFile> files,
                                            ProjectRules rules) {
        List<PolicyChange> changes = new ArrayList<>();
        MaintenanceModel old = previous == null ? null : MaintenanceModel.of(previous);
        for (ScannedFile file : files) {
            ManifestFile oldFile = old == null ? null : old.file(file.path()).orElse(null);
            if (oldFile == null) {
                if (old == null && file.preset() != MaintenancePreset.SYNC) {
                    changes.add(new PolicyChange("PRESET", file.path(), null, file.preset().name()));
                }
                continue;
            }
            String before = old.behaviorOf(oldFile).name();
            if (!before.equals(file.preset().name())) {
                changes.add(new PolicyChange("PRESET", file.path(), before, file.preset().name()));
            }
            if (!Objects.equals(oldFile.optionalGroup(), file.optionalGroup())) {
                changes.add(new PolicyChange("OPTIONAL_MEMBERSHIP", file.path(),
                        oldFile.optionalGroup(), file.optionalGroup()));
            }
        }

        Set<String> oldCleanup = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (old != null) oldCleanup.addAll(old.cleanupDirectories());
        Set<String> newCleanup = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        newCleanup.addAll(rules.cleanupDirectories());
        for (String directory : newCleanup) {
            if (!oldCleanup.contains(directory)) {
                changes.add(new PolicyChange("CLEANUP_DIRECTORY", directory, null, "ON"));
            }
        }
        for (String directory : oldCleanup) {
            if (!newCleanup.contains(directory)) {
                changes.add(new PolicyChange("CLEANUP_DIRECTORY", directory, "ON", null));
            }
        }

        Map<String, OptionalGroup> oldGroups = new LinkedHashMap<>();
        if (old != null) old.optionalGroups().forEach(group -> oldGroups.put(group.id(), group));
        Map<String, OptionalGroup> newGroups = new LinkedHashMap<>();
        publishedGroups(files, rules).forEach(group -> newGroups.put(group.id(), group));
        for (OptionalGroup group : newGroups.values()) {
            OptionalGroup before = oldGroups.get(group.id());
            if (!group.equals(before)) {
                changes.add(new PolicyChange("OPTIONAL_GROUP", group.id(),
                        before == null ? null : describe(before), describe(group)));
            }
        }
        for (OptionalGroup group : oldGroups.values()) {
            if (!newGroups.containsKey(group.id())) {
                changes.add(new PolicyChange("OPTIONAL_GROUP", group.id(), describe(group), null));
            }
        }

        Set<String> oldWithdrawals = new java.util.TreeSet<>();
        if (previous != null) previous.withdrawals().forEach(item -> oldWithdrawals.add(item.id()));
        Set<String> newWithdrawals = new java.util.TreeSet<>();
        rules.withdrawals().forEach(item -> newWithdrawals.add(item.id()));
        diffIds(changes, "WITHDRAWAL", oldWithdrawals, newWithdrawals);

        Set<String> scanned = new HashSet<>();
        files.forEach(file -> scanned.add(fold(file.path())));
        Set<String> oldCorrections = new java.util.TreeSet<>();
        if (previous != null) previous.corrections().forEach(item -> oldCorrections.add(item.id()));
        Set<String> newCorrections = new java.util.TreeSet<>();
        for (Correction correction : rules.corrections()) {
            if (scanned.contains(fold(correction.path()))) {
                newCorrections.add(correction.id());
            } else {
                changes.add(new PolicyChange("CORRECTION_DROPPED", correction.id(),
                        correction.path(), null));
            }
        }
        diffIds(changes, "CORRECTION", oldCorrections, newCorrections);
        return List.copyOf(changes);
    }

    private static void diffIds(List<PolicyChange> changes, String kind, Set<String> before,
                                Set<String> after) {
        for (String id : after) if (!before.contains(id)) changes.add(new PolicyChange(kind, id, null, "ON"));
        for (String id : before) if (!after.contains(id)) changes.add(new PolicyChange(kind, id, "ON", null));
    }

    private static String describe(OptionalGroup group) {
        return group.title() + (group.defaultInstall() ? "（默认安装）" : "（默认不安装）");
    }

    /** Optional groups that have at least one scanned file, in rule order. */
    static List<OptionalGroup> publishedGroups(List<ScannedFile> files, ProjectRules rules) {
        Set<String> used = new HashSet<>();
        files.forEach(file -> {
            if (file.optionalGroup() != null) used.add(file.optionalGroup());
        });
        return rules.optionalGroups().stream()
                .filter(group -> used.contains(group.id()))
                .map(group -> new OptionalGroup(group.id(), group.title(), group.description(),
                        group.defaultInstall()))
                .toList();
    }

    private List<PreviewWarning> warnings(ProjectRecord project, ReleaseManifest previous,
                                          List<ScannedFile> files, List<PreviewChange> changes) {
        List<PreviewWarning> warnings = new ArrayList<>();
        String newest = newestPlayerProgram(project.id());
        if (newest == null || SemanticVersion.parse(newest)
                .compareTo(SemanticVersion.parse(POLICY_PLAYER_VERSION)) < 0) {
            warnings.add(new PreviewWarning(PreviewWarning.PLAYER_PROGRAM_REQUIRED,
                    POLICY_PLAYER_VERSION,
                    "这次发布使用新的文件维护规则，玩家端需要 " + POLICY_PLAYER_VERSION
                            + " 或更新版本。当前项目" + (newest == null ? "还没有发布玩家端程序"
                            : "已发布的玩家端最高为 " + newest)
                            + "；旧玩家端会拒绝这个版本并无法启动游戏。请先发布新版玩家端，"
                            + "或确认玩家会通过新的整合包下载包获得新版玩家端。"));
        }
        MaintenanceModel old = previous == null ? null : MaintenanceModel.of(previous);
        for (PreviewChange change : changes) {
            if (change.kind() != ChangeKind.REMOVED || old == null) continue;
            ManifestFile removed = old.file(change.path()).orElse(null);
            if (removed != null && removed.componentId() != null
                    && ManagedPaths.isModJar(removed.path())) {
                warnings.add(new PreviewWarning(PreviewWarning.CONTENT_MOD_REMOVED, change.path(),
                        "移除模组后，玩家单机存档里属于这个模组的方块和物品会在下次进入存档时被游戏清除；"
                                + "选择“放弃管理并保留”或“改为可选”可以避免影响不需要移除的玩家。"));
            }
        }
        Set<String> previousWithdrawals = new HashSet<>();
        if (previous != null) previous.withdrawals().forEach(item -> previousWithdrawals.add(item.id()));
        for (Withdrawal withdrawal : project.rules().withdrawals()) {
            if (previousWithdrawals.contains(withdrawal.id())) continue;
            boolean mod = withdrawal.items().stream().anyMatch(WithdrawalItem::modScoped);
            if (mod) {
                warnings.add(new PreviewWarning(PreviewWarning.CONTENT_MOD_REMOVED, withdrawal.id(),
                        "撤回的模组会从所有玩家那里移走；玩家单机存档里属于它的方块和物品会在下次进入存档时被游戏清除。"));
            }
        }
        Set<String> scanned = new HashSet<>();
        files.forEach(file -> scanned.add(fold(file.path())));
        for (PresetRule rule : project.rules().presets()) {
            if (!rule.directory() && !scanned.contains(fold(rule.path()))) {
                warnings.add(new PreviewWarning(PreviewWarning.STALE_RULE, rule.path(),
                        "维护规则指向的文件已不在标准整合包目录中，这条规则不会生效。"));
            }
        }
        return List.copyOf(warnings);
    }

    private String newestPlayerProgram(String projectId) {
        PlayerProgramService programs = new PlayerProgramService(paths, database, json);
        SemanticVersion best = null;
        String bestText = null;
        try {
            for (String platform : programs.listPlatforms(projectId)) {
                for (StoredPlayerProgram program : programs.list(projectId, platform)) {
                    SemanticVersion version = SemanticVersion.parse(program.version());
                    if (best == null || version.compareTo(best) > 0) {
                        best = version;
                        bestText = program.version();
                    }
                }
            }
        } catch (ManagementException unreadable) {
            return bestText;
        }
        return bestText;
    }

    List<ScannedFile> scan(ProjectRecord project) {
        ProjectRules projectRules = project.rules();
        RuleSet rules = new RuleSet(projectRules);
        validateCleanupDirectories(project, rules);
        List<ScannedFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(project.sourceDirectory())) {
            stream.filter(path -> !path.equals(project.sourceDirectory())).forEach(path -> {
                String relative = project.sourceDirectory().relativize(path).toString().replace('\\', '/');
                relative = PathSafety.normalizeManifestPath(relative);
                RuleSet.Decision decision = rules.decide(relative);
                if (decision.excluded()) {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && projectRules.insideCleanupDirectory(relative)) {
                        throw new ManagementException("“" + relative + "”被排除发布，但它所在的目录开启了“清理多余文件”；"
                                + "玩家端会把它当作多余文件移走。请取消排除，或关闭这个目录的清理。");
                    }
                    return;
                }
                try { PathSafety.assertSafePathTree(path); }
                catch (IOException unsafe) { throw new java.io.UncheckedIOException(unsafe); }
                if (Files.isSymbolicLink(path)) {
                    throw new ManagementException("Managed source path cannot be a symbolic link: " + relative);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                files.add(hashStable(path, relative, projectRules));
            });
        } catch (IOException e) {
            throw new ManagementException("Unable to scan standard modpack directory " + project.sourceDirectory(), e);
        } catch (java.io.UncheckedIOException e) {
            throw new ManagementException("Unable to scan standard modpack directory " + project.sourceDirectory(), e);
        }
        files.sort(Comparator.comparing(ScannedFile::path));
        PathSafety.validateDistinctPaths(files.stream().map(ScannedFile::path).toList());
        return List.copyOf(files);
    }

    private void validateCleanupDirectories(ProjectRecord project, RuleSet rules) {
        for (String directory : project.rules().cleanupDirectories()) {
            if (rules.decide(directory + "/.dfs-cleanup-probe").excluded()) {
                throw new ManagementException("目录“" + directory + "”被排除发布，不能开启“清理多余文件”，"
                        + "否则玩家端会移走其中的全部文件。");
            }
            try {
                Path source = PathSafety.resolveInside(project.sourceDirectory(), directory);
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(source)) {
                    throw new ManagementException("Forced sync source directory is missing or unsafe: "
                            + directory);
                }
            } catch (IOException e) {
                throw new ManagementException("Unable to validate forced sync source directory: "
                        + directory, e);
            }
        }
    }

    private ScannedFile hashStable(Path file, String relative, ProjectRules rules) {
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String hash = CryptoSupport.sha256(file);
            ModMetadata metadata = ModMetadataReader.read(file).orElse(null);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                throw new ManagementException("Source file changed while scanning: " + relative);
            }
            String componentId = metadata == null ? null : metadata.componentId();
            List<OptionalGroupRule> groups = rules.groupsFor(relative, componentId);
            if (groups.size() > 1) {
                throw new ManagementException("“" + relative + "”同时属于多个可选分组："
                        + groups.stream().map(OptionalGroupRule::title).toList());
            }
            String group = groups.isEmpty() ? null : groups.getFirst().id();
            MaintenancePreset preset = rules.effectivePreset(relative, componentId);
            return new ScannedFile(
                    relative,
                    hash,
                    after.size(),
                    after.lastModifiedTime().toMillis(),
                    FilePolicy.ENFORCED,
                    file.getFileSystem().supportedFileAttributeViews().contains("posix") && Files.isExecutable(file),
                    componentId,
                    metadata == null ? null : metadata.displayName(),
                    preset,
                    group,
                    metadata == null ? null : metadata.version()
            );
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private List<PreviewChange> differences(ReleaseManifest previousManifest, List<ScannedFile> current) {
        List<ManifestFile> previous = previousManifest == null ? List.of() : previousManifest.files();
        MaintenanceModel oldModel = previousManifest == null ? null : MaintenanceModel.of(previousManifest);
        Map<String, ManifestFile> oldByPath = new HashMap<>();
        previous.forEach(file -> oldByPath.put(file.path(), file));
        Map<String, ScannedFile> newByPath = new HashMap<>();
        current.forEach(file -> newByPath.put(file.path(), file));
        List<PreviewChange> changes = new ArrayList<>();

        for (ScannedFile file : current) {
            ManifestFile old = oldByPath.get(file.path());
            if (old == null) {
                changes.add(new PreviewChange(ChangeKind.ADDED, file.path(), null, file.sha256(), file.size()));
            } else if (!old.sha256().equals(file.sha256()) || old.size() != file.size()) {
                changes.add(new PreviewChange(ChangeKind.MODIFIED, file.path(), old.sha256(), file.sha256(), file.size()));
            } else if (!oldModel.behaviorOf(old).name().equals(file.preset().name())
                    || !Objects.equals(old.optionalGroup(), file.optionalGroup())
                    || old.executable() != file.executable()) {
                changes.add(new PreviewChange(ChangeKind.POLICY_CHANGED, file.path(), old.sha256(), file.sha256(), 0));
            } else if (!java.util.Objects.equals(old.componentId(), file.componentId())
                    || !java.util.Objects.equals(old.displayName(), file.displayName())
                    || !java.util.Objects.equals(old.version(), file.version())) {
                changes.add(new PreviewChange(ChangeKind.METADATA_CHANGED,
                        file.path(), old.sha256(), file.sha256(), 0));
            }
        }
        for (ManifestFile old : previous) {
            if (!newByPath.containsKey(old.path())) {
                changes.add(new PreviewChange(ChangeKind.REMOVED, old.path(), old.sha256(), null, 0));
            }
        }
        changes.sort(Comparator.comparing(PreviewChange::path).thenComparing(change -> change.kind().name()));
        return List.copyOf(changes);
    }

    private void save(PublishPreview preview) {
        try {
            AtomicFiles.write(previewPath(preview.projectId()), json.writePretty(preview));
        } catch (IOException e) {
            throw new ManagementException("Unable to persist publish preview", e);
        }
    }

    private PublishPreview loadIfCompatible(String projectId, String baseReleaseId) {
        try {
            PublishPreview preview = load(projectId);
            return java.util.Objects.equals(
                    preview.baseReleaseId(), baseReleaseId) ? preview : null;
        } catch (ManagementException ignored) {
            return null;
        }
    }

    private Path previewPath(String projectId) {
        if (!projectId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new ManagementException("Invalid project ID");
        }
        return paths.previews().resolve(projectId + ".json");
    }

    private static String fold(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
