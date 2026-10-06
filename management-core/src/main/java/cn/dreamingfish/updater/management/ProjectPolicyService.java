package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Correction;
import cn.dreamingfish.updater.protocol.CorrectionMode;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.Hex;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ModMetadata;
import cn.dreamingfish.updater.protocol.ModMetadataReader;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import cn.dreamingfish.updater.protocol.Withdrawal;
import cn.dreamingfish.updater.protocol.WithdrawalItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Owner edits of a project's maintenance rules: presets, cleanup directories,
 * optional groups, withdrawals of historical versions and corrections. Every
 * edit is persisted immediately and takes effect with the next confirmed
 * release; it never changes an already signed release.
 */
public final class ProjectPolicyService {
    private static final DateTimeFormatter DIRECTIVE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final ObjectStore objects;

    public ProjectPolicyService(ManagementPaths paths, ManagementDatabase database) {
        this.paths = paths;
        this.database = database;
        this.objects = new ObjectStore(paths);
    }

    // ---- presets and cleanup -----------------------------------------------------

    /** Sets the preset of a file or of every file below a directory; {@code null} restores the default. */
    public ProjectRecord setPreset(String projectId, String path, boolean directory,
                                   MaintenancePreset preset) {
        return applyPresets(projectId, List.of(new PresetChange(path, directory, preset)));
    }

    public ProjectRecord setPresets(String projectId, List<PresetRule> presets) {
        return update(projectId, rules -> rules.withPresets(presets));
    }

    /** Applies several preset edits at once, such as a selection in the file list. */
    public ProjectRecord applyPresets(String projectId, List<PresetChange> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new ManagementException("请至少选择一个文件或文件夹");
        }
        ProjectRecord project = database.requireProject(projectId);
        Map<String, String> modIds = new LinkedHashMap<>();
        List<SourceFileService.SourceFileEntry> sourceFiles = changes.stream()
                .anyMatch(change -> change != null && change.directory() && change.preset() == MaintenancePreset.REQUIRED)
                ? new SourceFileService(paths, database, new JsonCodec()).list(projectId) : List.of();
        for (PresetChange change : changes) {
            if (change == null || change.directory() || change.preset() != MaintenancePreset.REQUIRED) continue;
            String path = normalize(change.path());
            if (!ManagedPaths.isModJar(path)) continue;
            try {
                Path jar = PathSafety.resolveInside(project.sourceDirectory(), path);
                ModMetadataReader.read(jar).ifPresent(mod -> modIds.put(path, mod.componentId()));
            } catch (IOException e) {
                throw new ManagementException("无法读取模组信息：" + path, e);
            }
        }
        return update(projectId, rules -> {
            ProjectRules updated = rules;
            for (PresetChange change : changes) {
                if (change == null) throw new ManagementException("维护方式设置不完整");
                String normalized = normalize(change.path());
                if (change.preset() == MaintenancePreset.DEFAULT_CONFIG) {
                    throw new ManagementException("默认配置已合并为首次提供，请选择普通同步、强制同步或首次提供");
                }
                if (change.preset() != null && change.preset() != MaintenancePreset.REQUIRED
                        && updated.presets().stream().anyMatch(rule -> rule.directory()
                        && rule.preset() == MaintenancePreset.REQUIRED
                        && ManagedPaths.isBelow(normalized, rule.path()))) {
                    throw new ManagementException("这个文件位于强制同步目录内；请先将上层目录改为普通同步");
                }
                if (change.preset() == MaintenancePreset.REQUIRED) {
                    String path = normalize(change.path());
                    String modId = modIds.get(path);
                    for (OptionalGroupRule group : updated.optionalGroups()) {
                        boolean member = change.directory()
                                ? group.directories().stream().anyMatch(dir -> dir.equalsIgnoreCase(path)
                                || ManagedPaths.isBelow(dir, path) || ManagedPaths.isBelow(path, dir))
                                || group.files().stream().anyMatch(file -> ManagedPaths.isBelow(file, path))
                                || sourceFiles.stream().anyMatch(file -> ManagedPaths.isBelow(file.path(), path)
                                && group.id().equals(file.optionalGroup()))
                                : group.files().stream().anyMatch(file -> file.equalsIgnoreCase(path))
                                || modId != null && group.modIds().stream().anyMatch(id -> id.equalsIgnoreCase(modId));
                        if (member) {
                            throw new ManagementException("“" + path + "”属于可选内容“" + group.title()
                                    + "”，必需同步的内容不能由玩家选择；请先把它移出可选内容");
                        }
                    }
                }
                if (change.directory()) {
                    if (change.preset() != null) updated = updated.withPresets(updated.presets().stream()
                            .filter(rule -> !ManagedPaths.isBelow(rule.path(), normalized)).toList());
                    List<String> cleanup = new ArrayList<>(updated.cleanupDirectories());
                    cleanup.removeIf(path -> path.equalsIgnoreCase(normalized)
                            || (change.preset() != null && ManagedPaths.isBelow(path, normalized)));
                    if (change.preset() == MaintenancePreset.REQUIRED) cleanup.add(normalized);
                    updated = updated.withCleanupDirectories(cleanup);
                }
                updated = updated.withPreset(change.path(), change.directory(), change.preset());
            }
            return updated;
        });
    }

    public ProjectRecord setCleanup(String projectId, String directory, boolean enabled) {
        return setPreset(projectId, directory, true,
                enabled ? MaintenancePreset.REQUIRED : MaintenancePreset.SYNC);
    }

    // ---- optional groups ---------------------------------------------------------

    public ProjectRecord saveOptionalGroup(String projectId, OptionalGroupRule group) {
        return update(projectId, rules -> {
            List<OptionalGroupRule> groups = new ArrayList<>();
            boolean replaced = false;
            for (OptionalGroupRule existing : rules.optionalGroups()) {
                if (existing.id().equals(group.id())) {
                    groups.add(group);
                    replaced = true;
                } else {
                    groups.add(existing);
                }
            }
            if (!replaced) groups.add(group);
            return rules.withOptionalGroups(groups);
        });
    }

    /** Creates an optional group, or renames and re-describes an existing one keeping its members. */
    public OptionalGroupRule defineOptionalGroup(String projectId, String groupId, String title,
                                                 String description, boolean defaultInstall) {
        String name = title == null ? "" : title.trim();
        if (name.isEmpty()) throw new ManagementException("请填写可选内容的名称");
        if (name.length() > 80) throw new ManagementException("可选内容名称不能超过 80 个字");
        OptionalGroupRule[] saved = new OptionalGroupRule[1];
        update(projectId, rules -> {
            List<OptionalGroupRule> groups = new ArrayList<>(rules.optionalGroups());
            if (groupId == null || groupId.isBlank()) {
                saved[0] = new OptionalGroupRule(groupIdFor(name, groups), name, description,
                        defaultInstall, List.of(), List.of(), List.of());
                groups.add(saved[0]);
            } else {
                int index = indexOfGroup(groups, groupId);
                OptionalGroupRule existing = groups.get(index);
                saved[0] = new OptionalGroupRule(existing.id(), name, description, defaultInstall,
                        existing.modIds(), existing.files(), existing.directories());
                groups.set(index, saved[0]);
            }
            return rules.withOptionalGroups(groups);
        });
        return saved[0];
    }

    /**
     * Adds files or directories to a group, or takes them out. Mods join by the mod
     * ID read from the jar, so renamed updates stay optional; other content joins
     * by path. Content belongs to one group at a time.
     */
    public ProjectRecord setGroupMembers(String projectId, String groupId, List<GroupMember> members,
                                         boolean add) {
        if (members == null || members.isEmpty()) {
            throw new ManagementException("请至少选择一个文件或文件夹");
        }
        ProjectRecord project = database.requireProject(projectId);
        List<ResolvedMember> resolved = new ArrayList<>();
        for (GroupMember member : members) {
            if (member == null) throw new ManagementException("可选内容成员不完整");
            if ((member.path() == null || member.path().isBlank()) && member.modId() != null && !add) {
                // A mod that left the source directory can still be taken out by its ID.
                resolved.add(new ResolvedMember(null, false, member.modId().trim()));
                continue;
            }
            String path = normalize(member.path());
            String modId = null;
            if (!member.directory() && ManagedPaths.isModJar(path)) {
                try {
                    Path jar = PathSafety.resolveInside(project.sourceDirectory(), path);
                    modId = ModMetadataReader.read(jar).map(ModMetadata::componentId).orElse(null);
                } catch (IOException e) {
                    throw new ManagementException("无法读取模组信息：" + path, e);
                }
            }
            resolved.add(new ResolvedMember(path, member.directory(), modId));
        }
        return update(projectId, rules -> {
            if (rules.optionalGroup(groupId).isEmpty()) {
                throw new ManagementException("可选分组不存在：" + groupId);
            }
            if (add) {
                for (ResolvedMember member : resolved) {
                    if (rules.presetFor(member.path()) != MaintenancePreset.SYNC) {
                        throw new ManagementException("可选包只接受普通同步内容；请先改为普通同步");
                    }
                    boolean explicitlyRequired = rules.presets().stream().anyMatch(rule ->
                            rule.directory() == member.directory()
                                    && rule.path().equalsIgnoreCase(member.path())
                                    && rule.preset() == MaintenancePreset.REQUIRED);
                    if (explicitlyRequired) {
                        throw new ManagementException("“" + member.path()
                                + "”单独设为了必需同步，不能由玩家选择；请先把它改为普通同步");
                    }
                }
            }
            List<OptionalGroupRule> groups = new ArrayList<>();
            for (OptionalGroupRule group : rules.optionalGroups()) {
                if (group.id().equals(groupId)) {
                    groups.add(add ? withMembers(group, resolved) : withoutMembers(group, resolved));
                } else {
                    groups.add(add ? withoutMembers(group, resolved) : group);
                }
            }
            return rules.withOptionalGroups(groups);
        });
    }

    private static OptionalGroupRule withMembers(OptionalGroupRule group, List<ResolvedMember> members) {
        List<String> modIds = new ArrayList<>(group.modIds());
        List<String> files = new ArrayList<>(group.files());
        List<String> directories = new ArrayList<>(group.directories());
        for (ResolvedMember member : members) {
            if (member.modId() != null) {
                if (modIds.stream().noneMatch(id -> id.equalsIgnoreCase(member.modId()))) {
                    modIds.add(member.modId());
                }
                files.removeIf(file -> file.equalsIgnoreCase(member.path()));
            } else {
                List<String> target = member.directory() ? directories : files;
                if (target.stream().noneMatch(path -> path.equalsIgnoreCase(member.path()))) {
                    target.add(member.path());
                }
            }
        }
        return new OptionalGroupRule(group.id(), group.title(), group.description(),
                group.defaultInstall(), modIds, files, directories);
    }

    private static OptionalGroupRule withoutMembers(OptionalGroupRule group, List<ResolvedMember> members) {
        List<String> modIds = new ArrayList<>(group.modIds());
        List<String> files = new ArrayList<>(group.files());
        List<String> directories = new ArrayList<>(group.directories());
        for (ResolvedMember member : members) {
            if (member.modId() != null) modIds.removeIf(id -> id.equalsIgnoreCase(member.modId()));
            (member.directory() ? directories : files).removeIf(path -> path.equalsIgnoreCase(member.path()));
        }
        return new OptionalGroupRule(group.id(), group.title(), group.description(),
                group.defaultInstall(), modIds, files, directories);
    }

    private static int indexOfGroup(List<OptionalGroupRule> groups, String groupId) {
        for (int index = 0; index < groups.size(); index++) {
            if (groups.get(index).id().equals(groupId)) return index;
        }
        throw new ManagementException("可选分组不存在：" + groupId);
    }

    public ProjectRecord deleteOptionalGroup(String projectId, String groupId) {
        return update(projectId, rules -> {
            if (rules.optionalGroup(groupId).isEmpty()) {
                throw new ManagementException("可选分组不存在：" + groupId);
            }
            return rules.withOptionalGroups(rules.optionalGroups().stream()
                    .filter(group -> !group.id().equals(groupId)).toList());
        });
    }

    /** Creates a stable group ID from a title. */
    public static String groupIdFor(String title, List<OptionalGroupRule> existing) {
        String base = title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty() || !base.matches("[a-z0-9][a-z0-9._-]{0,40}")) {
            base = "group-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String candidate = base;
        int suffix = 2;
        while (true) {
            String probe = candidate;
            if (existing.stream().noneMatch(group -> group.id().equals(probe))) return candidate;
            candidate = base + "-" + suffix++;
        }
    }

    /**
     * "改为可选": puts a file the owner just removed back into the standard
     * directory from the published object store and adds it to an optional
     * group, so players decide instead of every copy being deleted. Mods join
     * the group by mod ID so later renamed versions stay optional.
     */
    public ProjectRecord makeOptional(String projectId, String path, String groupId) {
        String normalized = normalize(path);
        ProjectRecord project = database.requireProject(projectId);
        if (project.rules().optionalGroup(groupId).isEmpty()) {
            throw new ManagementException("可选分组不存在：" + groupId);
        }
        ManifestFile published = database.latestRelease(projectId)
                .map(database::readManifest)
                .flatMap(manifest -> manifest.files().stream()
                        .filter(file -> file.path().equalsIgnoreCase(normalized)).findFirst())
                .orElse(null);
        try {
            Path target = PathSafety.resolveInside(project.sourceDirectory(), normalized);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (published == null) {
                    throw new ManagementException("标准整合包目录和最新发布中都没有这个文件：" + normalized);
                }
                Path object = objects.require(published.sha256());
                objects.verify(object, published.sha256(), published.size());
                AtomicFiles.copyReplace(object, target);
            }
        } catch (IOException e) {
            throw new ManagementException("无法把文件放回标准整合包目录：" + normalized, e);
        }
        String componentId = published == null ? null : published.componentId();
        return update(projectId, rules -> {
            boolean explicitlyRequired = rules.presets().stream().anyMatch(rule -> !rule.directory()
                    && rule.path().equalsIgnoreCase(normalized)
                    && rule.preset() == MaintenancePreset.REQUIRED);
            if (explicitlyRequired) rules = rules.withPreset(normalized, false, null);
            List<OptionalGroupRule> groups = new ArrayList<>();
            for (OptionalGroupRule group : rules.optionalGroups()) {
                if (!group.id().equals(groupId)) {
                    groups.add(group);
                    continue;
                }
                List<String> modIds = new ArrayList<>(group.modIds());
                List<String> files = new ArrayList<>(group.files());
                if (componentId != null && ManagedPaths.isModJar(normalized)) {
                    if (modIds.stream().noneMatch(id -> id.equalsIgnoreCase(componentId))) modIds.add(componentId);
                } else if (files.stream().noneMatch(file -> file.equalsIgnoreCase(normalized))) {
                    files.add(normalized);
                }
                groups.add(new OptionalGroupRule(group.id(), group.title(), group.description(),
                        group.defaultInstall(), modIds, files, group.directories()));
            }
            return rules.withOptionalGroups(groups);
        });
    }

    // ---- withdrawals and corrections --------------------------------------------

    /** Every version of every file the project ever published. */
    public List<FileHistory> history(String projectId) {
        ProjectRecord project = database.requireProject(projectId);
        Map<String, Map<String, VersionBuilder>> byPath = new LinkedHashMap<>();
        Map<String, String> displayPath = new LinkedHashMap<>();
        List<StoredRelease> releases = database.listReleases(projectId).stream()
                .sorted(Comparator.comparingLong(StoredRelease::sequence)).toList();
        ReleaseManifest latest = null;
        for (StoredRelease release : releases) {
            ReleaseManifest manifest = database.readManifest(release);
            latest = manifest;
            for (ManifestFile file : manifest.files()) {
                String key = ManagedPaths.fold(file.path());
                displayPath.putIfAbsent(key, file.path());
                byPath.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(file.sha256(), ignored -> new VersionBuilder(file, release))
                        .seenIn(release);
            }
        }
        Set<String> current = new java.util.HashSet<>();
        if (latest != null) latest.files().forEach(file -> current.add(file.sha256() + "|"
                + ManagedPaths.fold(file.path())));
        List<FileHistory> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, VersionBuilder>> entry : byPath.entrySet()) {
            List<FileVersion> versions = new ArrayList<>();
            for (VersionBuilder builder : entry.getValue().values()) {
                Withdrawal matched = project.rules().withdrawals().stream()
                        .filter(withdrawal -> withdrawal.items().stream().anyMatch(item ->
                                item.sha256().equals(builder.file.sha256())
                                || (withdrawal.removal() && RemovalPolicies.sameResource(item, builder.file))))
                        .sorted(Comparator.comparing(Withdrawal::removal)).findFirst().orElse(null);
                versions.add(new FileVersion(builder.file.sha256(), builder.file.size(),
                        builder.file.componentId(), builder.file.version(),
                        builder.first.displayVersion(), builder.last.displayVersion(),
                        builder.count, current.contains(builder.file.sha256() + "|" + entry.getKey()),
                        matched == null ? null : matched.id(), matched == null ? null : matched.kind().name(),
                        builder.releaseIds));
            }
            result.add(new FileHistory(displayPath.get(entry.getKey()), versions));
        }
        result.sort(Comparator.comparing(FileHistory::path, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** 撤回问题版本: selected historical versions are removed from every player that still has them. */
    public Withdrawal withdraw(String projectId, String reason, List<VersionReference> versions) {
        if (versions == null || versions.isEmpty()) {
            throw new ManagementException("请至少选择一个要撤回的历史版本");
        }
        String text = requireReason(reason);
        Map<String, FileVersion> known = new LinkedHashMap<>();
        Map<String, String> pathsByKey = new LinkedHashMap<>();
        for (FileHistory history : history(projectId)) {
            for (FileVersion version : history.versions()) {
                String key = ManagedPaths.fold(history.path()) + "|" + version.sha256();
                known.put(key, version);
                pathsByKey.put(key, history.path());
            }
        }
        List<WithdrawalItem> items = new ArrayList<>();
        for (VersionReference reference : versions) {
            String key = ManagedPaths.fold(normalize(reference.path())) + "|" + reference.sha256();
            FileVersion version = known.get(key);
            if (version == null) {
                throw new ManagementException("只能撤回本项目发布过的版本：" + reference.path());
            }
            items.add(new WithdrawalItem(version.sha256(), version.size(), pathsByKey.get(key),
                    version.componentId(), version.version()));
        }
        Withdrawal withdrawal = new Withdrawal(directiveId("withdraw"), text, Instant.now(), items);
        update(projectId, rules -> {
            List<Withdrawal> updated = new ArrayList<>(rules.withdrawals());
            updated.add(withdrawal);
            return rules.withWithdrawals(updated);
        });
        // Prepare the next target in one workflow; a newer replacement in the source is kept.
        ProjectRecord current = database.requireProject(projectId);
        SourceFileService sources = new SourceFileService(paths, database, new JsonCodec());
        for (WithdrawalItem item : items) {
            try {
                Path source = PathSafety.resolveInside(current.sourceDirectory(), item.path());
                if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        && !new RuleSet(current.rules()).decide(item.path()).excluded()
                        && CryptoSupport.sha256(source).equals(item.sha256())) {
                    sources.removeBatch(projectId, List.of(new SourceFileService.SourceRemoval(item.path(),
                            current.rules().insideCleanupDirectory(item.path()) ? RemovalAction.DELETE : RemovalAction.RELEASE,
                            item.sha256())));
                }
            } catch (IOException error) { throw new ManagementException("无法准备问题文件的移除", error); }
        }
        return withdrawal;
    }

    public ProjectRecord revokeWithdrawal(String projectId, String withdrawalId) {
        return update(projectId, rules -> {
            if (rules.withdrawals().stream().noneMatch(item -> item.id().equals(withdrawalId))) {
                throw new ManagementException("撤回规则不存在：" + withdrawalId);
            }
            return rules.withWithdrawals(rules.withdrawals().stream()
                    .filter(item -> !item.id().equals(withdrawalId)).toList());
        });
    }

    /** 修正配置: put the currently published content in place even where players kept their own copy. */
    public Correction correct(String projectId, String path, CorrectionMode mode, String reason,
                              List<String> badSha256) {
        if (mode == null) throw new ManagementException("请选择修正方式");
        String normalized = normalize(path);
        String text = requireReason(reason);
        List<String> bad = badSha256 == null ? List.of() : badSha256.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
        if (mode == CorrectionMode.KNOWN_BAD && bad.isEmpty()) {
            throw new ManagementException("“只替换已知坏版本”需要至少选择一个有问题的历史版本");
        }
        if (mode == CorrectionMode.ONCE && !bad.isEmpty()) {
            throw new ManagementException("“覆盖一次”不需要选择历史版本");
        }
        for (String hash : bad) {
            if (!Hex.isSha256(hash)) throw new ManagementException("历史版本标识无效：" + hash);
        }
        ProjectRecord project = database.requireProject(projectId);
        try {
            Path source = PathSafety.resolveInside(project.sourceDirectory(), normalized);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || new RuleSet(project.rules()).decide(normalized).excluded()) {
                throw new ManagementException("请先在整合包文件列表中准备正确文件：" + normalized);
            }
            String currentHash = CryptoSupport.sha256(source);
            if (bad.contains(currentHash)) throw new ManagementException("正确文件仍是选中的问题版本，请先替换它");
            String component = ModMetadataReader.read(source).map(ModMetadata::componentId).orElse(null);
            for (String hash : bad) {
                boolean known = history(projectId).stream().anyMatch(file -> file.versions().stream().anyMatch(version ->
                        version.sha256().equals(hash) && (file.path().equalsIgnoreCase(normalized)
                        || (component != null && component.equalsIgnoreCase(version.componentId())
                        && ManagedPaths.topLevel(file.path()).equalsIgnoreCase(ManagedPaths.topLevel(normalized))))));
                if (!known) throw new ManagementException("只能选择这个文件或模组在本项目中发布过的问题版本");
            }
        } catch (IOException error) { throw new ManagementException("无法验证正确文件", error); }
        Correction correction = new Correction(directiveId("fix"), normalized, mode, bad, text, Instant.now());
        update(projectId, rules -> {
            List<Correction> updated = new ArrayList<>();
            for (Correction existing : rules.corrections()) {
                if (!existing.path().equalsIgnoreCase(normalized)) updated.add(existing);
            }
            updated.add(correction);
            return rules.withCorrections(updated);
        });
        return correction;
    }

    public ProjectRecord revokeCorrection(String projectId, String correctionId) {
        return update(projectId, rules -> {
            if (rules.corrections().stream().noneMatch(item -> item.id().equals(correctionId))) {
                throw new ManagementException("修正规则不存在：" + correctionId);
            }
            return rules.withCorrections(rules.corrections().stream()
                    .filter(item -> !item.id().equals(correctionId)).toList());
        });
    }

    // ---- helpers -----------------------------------------------------------------

    private ProjectRecord update(String projectId, UnaryOperator<ProjectRules> change) {
        try (ProjectLock ignored = ProjectLock.acquire(
                PathSafety.resolveInside(paths.locks(), projectId + ".lock"))) {
            ProjectRecord current = database.requireProject(projectId);
            ProjectRules updated = change.apply(current.rules());
            new RuleSet(updated);
            database.updateProject(current.id(), current.displayName(), current.sourceDirectory(),
                    current.publicBaseUrl(), current.branding(), updated);
            return database.requireProject(projectId);
        } catch (IOException e) {
            throw new ManagementException("Unable to lock maintenance rules", e);
        }
    }

    private static String normalize(String path) {
        try {
            return PathSafety.normalizeManifestPath(path == null ? null : path.trim());
        } catch (RuntimeException e) {
            throw new ManagementException("路径无效：" + path, e);
        }
    }

    private static String requireReason(String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) throw new ManagementException("请填写原因，玩家会在更新器里看到它");
        if (text.length() > 500 || text.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n')) {
            throw new ManagementException("原因过长或包含控制字符");
        }
        return text;
    }

    private static String directiveId(String prefix) {
        return prefix + "-" + DIRECTIVE_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private static final class VersionBuilder {
        private final ManifestFile file;
        private final StoredRelease first;
        private StoredRelease last;
        private int count;
        private final List<String> releaseIds = new ArrayList<>();

        VersionBuilder(ManifestFile file, StoredRelease first) {
            this.file = file;
            this.first = first;
            this.last = first;
        }

        VersionBuilder seenIn(StoredRelease release) {
            last = release;
            count++;
            releaseIds.add(release.releaseId());
            return this;
        }
    }

    /** All published versions of one path, oldest first. */
    public record FileHistory(String path, List<FileVersion> versions) {
        public FileHistory {
            versions = List.copyOf(versions);
        }
    }

    /**
     * @param withdrawnBy the active withdrawal covering this version, if any
     */
    public record FileVersion(String sha256, long size, String componentId, String version,
                              String firstRelease, String lastRelease, int releaseCount,
                              boolean currentlyPublished, String withdrawnBy, String withdrawnKind,
                              List<String> releaseIds) {
        public FileVersion {
            releaseIds = releaseIds == null ? List.of() : List.copyOf(releaseIds);
        }

        public FileVersion(String sha256, long size, String componentId, String version,
                           String firstRelease, String lastRelease, int releaseCount,
                           boolean currentlyPublished, String withdrawnBy, String withdrawnKind) {
            this(sha256, size, componentId, version, firstRelease, lastRelease, releaseCount,
                    currentlyPublished, withdrawnBy, withdrawnKind, List.of());
        }
    }

    public record VersionReference(String path, String sha256) {
    }

    /** One preset edit; {@code preset == null} clears the rule so the default applies again. */
    public record PresetChange(String path, boolean directory, MaintenancePreset preset) {
    }

    /** A file or directory by path, or (when taking a mod out) a mod by its ID alone. */
    public record GroupMember(String path, boolean directory, String modId) {
        public GroupMember(String path, boolean directory) {
            this(path, directory, null);
        }
    }

    private record ResolvedMember(String path, boolean directory, String modId) {
    }
}
