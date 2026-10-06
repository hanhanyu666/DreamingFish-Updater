package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Durable operation history and explicit, checked restores of private workspace settings. */
public final class WorkspaceOperationService {
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final SourceTransferService transfers;
    private final SourceOperationJournal sourceOperations;

    public WorkspaceOperationService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths; this.database = database; this.json = json;
        this.transfers = new SourceTransferService(paths, database, json);
        this.sourceOperations = new SourceOperationJournal(paths, database, json);
    }

    public List<Entry> history(String projectId) {
        ProjectSettingsSnapshot current = ProjectSettingsSnapshot.of(database.requireProject(projectId));
        List<Entry> entries = new ArrayList<>();
        for (SettingsOperation operation : database.settingsOperations(projectId)) {
            List<ProjectSettingsSnapshot.Change> changes = operation.before().changesTo(operation.after());
            boolean undo = !operation.undone() && !operation.kind().equals("UNDO");
            String reason = "";
            if (undo) {
                try { ProjectSettingsSnapshot.revert(current, operation.before(), operation.after()); }
                catch (ManagementException conflict) { undo = false; reason = conflict.getMessage(); }
            }
            String title = operation.kind().equals("RESTORE") ? "恢复已发布版本的维护设置" : operation.kind().equals("UNDO") ? "撤销设置修改" : changes.size() == 1
                    ? "修改“" + changes.getFirst().setting() + "”" : "修改 " + changes.size() + " 项设置";
            entries.add(new Entry("settings/" + operation.id(), "SETTINGS", title, operation.createdAt(),
                    operation.undone(), undo, reason, changes));
        }
        for (var entry : transfers.history(projectId)) {
            entries.add(new Entry("import/" + entry.id(), "FILES", "导入 " + entry.path(), entry.createdAt(),
                    entry.undone(), !entry.undone(), "", List.of(new ProjectSettingsSnapshot.Change(entry.path(),
                    entry.previousPaths().isEmpty() ? "没有旧文件" : String.join("、", entry.previousPaths()), "导入文件"))));
        }
        for (var entry : sourceOperations.history(projectId)) {
            String title = entry.kind().equals("MKDIR") ? "新建文件夹 " + entry.directory()
                    : (entry.kind().equals("REMOVE") ? "移除 " : "导入或覆盖 ") + entry.after().size() + " 个文件";
            String reason = entry.undone() ? "" : sourceOperations.unavailableReason(database.requireProject(projectId), entry);
            boolean undo = !entry.undone() && reason.isEmpty();
            List<ProjectSettingsSnapshot.Change> changes = sourceChanges(entry);
            entries.add(new Entry("source/" + entry.id(), "FILES", title, entry.createdAt(), entry.undone(), undo, reason, changes));
        }
        for (StoredRelease release : database.listReleases(projectId)) {
            entries.add(new Entry("release/" + release.releaseId(), "PUBLISH", "发布 " + release.displayVersion(),
                    release.createdAt(), false, false, "", List.of(new ProjectSettingsSnapshot.Change("整合包版本", "", release.displayVersion()))));
        }
        entries.sort(Comparator.comparing(Entry::createdAt).reversed().thenComparing(Entry::id));
        return List.copyOf(entries.stream().limit(200).toList());
    }

    public UndoPlan previewUndo(String projectId, String id) {
        ProjectRecord project = database.requireProject(projectId);
        if (id != null && id.startsWith("source/")) {
            var plan = sourceOperations.previewUndo(projectId, id.substring(7));
            return new UndoPlan(id, "恢复文件操作前内容", plan.stamp(), sourceChanges(plan.receipt()).stream()
                    .map(change -> new ProjectSettingsSnapshot.Change(change.setting(), change.after(), change.before())).toList(), plan.receipt().undone());
        }
        if (id != null && id.startsWith("settings/")) {
            SettingsOperation operation = database.settingsOperation(projectId, id.substring(9));
            if (operation.kind().equals("UNDO")) throw new ManagementException("这条记录用于查看，不能直接撤销");
            ProjectSettingsSnapshot current = ProjectSettingsSnapshot.of(project);
            ProjectSettingsSnapshot target = operation.undone() ? current
                    : ProjectSettingsSnapshot.revert(current, operation.before(), operation.after());
            return new UndoPlan(id, "撤销设置修改", settingsStamp(projectId, id, current, target),
                    current.changesTo(target), operation.undone());
        }
        if (id != null && id.startsWith("import/")) {
            var plan = transfers.plan(projectId, id.substring(7));
            if (!plan.committed() && !plan.undone()) throw new ManagementException("这条导入还没有完成");
            String stamp = CryptoSupport.sha256(json.write(List.of(id, plan.stamp(), project.rules(), project.sourceDirectory().toString(), project.nextSequence())));
            var original = transfers.importView(projectId, id.substring(7));
            List<ProjectSettingsSnapshot.Change> changes = new ArrayList<>();
            changes.add(new ProjectSettingsSnapshot.Change(plan.path(), "导入后的文件",
                    original.previousPaths().contains(plan.path()) ? "恢复原文件" : "移出新导入的文件"));
            for (String previous : original.previousPaths()) if (!previous.equals(plan.path())) {
                changes.add(new ProjectSettingsSnapshot.Change(previous, "被替换", "恢复原文件"));
            }
            return new UndoPlan(id, "恢复导入前内容", stamp, List.copyOf(changes), plan.undone());
        }
        throw new ManagementException("操作记录不存在或不能撤销");
    }

    public Result undo(String projectId, String id, String stamp) {
        UndoPlan plan = previewUndo(projectId, id);
        if (plan.undone()) return new Result("操作已撤销", "");
        requireStamp(plan.stamp(), stamp);
        if (id.startsWith("source/")) {
            sourceOperations.undo(projectId, id.substring(7), stamp);
            return new Result("已恢复文件操作前内容", "");
        }
        if (id.startsWith("import/")) {
            var result = transfers.undo(projectId, id.substring(7));
            return new Result("已恢复导入前内容", result.checkError());
        }
        try (ProjectLock ignored = lock(projectId)) {
            ProjectRecord project = database.requireProject(projectId);
            UndoPlan current = previewUndo(projectId, id);
            requireStamp(current.stamp(), stamp);
            database.undoSettingsOperation(projectId, id.substring(9), ProjectSettingsSnapshot.of(project));
            return new Result("已撤销设置修改", "");
        } catch (IOException error) { throw new ManagementException("无法撤销设置修改", error); }
    }

    public RestorePlan previewRestorePublished(String projectId) {
        ProjectRecord project = database.requireProject(projectId);
        StoredRelease release = database.latestRelease(projectId).orElseThrow(() -> new ManagementException("还没有已发布版本"));
        verifyRelease(project, release);
        ProjectRules rules = database.releaseSettings(projectId, release.releaseId())
                .orElseGet(() -> rulesFromManifest(project.rules(), database.readManifest(release)));
        if (project.rules().simplified() && !rules.simplified()) rules = rules.asSimplified();
        ProjectSettingsSnapshot current = ProjectSettingsSnapshot.of(project);
        ProjectSettingsSnapshot target = new ProjectSettingsSnapshot(current.displayName(), current.sourceDirectory(),
                current.publicBaseUrl(), current.branding(), rules);
        String stamp = CryptoSupport.sha256(json.write(List.of("published-settings", release.releaseId(), release.manifestSha256(),
                current, target, sourceState(projectId))));
        return new RestorePlan(release.releaseId(), release.displayVersion(), stamp, current.changesTo(target), rules);
    }

    public Result restorePublished(String projectId, String stamp) {
        try (ProjectLock ignored = lock(projectId)) {
            RestorePlan plan = previewRestorePublished(projectId);
            requireStamp(plan.stamp(), stamp);
            ProjectRecord project = database.requireProject(projectId);
            new RuleSet(plan.rules());
            database.restoreMaintenance(projectId, plan.releaseId(), plan.rules(), ProjectSettingsSnapshot.of(project));
            return new Result("已恢复 " + plan.displayVersion() + " 的维护设置", "");
        } catch (IOException error) { throw new ManagementException("无法恢复已发布版本设置", error); }
    }

    private String settingsStamp(String projectId, String id, ProjectSettingsSnapshot current, ProjectSettingsSnapshot target) {
        return CryptoSupport.sha256(json.write(List.of(projectId, id, current, target,
                database.requireProject(projectId).nextSequence(), sourceState(projectId))));
    }

    private List<SourceState> sourceState(String projectId) {
        return new SourceFileService(paths, database, json).list(projectId).stream()
                .map(file -> new SourceState(file.path(), file.size(), file.lastModifiedMillis())).toList();
    }

    private static List<ProjectSettingsSnapshot.Change> sourceChanges(SourceOperationJournal.Receipt entry) {
        if (entry.directory() != null) return List.of(new ProjectSettingsSnapshot.Change(entry.directory() + "/", "没有此文件夹", "新建文件夹"));
        List<ProjectSettingsSnapshot.Change> changes = new ArrayList<>();
        for (var file : entry.after()) {
            String before = entry.before().stream().anyMatch(item -> item.path().equals(file.path())) ? "原文件" : "不存在";
            changes.add(new ProjectSettingsSnapshot.Change(file.path(), before, file.sha256() == null ? "移除并归档" : "导入后的文件"));
        }
        return List.copyOf(changes);
    }

    private ProjectLock lock(String projectId) throws IOException {
        return ProjectLock.acquire(PathSafety.resolveInside(paths.locks(), projectId + ".lock"));
    }

    private static void requireStamp(String current, String expected) {
        if (!Objects.equals(current, expected)) throw new ManagementException("文件或设置已改变，请重新查看恢复预览");
    }

    private void verifyRelease(ProjectRecord project, StoredRelease release) {
        try {
            byte[] bytes = Files.readAllBytes(release.manifestPath());
            if (!release.manifestSha256().equals(CryptoSupport.sha256(bytes))
                    || !CryptoSupport.verify(bytes, Base64.getDecoder().decode(release.signature()), CryptoSupport.decodePublicKey(project.publicKey()))) {
                throw new ManagementException("已发布版本校验失败，无法恢复设置");
            }
        } catch (IOException | IllegalArgumentException error) { throw new ManagementException("无法读取已发布版本设置", error); }
    }

    private static ProjectRules rulesFromManifest(ProjectRules current, ReleaseManifest manifest) {
        MaintenanceModel model = MaintenanceModel.of(manifest);
        List<PresetRule> presets = new ArrayList<>();
        for (String directory : model.cleanupDirectories()) presets.add(new PresetRule(directory, true, MaintenancePreset.REQUIRED));
        for (var file : manifest.files()) {
            var behavior = model.behaviorOf(file);
            if (behavior == MaintenanceModel.Behavior.LEGACY_MISSING_ONLY || behavior == MaintenanceModel.Behavior.DEFAULT_CONFIG) {
                throw new ManagementException("这个旧版本使用已停用的维护方式，请先整理并发布当前设置");
            }
            MaintenancePreset preset = MaintenancePreset.valueOf(behavior.name());
            if (preset != MaintenancePreset.SYNC && !model.insideCleanupDirectory(file.path())) presets.add(new PresetRule(file.path(), false, preset));
        }
        List<OptionalGroupRule> groups = new ArrayList<>();
        for (var group : model.optionalGroups()) {
            List<String> files = manifest.files().stream().filter(file -> group.id().equals(file.optionalGroup())).map(file -> file.path()).toList();
            List<String> mods = manifest.files().stream().filter(file -> group.id().equals(file.optionalGroup()) && file.componentId() != null)
                    .map(file -> file.componentId()).distinct().toList();
            groups.add(new OptionalGroupRule(group.id(), group.title(), group.description(), group.defaultInstall(), mods, files, List.of()));
        }
        return new ProjectRules(current.rules(), List.of(), List.of(), presets, model.cleanupDirectories(), groups,
                manifest.withdrawals(), manifest.corrections(), current.simplified());
    }

    public record Entry(String id, String kind, String title, Instant createdAt, boolean undone,
                        boolean canUndo, String undoUnavailableReason, List<ProjectSettingsSnapshot.Change> changes) {}
    public record UndoPlan(String id, String title, String stamp, List<ProjectSettingsSnapshot.Change> changes, boolean undone) {}
    public record RestorePlan(String releaseId, String displayVersion, String stamp,
                              List<ProjectSettingsSnapshot.Change> changes, ProjectRules rules) {}
    public record Result(String message, String checkError) {}
    private record SourceState(String path, long size, long lastModifiedMillis) {}
}
