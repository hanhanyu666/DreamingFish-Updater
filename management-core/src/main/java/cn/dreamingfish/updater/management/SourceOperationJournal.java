package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PathSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Private receipts for regular source edits, reusing their already verified archives. */
public final class SourceOperationJournal {
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;

    public SourceOperationJournal(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths=paths; this.database=database; this.json=json;
    }

    public Before archived(String path, Path archive, boolean executable) {
        try {
            return new Before(PathSafety.normalizeManifestPath(path), CryptoSupport.sha256(archive), Files.size(archive),
                    paths.root().relativize(archive.toAbsolutePath().normalize()).toString().replace('\\','/'), executable);
        } catch (IOException error) { throw new ManagementException("无法读取原文件归档",error); }
    }

    public void record(ProjectRecord beforeProject, String kind, List<Before> before, List<String> affected, String directory) {
        ProjectRecord current=database.requireProject(beforeProject.id());
        List<After> after=new ArrayList<>();
        try {
            for(String path:affected) {
                Path file=source(current,path);
                boolean exists=Files.exists(file,LinkOption.NOFOLLOW_LINKS);
                if(exists&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new ManagementException("文件状态发生变化："+path);
                after.add(new After(path,exists?CryptoSupport.sha256(file):null));
            }
            Receipt entry=new Receipt(UUID.randomUUID().toString(),Instant.now(),kind,current.sourceDirectory().toString(),
                    List.copyOf(before),List.copyOf(after),directory,beforeProject.rules(),current.rules(),false);
            AtomicFiles.write(receiptPath(current.id(),entry.id()),json.write(entry));
        } catch(IOException error){throw new ManagementException("无法保存文件操作记录",error);}
    }

    public List<Receipt> history(String projectId) {
        database.requireProject(projectId); Path directory=root(projectId);
        if(!Files.exists(directory))return List.of();
        try(var files=Files.list(directory)){
            List<Receipt> result=new ArrayList<>();
            for(Path path:files.filter(p->p.getFileName().toString().endsWith(".json")).toList()) {
                if(Files.isSymbolicLink(path))throw new ManagementException("操作记录路径无效");
                result.add(json.read(Files.readAllBytes(path),Receipt.class));
            }
            result.sort(Comparator.comparing(Receipt::createdAt).reversed());
            return List.copyOf(result.stream().limit(200).toList());
        }catch(IOException error){throw new ManagementException("无法读取文件操作记录",error);}
    }

    public Receipt receipt(String projectId,String id) {
        database.requireProject(projectId);
        try{return json.read(Files.readAllBytes(receiptPath(projectId,id)),Receipt.class);}
        catch(IOException error){throw new ManagementException("文件操作记录不存在",error);}
    }

    public Plan previewUndo(String projectId,String id) {
        Receipt entry=receipt(projectId,id); ProjectRecord project=database.requireProject(projectId);
        if(!entry.undone())verifyCurrent(project,entry);
        String stamp=CryptoSupport.sha256(json.write(List.of(entry,ProjectSettingsSnapshot.of(project),project.nextSequence())));
        return new Plan(entry,stamp);
    }

    public String unavailableReason(ProjectRecord project,Receipt entry) {
        try { verifyCurrent(project,entry,false); return ""; }
        catch(ManagementException conflict){return conflict.getMessage();}
    }

    public void undo(String projectId,String id,String stamp) {
        try(ProjectLock ignored=ProjectLock.acquire(PathSafety.resolveInside(paths.locks(),projectId+".lock"))){
            Plan plan=previewUndo(projectId,id); Receipt entry=plan.receipt();
            if(entry.undone())return;
            if(!Objects.equals(stamp,plan.stamp()))throw new ManagementException("文件或设置已改变，请重新查看撤销预览");
            ProjectRecord project=database.requireProject(projectId);
            if(entry.directory()!=null){
                Path directory=source(project,entry.directory());
                Files.delete(directory);
                try { saveUndone(projectId,entry); }
                catch(IOException|RuntimeException failure){
                    try{Files.createDirectory(directory);}catch(IOException restore){failure.addSuppressed(restore);}
                    throw failure;
                }
            }else{
                Path rollback=PathSafety.resolveInside(root(projectId),"undo-"+id);
                for(After after:entry.after())if(after.sha256()!=null){
                    Path copy=PathSafety.resolveInside(rollback,after.path());
                    AtomicFiles.copyReplace(source(project,after.path()),copy);
                    if(!after.sha256().equals(CryptoSupport.sha256(copy)))throw new ManagementException("文件在准备恢复时发生变化");
                }
                List<String> changed=new ArrayList<>();
                boolean rulesChanged=false;
                try{
                    verifyCurrent(project,entry);
                    for(After after:entry.after()){
                        Before before=entry.before().stream().filter(b->b.path().equals(after.path())).findFirst().orElse(null);
                        Path target=source(project,after.path());
                        if(before==null){Files.delete(target);changed.add(after.path());}
                        else{
                            Files.createDirectories(target.getParent());
                            Path backup=archive(before);
                            if(after.sha256()==null)Files.copy(backup,target); else AtomicFiles.copyReplace(backup,target);
                            if(before.executable()&&target.getFileSystem().supportedFileAttributeViews().contains("posix"))target.toFile().setExecutable(true,false);
                            changed.add(after.path());
                        }
                    }
                    ProjectRecord latest=database.requireProject(projectId);
                    if(!latest.sourceDirectory().equals(project.sourceDirectory())||!sameFileRules(latest.rules(),entry.afterRules())){
                        throw new ManagementException("目录或维护设置已改变，请重新查看操作记录");
                    }
                    ProjectRules restored=latest.rules().withPresets(entry.beforeRules().presets())
                            .withCleanupDirectories(entry.beforeRules().cleanupDirectories()).withOptionalGroups(entry.beforeRules().optionalGroups());
                    database.updateProjectWithoutJournal(latest.id(),latest.displayName(),latest.sourceDirectory(),latest.publicBaseUrl(),latest.branding(),restored);
                    rulesChanged=true;
                    saveUndone(projectId,entry);
                }catch(IOException|RuntimeException error){
                    for(String path:changed){
                        After after=entry.after().stream().filter(a->a.path().equals(path)).findFirst().orElseThrow();
                        try{
                            if(after.sha256()==null)Files.deleteIfExists(source(project,path));
                            else AtomicFiles.copyReplace(PathSafety.resolveInside(rollback,path),source(project,path));
                        }catch(IOException restore){error.addSuppressed(restore);}
                    }
                    if(rulesChanged){
                        try{database.updateProjectWithoutJournal(project.id(),project.displayName(),project.sourceDirectory(),project.publicBaseUrl(),project.branding(),project.rules());}
                        catch(RuntimeException restore){error.addSuppressed(restore);}
                    }
                    throw error;
                }
            }
        }catch(IOException error){throw new ManagementException("无法恢复文件操作",error);}
    }

    private void saveUndone(String projectId,Receipt entry)throws IOException{
        Receipt undone=new Receipt(entry.id(),entry.createdAt(),entry.kind(),entry.sourceRoot(),entry.before(),entry.after(),
                entry.directory(),entry.beforeRules(),entry.afterRules(),true);
        AtomicFiles.write(receiptPath(projectId,entry.id()),json.write(undone));
    }

    private void verifyCurrent(ProjectRecord project,Receipt entry)throws ManagementException{
        verifyCurrent(project,entry,true);
    }

    private void verifyCurrent(ProjectRecord project,Receipt entry,boolean contentHashes)throws ManagementException{
        if(!entry.sourceRoot().equals(project.sourceDirectory().toString())||!sameFileRules(project.rules(),entry.afterRules())){
            throw new ManagementException("目录或维护设置后来有修改，不能直接撤销这次文件操作");
        }
        try{
            if(entry.directory()!=null){
                Path folder=source(project,entry.directory());
                if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new ManagementException("这个文件夹已不存在");
                try(var contents=Files.list(folder)){if(contents.findAny().isPresent())throw new ManagementException("文件夹已有内容，不能撤销创建");}
            }
            for(After after:entry.after()){
                Path current=source(project,after.path());
                boolean exists=Files.exists(current,LinkOption.NOFOLLOW_LINKS);
                if(after.sha256()==null?exists:!exists||!Files.isRegularFile(current,LinkOption.NOFOLLOW_LINKS)||contentHashes&&!after.sha256().equals(CryptoSupport.sha256(current))){
                    throw new ManagementException("“"+after.path()+"”后来已被修改，不能直接撤销");
                }
            }
            for(Before before:entry.before()){
                Path backup=archive(before);
                if(!Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS)||Files.size(backup)!=before.size()||contentHashes&&!before.sha256().equals(CryptoSupport.sha256(backup))){
                    throw new ManagementException("原文件归档校验失败："+before.path());
                }
            }
        }catch(IOException error){throw new ManagementException("无法核对文件操作记录",error);}
    }

    private static boolean sameFileRules(ProjectRules a,ProjectRules b){return a.presets().equals(b.presets())&&a.cleanupDirectories().equals(b.cleanupDirectories())&&a.optionalGroups().equals(b.optionalGroups())&&a.rules().equals(b.rules());}
    private Path archive(Before before){
        if(before.archive()==null||!before.archive().startsWith("source-archive/"))throw new ManagementException("原文件归档路径无效");
        return safe(paths.root(),before.archive());
    }
    private static Path source(ProjectRecord project,String path){return safe(project.sourceDirectory(),PathSafety.normalizeManifestPath(path));}
    private Path root(String projectId){return safe(paths.root(),"source-operations/"+projectId);}
    private Path receiptPath(String projectId,String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw new ManagementException("操作记录标识无效");return safe(root(projectId),id+".json");}
    private static Path safe(Path root,String path){try{return PathSafety.resolveInside(root,path);}catch(IOException error){throw new ManagementException("操作记录路径无法访问",error);}}
    public record Before(String path,String sha256,long size,String archive,boolean executable){}
    public record After(String path,String sha256){}
    public record Receipt(String id,Instant createdAt,String kind,String sourceRoot,List<Before> before,List<After> after,
                          String directory,ProjectRules beforeRules,ProjectRules afterRules,boolean undone){}
    public record Plan(Receipt receipt,String stamp){}
}
