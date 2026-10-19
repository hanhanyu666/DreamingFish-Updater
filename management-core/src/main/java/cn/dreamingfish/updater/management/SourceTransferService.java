package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Stages browser transfers, checks their effects, then commits one recoverable import. */
public final class SourceTransferService {
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final SourceFileService files;
    private final ScanService scanner;
    private final java.util.function.Consumer<String> checkpoint;

    public SourceTransferService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this(paths, database, json, phase -> {});
    }

    SourceTransferService(ManagementPaths paths, ManagementDatabase database, JsonCodec json,
                          java.util.function.Consumer<String> checkpoint) {
        this.checkpoint = checkpoint;
        this.paths = paths;
        this.database = database;
        this.json = json;
        files = new SourceFileService(paths, database, json);
        scanner = new ScanService(paths, database, json);
    }

    public Plan stage(String projectId, String targetPath, InputStream input, long length) {
        return stage(projectId, targetPath, input, length, null);
    }

    public Plan stage(String projectId, String targetPath, InputStream input, long length, String requestId) {
        ProjectRecord project = database.requireProject(projectId);
        String target = PathSafety.normalizeManifestPath(targetPath);
        validateTarget(project, target);
        String id = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        Path directory = directory(projectId, id);
        try (ProjectLock uploadLock = ProjectLock.acquire(paths.locks().resolve("upload-" + id + ".lock"))) {
            if (Files.isRegularFile(directory.resolve("stage.json"))) {
                Stage previous = readStage(projectId, id);
                if (!previous.path().equals(target)) throw new ManagementException("请求身份已用于另一个目标，请新建导入任务");
                return plan(projectId, id);
            }
            if (Files.exists(directory.resolve("receipt.json")) || Files.exists(directory.resolve("intent.json"))
                    || Files.exists(paths.root().resolve("source-transactions").resolve(projectId).resolve(id+".json")))
                throw new ManagementException("这个请求有未核对的操作记录，请先恢复或复查");
            if(Files.exists(paths.root().resolve("source-import-expired").resolve(projectId).resolve(id+".json")))
                throw new ManagementException("原暂存已过期，请使用新的请求身份重新检查");
            boolean createdHere = false;
            try(StorageService.Lease budget=new StorageService(paths,database,json).reserve(length<0?SourceFileService.MAX_UPLOAD_BYTES:length,0)) {
            Files.createDirectories(directory);
            createdHere = true;
            Path payload = directory.resolve(target.toLowerCase(Locale.ROOT).endsWith(".jar") ? "payload.jar" : "payload.bin");
            budget.copy(input, payload, length, SourceFileService.MAX_UPLOAD_BYTES);
            ModMetadata metadata = target.toLowerCase(Locale.ROOT).endsWith(".jar")
                    ? ModMetadataReader.read(payload).orElse(null) : null;
            Stage stage = new Stage(id, target, Files.size(payload), CryptoSupport.sha256(payload),
                    metadata, payload.getFileName().toString(), Instant.now());
            AtomicFiles.write(directory.resolve("stage.json"), json.write(stage));
            return plan(projectId, id);
            } catch (IOException | RuntimeException error) {
                // Rejecting an existing identity must never discard its earlier upload.
                // Once stage.json is durable, retain it even if returning the plan fails.
                if (createdHere && !Files.exists(directory.resolve("stage.json"), LinkOption.NOFOLLOW_LINKS)) {
                    try { discard(projectId, id); }
                    catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                }
                throw error;
            }
        } catch (IOException | RuntimeException error) {
            throw failure("无法暂存文件", error);
        }
    }

    public Plan plan(String projectId, String id) {
        Stage stage = readStage(projectId, id);
        ProjectRecord project = database.requireProject(projectId);
        validateTarget(project, stage.path());
        List<SourceFileService.SourceFileEntry> entries = files.listVerified(projectId);
        Snapshot existing = snapshot(project, stage.path());
        ModMetadata existingMetadata = entries.stream().filter(entry -> fold(entry.path()).equals(fold(stage.path()))
                && entry.componentId() != null).findFirst().map(entry -> new ModMetadata(entry.componentId(), entry.displayName(), entry.version())).orElse(null);
        List<Match> replacements = new ArrayList<>();
        for (var entry : entries) {
            if (stage.metadata() != null && stage.metadata().componentId().equals(entry.componentId())
                    && !fold(entry.path()).equals(fold(stage.path()))) {
                Snapshot snapshot = snapshot(project, entry.path());
                if (snapshot == null) throw new ManagementException("源文件正在变化，请重新检查");
                replacements.add(new Match(entry.path(), snapshot.sha256(), entry.version(),
                        entry.preset(), entry.optionalGroup()));
            }
        }
        String stamp = CryptoSupport.sha256(json.write(Arrays.asList(existing, replacements,
                configurationStamp(project.rules()), project.sourceDirectory().toString())));
        Receipt receipt = Files.isRegularFile(directory(projectId, id).resolve("receipt.json")) ? readReceipt(projectId, id) : null;
        return new Plan(stage.id(), stage.path(), stage.size(), stage.sha256(), stage.metadata(),
                existing, existingMetadata, List.copyOf(replacements), stamp, receipt != null && !receipt.undone(), receipt != null && receipt.undone());
    }

    public Plan stageServer(String projectId, Path external, String targetPath) {
        return stageServer(projectId, external, targetPath, null);
    }

    public Plan stageServer(String projectId, Path external, String targetPath, String requestId) {
        if (requestId != null && hasStage(projectId, requestId)) return plan(projectId, requestId);
        Path source = external.toAbsolutePath().normalize();
        try {
            PathSafety.assertSafePathTree(source);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                throw new ManagementException("请选择服务器上的普通文件");
            long size = Files.size(source);
            Plan plan;
            try (InputStream input = Files.newInputStream(source)) { plan = stage(projectId, targetPath, input, size, requestId); }
            if (Files.size(source) != size || !CryptoSupport.sha256(source).equals(plan.sha256())) {
                discard(projectId, plan.id());
                throw new ManagementException("服务器文件在复制时变化，请重新检查");
            }
            return plan;
        } catch (IOException error) { throw failure("无法暂存服务器文件", error); }
    }

    public Result commit(String projectId, String id, String action, String stamp) {
        return commit(projectId, id, action, stamp, true);
    }

    public Result commit(String projectId, String id, String action, String stamp, boolean refreshPreview) {
        return commit(projectId,id,action,stamp,refreshPreview,false);
    }
    Result commitLegacy(String projectId,String id,boolean overwrite) {
        Plan plan=plan(projectId,id);
        return commit(projectId,id,overwrite?"OVERWRITE":"ADD",plan.stamp(),false,true);
    }
    private Result commit(String projectId,String id,String action,String stamp,boolean refreshPreview,boolean legacy) {
        Stage stage = readStage(projectId, id);
        if (!Set.of("ADD", "OVERWRITE", "REPLACE_MOD").contains(action))
            throw new ManagementException("请选择新增、覆盖或替换旧模组");
        Path directory = directory(projectId, id);
        if (Files.exists(directory.resolve("receipt.json"))) {
            Receipt previous = readReceipt(projectId, id);
            if (previous.undone()) throw new ManagementException("这个导入已恢复，请重新选择文件开始新的导入");
            return result(projectId, previous, refreshPreview);
        }
        Receipt receipt;
        try (ProjectLock ignored = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
            if (Files.isRegularFile(directory.resolve("receipt.json"))) {
                Receipt previous = readReceipt(projectId,id);
                if (previous.undone()) throw new ManagementException("这个导入已恢复，请开始新的导入");
                return result(projectId,previous,refreshPreview);
            }
            Plan plan = plan(projectId, id);
            if (!Objects.equals(plan.stamp(), stamp))
                throw new ManagementException("源文件或维护设置已变化，请重新检查上传清单");
            if (action.equals("ADD") && plan.existing() != null)
                throw new ManagementException("目标文件已经存在，请选择覆盖或跳过");
            if (!legacy && !action.equals("REPLACE_MOD") && !plan.replacements().isEmpty())
                throw new ManagementException("同一模组已有其他文件，请明确选择替换旧版本");
            if (action.equals("REPLACE_MOD") && plan.replacements().isEmpty())
                throw new ManagementException("没有可替换的旧模组，请重新检查");
            if (action.equals("REPLACE_MOD") && plan.existing() != null && (plan.existingMetadata() == null
                    || !plan.existingMetadata().componentId().equals(plan.metadata().componentId())))
                throw new ManagementException("目标同名文件属于其他内容，请修改保存路径后重新检查");
            ProjectRecord project = database.requireProject(projectId);
            LinkedHashSet<String> affected = new LinkedHashSet<>();
            affected.add(stage.path());
            if (action.equals("REPLACE_MOD")) plan.replacements().forEach(item -> affected.add(item.path()));
            List<Snapshot> before = new ArrayList<>();
            for(String path:affected){Snapshot previous=snapshot(project,path);if(previous!=null)before.add(previous);}
            long backupBytes=before.stream().mapToLong(Snapshot::size).sum();
            try(StorageService.Lease budget=new StorageService(paths,database,json).reserve(Math.addExact(backupBytes,stage.size()),backupBytes)) {
            for(Snapshot previous:before){
                Path archive=PathSafety.resolveInside(directory.resolve("before"),previous.path());
                budget.charge(previous.size());AtomicFiles.copyReplace(source(project,previous.path()),archive);
                if(!previous.sha256().equals(CryptoSupport.sha256(archive)))throw new ManagementException("归档期间文件发生变化，请重新检查");
            }
            ProjectRules updated = action.equals("REPLACE_MOD") ? migrate(project.rules(), plan) : project.rules();
            Path target = source(project, stage.path());
            Path payload = directory.resolve(stage.payload());
            if (!stage.sha256().equals(CryptoSupport.sha256(payload)))
                throw new ManagementException("暂存文件校验失败，请重新上传");
            receipt = new Receipt(stage.id(), stage.path(), stage.size(), stage.sha256(), stage.createdAt(),
                    project.sourceDirectory().toString(), List.copyOf(before), List.copyOf(affected),
                    project.rules(), updated, false);
            // The durable intention precedes the first source mutation. Recovery redoes
            // this exact operation; a lost response never creates another import.
            new SourceImportRecovery(paths,database,json,checkpoint).commit(projectId,receipt,false);
            }
        } catch (IOException error) {
            throw failure("无法导入文件", error);
        }
        return result(projectId, receipt, refreshPreview);
    }

    private ProjectRules migrate(ProjectRules rules, Plan plan) {
        Match first = plan.replacements().getFirst();
        if (plan.replacements().stream().anyMatch(match -> match.preset() != first.preset()
                || !Objects.equals(match.optionalGroup(), first.optionalGroup())))
            throw new ManagementException("旧模组有不同维护设置，请先整理旧版本再替换");
        Set<String> old = new HashSet<>();
        plan.replacements().forEach(match -> old.add(fold(match.path())));
        List<PresetRule> presets = new ArrayList<>(rules.presets().stream()
                .filter(rule -> rule.directory() || (!old.contains(fold(rule.path()))
                        && !fold(rule.path()).equals(fold(plan.path())))).toList());
        presets.add(new PresetRule(plan.path(), false, first.preset()));
        List<OptionalGroupRule> groups = rules.optionalGroups().stream().map(group -> {
            List<String> members = new ArrayList<>(group.files().stream().filter(path -> !old.contains(fold(path))).toList());
            if (group.files().stream().anyMatch(path -> old.contains(fold(path))) && !members.contains(plan.path()))
                members.add(plan.path());
            return new OptionalGroupRule(group.id(), group.title(), group.description(), group.defaultInstall(),
                    group.modIds(), members, group.directories());
        }).toList();
        ProjectRules updated = rules.withOptionalGroups(groups).withPresets(presets);
        updated.effectivePreset(plan.path(), plan.metadata().componentId());
        return updated;
    }

    public List<ImportView> history(String projectId) {
        database.requireProject(projectId);
        Path projectDirectory = projectDirectory(projectId);
        if (!Files.exists(projectDirectory)) return List.of();
        try (var stream = Files.list(projectDirectory)) {
            List<ImportView> result = new ArrayList<>();
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                if (Files.isSymbolicLink(directory)) continue;
                if (Files.isRegularFile(directory.resolve("receipt.json"))) {
                    Receipt receipt = readReceipt(projectId, directory.getFileName().toString());
                    result.add(new ImportView(receipt.id(), receipt.path(), receipt.size(), receipt.createdAt(),
                            receipt.before().stream().map(Snapshot::path).toList(), receipt.undone(), undoAvailable(projectId,receipt)));
                }
            }
            return result.stream().sorted(Comparator.comparing(ImportView::createdAt).reversed()).limit(40).toList();
        } catch (IOException error) { throw failure("无法读取导入记录", error); }
    }

    public Result undo(String projectId, String id) {
        Receipt receipt = readReceipt(projectId, id);
        String targetPath = receipt.path();
        if (receipt.undone()) return result(projectId, receipt);
        Path directory = directory(projectId, id);
        try (ProjectLock ignored = ProjectLock.acquire(paths.locks().resolve(projectId + ".lock"))) {
            ProjectRecord project = database.requireProject(projectId);
            if (!project.sourceDirectory().toString().equals(receipt.sourceRoot())
                    || !configurationStamp(project.rules()).equals(configurationStamp(receipt.afterRules())))
                throw new ManagementException("目录或维护设置已变化，无法自动恢复；请按记录手动导入归档文件");
            Snapshot current = snapshot(project, receipt.path());
            if (current == null || !current.sha256().equals(receipt.sha256()))
                throw new ManagementException("导入的文件已经被修改，恢复会覆盖后续操作，已停止");
            for (String path : receipt.affected()) if (!path.equals(receipt.path()) && snapshot(project, path) != null)
                throw new ManagementException("旧路径已出现新文件，无法自动恢复：" + path);
            for (Snapshot previous : receipt.before()) {
                Path archive = PathSafety.resolveInside(directory.resolve("before"), previous.path());
                if (!previous.sha256().equals(CryptoSupport.sha256(archive)))
                    throw new ManagementException("归档文件校验失败，已停止恢复");
            }
            new SourceImportRecovery(paths,database,json,checkpoint).commit(projectId,receipt,true);
            receipt = readReceipt(projectId,id);

        } catch (IOException error) { throw failure("无法恢复导入前内容", error); }
        return result(projectId, receipt);
    }

    public ImportView importView(String projectId, String id) {
        database.requireProject(projectId);
        Receipt receipt = readReceipt(projectId, id);
        return new ImportView(receipt.id(), receipt.path(), receipt.size(), receipt.createdAt(),
                receipt.before().stream().map(Snapshot::path).toList(), receipt.undone(),undoAvailable(projectId,receipt));
    }

    public boolean discard(String projectId,String id) {
        Path directory=directory(projectId,id);
        try(ProjectLock project=ProjectLock.acquire(paths.locks().resolve(projectId+".lock"));ProjectLock upload=ProjectLock.acquire(paths.locks().resolve("upload-"+id+".lock"))){
            if(Files.exists(directory.resolve("receipt.json"))||Files.exists(paths.root().resolve("source-transactions").resolve(projectId).resolve(id+".json")))return false;
            AtomicFiles.deleteRecursively(directory);return true;
        }catch(IOException error){throw new ManagementException("暂存清理未完成；请保留原请求并查看存储预览",error);}
    }

    private boolean undoAvailable(String projectId,Receipt receipt){
        Path directory=directory(projectId,receipt.id());
        return !receipt.undone()&&receipt.before().stream().allMatch(before->Files.isRegularFile(directory.resolve("before").resolve(before.path()),LinkOption.NOFOLLOW_LINKS));
    }
    private Result result(String projectId, Receipt receipt) {
        return result(projectId, receipt, true);
    }
    private Result result(String projectId, Receipt receipt, boolean refreshPreview) {
        if (!refreshPreview) return new Result(receipt.id(), receipt.path(), receipt.undone(), "");
        try { checkImports(projectId, List.of(receipt.id())); return new Result(receipt.id(), receipt.path(), receipt.undone(), ""); }
        catch (ManagementException error) { return new Result(receipt.id(), receipt.path(), receipt.undone(), error.getMessage()); }
    }

    /** Replacement is already an explicit removal choice; carry it into the final batch preview. */
    public PublishPreview checkImports(String projectId, List<String> ids) {
        ProjectRecord project = database.requireProject(projectId);
        Set<String> replaced = new HashSet<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            Receipt receipt = readReceipt(projectId, id);
            if (!project.sourceDirectory().toString().equals(receipt.sourceRoot()))
                throw new ManagementException("整合包目录已更换，请单独检查新目录的发布变化");
            if (receipt.undone()) replaced.add(fold(receipt.path()));
            else receipt.affected().stream().filter(path -> !path.equals(receipt.path())).forEach(path -> replaced.add(fold(path)));
        }
        PublishPreview preview = scanner.createPreview(projectId);
        List<RemovalDecision> decisions = preview.changes().stream()
                .filter(change -> change.kind() == ChangeKind.REMOVED && replaced.contains(fold(change.path())))
                .map(change -> new RemovalDecision(change.path(), RemovalAction.DELETE)).toList();
        return decisions.isEmpty() ? preview : scanner.decideRemovals(projectId, decisions);
    }

    private void restoreBefore(ProjectRecord project, Receipt receipt, Path directory, Throwable error) {
        try {
            for (Snapshot previous : receipt.before()) AtomicFiles.copyReplace(
                    PathSafety.resolveInside(directory.resolve("before"), previous.path()), source(project, previous.path()));
            if (receipt.before().stream().noneMatch(previous -> previous.path().equals(receipt.path())))
                Files.deleteIfExists(source(project, receipt.path()));
        } catch (IOException restoreError) { error.addSuppressed(restoreError); }
    }

    private void updateRules(ProjectRecord project, ProjectRules rules) {
        ProjectRecord current = database.requireProject(project.id());
        if (!rules.equals(current.rules())) database.updateProjectWithoutJournal(current.id(),
                current.displayName(), current.sourceDirectory(), current.publicBaseUrl(), current.branding(), rules);
    }
    private String configurationStamp(ProjectRules rules) {
        return CryptoSupport.sha256(json.write(Arrays.asList(rules.presets(), rules.optionalGroups(),
                rules.cleanupDirectories(), rules.rules())));
    }
    private Snapshot snapshot(ProjectRecord project, String path) {
        Path source = source(project, path);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source))
            throw new ManagementException("目标不是安全的普通文件：" + path);
        try { return new Snapshot(path, CryptoSupport.sha256(source), Files.size(source)); }
        catch (IOException error) { throw failure("无法检查源文件", error); }
    }
    private Path source(ProjectRecord project, String path) {
        try { return PathSafety.resolveInside(project.sourceDirectory(), path); }
        catch (IOException error) { throw failure("文件路径无效", error); }
    }
    private void validateTarget(ProjectRecord project, String path) {
        if (new RuleSet(project.rules()).decide(path).excluded())
            throw new ManagementException("该位置不属于整合包管理范围：" + path);
        source(project, path);
    }
    private Path projectDirectory(String projectId) {
        database.requireProject(projectId);
        try { return PathSafety.resolveInside(paths.root().resolve("source-imports"), projectId); }
        catch (IOException error) { throw failure("暂存目录无效", error); }
    }
    private Path directory(String projectId, String id) {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new ManagementException("导入记录不存在");
        try { return PathSafety.resolveInside(projectDirectory(projectId), id); }
        catch (IOException error) { throw failure("暂存目录无效", error); }
    }
    public boolean hasStage(String projectId, String id) {
        Path directory=directory(projectId,id);
        try(ProjectLock project=ProjectLock.acquire(paths.locks().resolve(projectId+".lock"));ProjectLock upload=ProjectLock.acquire(paths.locks().resolve("upload-"+id+".lock"))){
            if(Files.exists(directory.resolve("receipt.json"))||Files.exists(paths.root().resolve("source-transactions").resolve(projectId).resolve(id+".json"))) {
                if(!Files.isRegularFile(directory.resolve("stage.json")))throw new ManagementException("导入元数据损坏，请保留操作目录并恢复记录");
            }
            if(Files.isRegularFile(directory.resolve("stage.json"))&&expired(projectId,id)){
                AtomicFiles.write(PathSafety.resolveInside(paths.root(),"source-import-expired/"+projectId+"/"+id+".json"),json.write(Map.of("id",id,"state","EXPIRED","expiredAt",Instant.now())));
                AtomicFiles.deleteRecursively(directory);return false;
            }
            return Files.isRegularFile(directory.resolve("stage.json"));
        }catch(IOException error){throw new ManagementException("无法确认暂存状态，请保留原请求后重试",error);}
    }
    private boolean expired(String projectId,String id)throws IOException {
        Path dir=directory(projectId,id);
        if(Files.exists(dir.resolve("receipt.json"))||Files.exists(paths.root().resolve("source-transactions").resolve(projectId).resolve(id+".json")))return false;
        Stage stage=stageRecord(projectId,id);
        return stage.createdAt().isBefore(Instant.now().minus(java.time.Duration.ofHours(StorageService.Limits.defaults().expiryHours())));
    }
    private Stage readStage(String projectId, String id) {
        try {Stage stage=stageRecord(projectId,id);if(expired(projectId,id))throw new ManagementException("暂存已过期，请重新选择文件并确认");return stage; }
        catch (IOException error) { throw new ManagementException("暂存文件已失效，请重新上传"); }
    }
    private Stage stageRecord(String projectId,String id)throws IOException {
        Stage stage=json.read(directory(projectId,id).resolve("stage.json"),Stage.class);
        if(!id.equals(stage.id())||stage.path()==null||!PathSafety.normalizeManifestPath(stage.path()).equals(stage.path())||!cn.dreamingfish.updater.protocol.Hex.isSha256(stage.sha256())||stage.createdAt()==null||stage.size()<0||stage.size()>SourceFileService.MAX_UPLOAD_BYTES||!Set.of("payload.jar","payload.bin").contains(stage.payload()))throw new ManagementException("暂存元数据身份或格式损坏，已保留材料，请恢复记录后重试");
        return stage;
    }
    private Receipt readReceipt(String projectId,String id) {
        try {
            byte[] bytes=Files.readAllBytes(directory(projectId,id).resolve("receipt.json"));
            var tree=json.read(bytes,com.fasterxml.jackson.databind.JsonNode.class);
            if(!tree.has("undone")||!tree.get("undone").isBoolean())throw new ManagementException("导入收据缺少明确的最终状态，不能假定成功");
            Receipt receipt=json.read(bytes,Receipt.class);
            if(!id.equals(receipt.id())||receipt.before()==null||receipt.affected()==null||receipt.beforeRules()==null||receipt.afterRules()==null||receipt.sourceRoot()==null||receipt.createdAt()==null||!cn.dreamingfish.updater.protocol.Hex.isSha256(receipt.sha256()))throw new ManagementException("导入收据身份或内容不完整，请保留材料后恢复");
            return receipt;
        }catch(IOException error){throw new ManagementException("导入记录不存在或无法读取；请保留原请求复查",error);}
    }

    static void copyLimited(InputStream input, Path target, long expected, long maximum) throws IOException {
        if (expected > maximum) throw new ManagementException("文件超过允许的大小");
        long copied = 0;
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            for (int length; (length = input.read(buffer)) >= 0;) {
                copied += length;
                if (copied > maximum) throw new ManagementException("文件超过允许的大小");
                output.write(buffer, 0, length);
            }
        }
        if (expected >= 0 && copied != expected) throw new ManagementException("传输未完成，请重试这一项");
    }
    private static String fold(String path) { return ManagedPaths.fold(path); }
    private static ManagementException failure(String message, Throwable error) {
        return error instanceof ManagementException known ? known : new ManagementException(message, error);
    }
    public record Stage(String id, String path, long size, String sha256, ModMetadata metadata, String payload, Instant createdAt) {}
    public record Snapshot(String path, String sha256, long size) {}
    public record Match(String path, String sha256, String version, MaintenancePreset preset, String optionalGroup) {}
    public record Plan(String id, String path, long size, String sha256, ModMetadata metadata,
                       Snapshot existing, ModMetadata existingMetadata, List<Match> replacements, String stamp, boolean committed, boolean undone) {}
    public record Receipt(String id, String path, long size, String sha256, Instant createdAt, String sourceRoot,
                          List<Snapshot> before, List<String> affected, ProjectRules beforeRules, ProjectRules afterRules, boolean undone) {}
    public record ImportView(String id, String path, long size, Instant createdAt, List<String> previousPaths, boolean undone,boolean undoAvailable) {}
    public record Result(String id, String path, boolean undone, String checkError) {}
}
