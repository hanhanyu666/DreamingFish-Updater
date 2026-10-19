package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PathSafety;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Coordinates source files and SQLite with a durable, idempotent redo intention.
 * A receipt is the commit fact. Unfinished intentions are verified before any redo.
 * Conflicting human edits or missing archives preserve all evidence and block writes.
 */
final class SourceImportRecovery {
    private static final ThreadLocal<Set<String>> ACTIVE = ThreadLocal.withInitial(HashSet::new);
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final Consumer<String> checkpoint;

    SourceImportRecovery(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this(paths, database, json, phase -> {});
    }
    SourceImportRecovery(ManagementPaths paths, ManagementDatabase database, JsonCodec json, Consumer<String> checkpoint) {
        this.paths=paths; this.database=database; this.json=json; this.checkpoint=checkpoint;
    }
    private String key(String project) { return paths.root()+"/"+project; }

    void recoverPending(String projectId) {
        if (ACTIVE.get().contains(key(projectId))) return;
        Path root=safe(paths.root(),"source-transactions/"+projectId);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try(var pending=Files.list(root)){if(pending.noneMatch(p->p.getFileName().toString().endsWith(".json")))return;}
        catch(IOException error){throw new ManagementException("无法检查导入恢复记录："+root,error);}
        ACTIVE.get().add(key(projectId));
        try (ProjectLock ignored=ProjectLock.acquire(paths.locks().resolve(projectId+".lock"));
             var entries=Files.list(root)) {
            PathSafety.assertSafePathTree(root);
            for (Path file:entries.sorted().toList()) {
                PathSafety.assertSafePathTree(file);
                if (!file.getFileName().toString().endsWith(".json")) continue;
                Intent intent=json.read(file,Intent.class);
                if (intent.version()!=1 || intent.undo()==null || intent.receipt()==null || intent.phase()==null || !Set.of("PREPARED","APPLYING","RECOVERY_FAILED").contains(intent.phase()) || !projectId.equals(intent.projectId()) ||
                        !file.equals(intentPath(projectId,intent.receipt().id())))
                    throw new ManagementException("导入恢复记录身份不匹配："+file);
                apply(intent,file);
            }
        } catch (IOException | RuntimeException error) {
            throw new ManagementException("项目 "+projectId+" 的导入恢复尚未完成；已保留 "+root
                    +" 及 source-imports 中的原件。请处理路径 / 归档冲突后重试，恢复前不能发布或写入。原因："+error.getMessage(),error);
        } finally { leave(projectId); }
    }

    void commit(String projectId, SourceTransferService.Receipt receipt, boolean undo) throws IOException {
        ACTIVE.get().add(key(projectId));
        try {
            ApplicationTasks.committing(undo?"正在恢复导入前内容":"正在提交文件导入");
            Intent intent=new Intent(1,projectId,receipt,undo,"PREPARED","");
            Path file=intentPath(projectId,receipt.id());
            AtomicFiles.write(file,json.write(intent));
            checkpoint.accept("PREPARED");
            apply(intent,file);
        } finally { leave(projectId); }
    }
    private void leave(String projectId) { ACTIVE.get().remove(key(projectId)); if(ACTIVE.get().isEmpty())ACTIVE.remove(); }

    private void apply(Intent intent, Path file) throws IOException {
        var receipt=intent.receipt();
        Path stage=safe(paths.root(),"source-imports/"+intent.projectId()+"/"+receipt.id());
        Path receiptFile=safe(stage,"receipt.json");
        if (Files.isRegularFile(receiptFile,LinkOption.NOFOLLOW_LINKS)) {
            var committed=json.read(receiptFile,SourceTransferService.Receipt.class);
            var expected=new SourceTransferService.Receipt(receipt.id(),receipt.path(),receipt.size(),receipt.sha256(),receipt.createdAt(),receipt.sourceRoot(),receipt.before(),receipt.affected(),receipt.beforeRules(),receipt.afterRules(),intent.undo());
            if(committed.undone()==intent.undo()) {
                if(!committed.equals(expected))throw new ManagementException("提交收据与恢复意图不一致，保留双方材料");
                releasePayload(stage);
                Files.deleteIfExists(file); // No redo of a committed fact, including after later user edits.
                return;
            }
        }
        try {
            ProjectRecord project=database.requireProject(intent.projectId());
            if (!project.sourceDirectory().toString().equals(receipt.sourceRoot()) ||
                    (!project.rules().equals(receipt.beforeRules()) && !project.rules().equals(receipt.afterRules())))
                throw new ManagementException("源目录或维护规则后来被修改，已停止自动恢复");
            Map<String,SourceTransferService.Snapshot> before=new HashMap<>();
            for (var snapshot:receipt.before()) {
                before.put(snapshot.path(),snapshot);
                verifyFile(safe(stage,"before/"+snapshot.path()),snapshot.sha256());
            }
            var stageRecord=json.read(safe(stage,"stage.json"),SourceTransferService.Stage.class);
            Path payload=safe(stage,stageRecord.payload());
            if (!stageRecord.id().equals(receipt.id()) || !stageRecord.sha256().equals(receipt.sha256()))
                throw new ManagementException("暂存身份与恢复记录不匹配");
            if (!intent.undo()) verifyFile(payload,receipt.sha256());
            // Preflight every path before touching any file. Each path must still match
            // either side of this exact operation; metadata alone is never sufficient.
            for (String path:receipt.affected()) {
                String old=before.containsKey(path)?before.get(path).sha256():null;
                String next=path.equals(receipt.path())?receipt.sha256():null;
                String actual=fingerprint(source(project,path));
                if (!Objects.equals(actual,old) && !Objects.equals(actual,next))
                    throw new ManagementException("文件后来被修改，保留双方内容："+path);
            }
            AtomicFiles.write(file,json.write(new Intent(1,intent.projectId(),receipt,intent.undo(),"APPLYING","")));
            if (!intent.undo()) {
                replace(payload,source(project,receipt.path()),receipt.id(),receipt.sha256());
                checkpoint.accept("TARGET_WRITTEN");
                for (String path:receipt.affected()) if (!path.equals(receipt.path())) Files.deleteIfExists(source(project,path));
            } else {
                for (var snapshot:receipt.before()) replace(safe(stage,"before/"+snapshot.path()),source(project,snapshot.path()),receipt.id(),snapshot.sha256());
                if (!before.containsKey(receipt.path())) Files.deleteIfExists(source(project,receipt.path()));
            }
            Set<Path> changedDirectories=new HashSet<>();
            for(String affected:receipt.affected()){
                Path directory=source(project,affected).getParent();
                while(directory!=null&&directory.startsWith(project.sourceDirectory())){changedDirectories.add(directory);directory=directory.getParent();}
            }
            for(Path directory:changedDirectories)if(Files.isDirectory(directory))AtomicFiles.syncDirectory(directory);
            checkpoint.accept("PATHS_REMOVED");
            ProjectRules rules=intent.undo()?receipt.beforeRules():receipt.afterRules();
            database.updateProjectWithoutJournal(project.id(),project.displayName(),project.sourceDirectory(),project.publicBaseUrl(),project.branding(),rules);
            checkpoint.accept("RULES_WRITTEN");
            var result=new SourceTransferService.Receipt(receipt.id(),receipt.path(),receipt.size(),receipt.sha256(),receipt.createdAt(),
                    receipt.sourceRoot(),receipt.before(),receipt.affected(),receipt.beforeRules(),receipt.afterRules(),intent.undo());
            checkpoint.accept("BEFORE_RECEIPT");
            AtomicFiles.write(receiptFile,json.write(result));
            checkpoint.accept("RECEIPT_WRITTEN");
            Files.deleteIfExists(file);
            releasePayload(stage);
        } catch (IOException | RuntimeException failure) {
            try { AtomicFiles.write(file,json.write(new Intent(1,intent.projectId(),receipt,intent.undo(),"RECOVERY_FAILED",failure.getMessage()))); }
            catch(IOException suppressed){failure.addSuppressed(suppressed);}
            throw failure;
        }
    }
    private static void releasePayload(Path stage) {
        try {
            for(String name:List.of("payload.jar","payload.bin"))Files.deleteIfExists(safe(stage,name));
            Files.deleteIfExists(safe(stage,"cleanup-pending.json"));
        }catch(IOException|RuntimeException error){
            // A cleanup failure cannot reverse a durable receipt or block subsequent project use.
            try{AtomicFiles.write(safe(stage,"cleanup-pending.json"),new JsonCodec().write(Map.of("reason",String.valueOf(error.getMessage()))));}catch(IOException|RuntimeException ignored){}
        }
    }
    private void replace(Path source,Path target,String id,String hash)throws IOException {
        PathSafety.assertSafePathTree(target);
        PathSafety.createSafeDirectories(target.getParent());
        Path temporary=target.resolveSibling(".dfs-import-"+id+"-"+CryptoSupport.sha256(target.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16)+".tmp");
        PathSafety.assertSafePathTree(temporary);
        // The intent fixes this scratch name and every target was checked above.
        // A crash during copying may leave partial bytes here; rebuild only this
        // transaction's regular scratch from a freshly verified payload/archive.
        String scratchHash = fingerprint(temporary);
        if (!Objects.equals(scratchHash, hash)) {
            if (scratchHash != null) {
                verifyFile(source, hash);
                Files.delete(temporary);
            }
            try (var input = Files.newInputStream(source);
                 var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[128 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    checkpoint.accept("COPY_PROGRESS");
                }
            }
        }
        AtomicFiles.force(temporary);
        verifyFile(temporary,hash);
        AtomicFiles.moveReplace(temporary,target);
        if(target.getFileSystem().supportedFileAttributeViews().contains("posix"))Files.setPosixFilePermissions(target,Files.getPosixFilePermissions(source));
    }
    private static String fingerprint(Path file)throws IOException {
        PathSafety.assertSafePathTree(file);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new ManagementException("恢复目标不是普通文件："+file);
        return CryptoSupport.sha256(file);
    }
    private static void verifyFile(Path file,String hash)throws IOException {
        if(!Objects.equals(hash,fingerprint(file)))throw new ManagementException("恢复文件缺失或内容不符："+file);
    }
    private Path intentPath(String project,String id) {
        if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new ManagementException("恢复标识无效");
        return safe(paths.root(),"source-transactions/"+project+"/"+id+".json");
    }
    private static Path source(ProjectRecord project,String path){return safe(project.sourceDirectory(),PathSafety.normalizeManifestPath(path));}
    private static Path safe(Path root,String relative){try{return PathSafety.resolveInside(root,relative);}catch(IOException error){throw new ManagementException("恢复路径无效",error);}}
    record Intent(int version,String projectId,SourceTransferService.Receipt receipt,Boolean undo,String phase,String error){}
}
