package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Conservative staging budgets and explicit, fingerprint-bound reclamation.
 * Signed releases, objects, receipts and unfinished recovery intentions are never garbage.
 */
public final class StorageService {
    private static final Set<Path> LOCAL_LEASES=ConcurrentHashMap.newKeySet();
    public record Limits(long stagingBytes,long recoverableBytes,long minimumFreeBytes,int concurrent,long expiryHours) {
        public Limits {if(stagingBytes<1||recoverableBytes<0||minimumFreeBytes<0||concurrent<1||concurrent>64||expiryHours<1)throw new IllegalArgumentException("Invalid storage limits");}
        public static Limits defaults(){return new Limits(Long.getLong("dfs.storage.stagingBytes",16L<<30),Long.getLong("dfs.storage.recoverableBytes",64L<<30),Long.getLong("dfs.storage.minimumFreeBytes",512L<<20),Integer.getInteger("dfs.storage.concurrent",4),Long.getLong("dfs.storage.expiryHours",24));}
    }
    public record Category(String kind,long bytes,String protection){}
    public record Candidate(String path,long bytes,String fingerprint,String reason,boolean losesUndo){}
    public record Preview(String stamp,List<Category> categories,List<Candidate> candidates,Limits limits){}
    public record CleanResult(long releasedBytes,List<String> removed,List<String> failed){}
    private record Reservation(long bytes,long archiveBytes,Instant createdAt){}
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final Limits limits;
    public StorageService(ManagementPaths paths){this(paths,null,new JsonCodec());}
    public StorageService(ManagementPaths paths,ManagementDatabase database,JsonCodec json){this(paths,database,json,Limits.defaults());}
    public StorageService(ManagementPaths paths,ManagementDatabase database,JsonCodec json,Limits limits){this.paths=paths;this.database=database;this.json=json;this.limits=limits;}

    /** Charges the whole remaining reservation plus existing disk staging, conservatively.
     * Unknown upload lengths must reserve their bounded maximum before accepting bytes.
     */
    public Lease reserve(long bytes,long archiveBytes) {
        if(bytes<0||archiveBytes<0)throw new IllegalArgumentException("Negative reservation");
        try(ProjectLock ignored=ProjectLock.acquire(paths.locks().resolve("storage-budget.lock"))){
            Path leases=safe("storage-leases");PathSafety.createSafeDirectories(leases);
            long reserved=0,archives=0;int active=0;
            try(var entries=Files.list(leases)){
                for(Path file:entries.filter(p->p.getFileName().toString().endsWith(".json")).toList()){
                    PathSafety.assertSafePathTree(file);String id=file.getFileName().toString().replace(".json","");
                    if(!id.matches("[a-f0-9-]{36}"))throw new ManagementException("存储租约标识损坏，保留并检查："+file);
                    boolean owned=LOCAL_LEASES.contains(file);
                    if(!owned){try(ProjectLock free=ProjectLock.acquire(paths.locks().resolve("storage-"+id+".lock"))){Files.delete(file);continue;}catch(ManagementException busy){owned=true;}}
                    if(owned){var lease=json.read(file,Reservation.class);reserved=Math.addExact(reserved,lease.bytes());archives=Math.addExact(archives,lease.archiveBytes());active++;}
                }
            }
            long staging=stagingBytes(),recoverable=recoverableBytes();
            if(active>=limits.concurrent())throw new ManagementException("并发暂存已达上限，请完成或取消现有上传后重试");
            if(bytes>limits.stagingBytes()-staging-reserved)throw new ManagementException("暂存总配额不足；请查看存储清理预览。已有文件保持完整");
            if(archiveBytes>limits.recoverableBytes()-recoverable-archives)throw new ManagementException("可恢复归档配额不足；请先明确选择需要保留的撤销历史");
            FileStore fileStore=Files.getFileStore(paths.root());
            requireFree(fileStore,Math.addExact(bytes,limits.minimumFreeBytes()));
            String id=UUID.randomUUID().toString();Path file=leases.resolve(id+".json");
            ProjectLock lock=ProjectLock.acquire(paths.locks().resolve("storage-"+id+".lock"));
            try{AtomicFiles.write(file,json.write(new Reservation(bytes,archiveBytes,Instant.now())));LOCAL_LEASES.add(file);return new Lease(file,lock,bytes,fileStore);}
            catch(IOException|RuntimeException error){lock.close();throw error;}
        }catch(IOException error){throw new ManagementException("无法取得存储预算",error);}
    }
    public final class Lease implements AutoCloseable {
        private final Path file;private final ProjectLock lock;private final long maximum;private final FileStore fileStore;private long written;private boolean closed;
        private Lease(Path file,ProjectLock lock,long maximum,FileStore fileStore){this.file=file;this.lock=lock;this.maximum=maximum;this.fileStore=fileStore;}
        long checkpoint(){return written;}
        void beginObject(long bytes){if(bytes<0||written>maximum-bytes)throw new ManagementException("对象暂存超过已预留峰值");requireFree(fileStore,Math.addExact(limits.minimumFreeBytes(),bytes));}
        void rewind(long checkpoint){if(checkpoint<0||checkpoint>written)throw new IllegalArgumentException("Invalid temporary ownership checkpoint");written=checkpoint;}
        public void charge(long bytes){if(bytes<0||written>maximum-bytes)throw new ManagementException("暂存写入超过已预留配额");requireFree(fileStore,limits.minimumFreeBytes()+bytes);written+=bytes;}
        public void copy(InputStream input,Path target,long expected,long maximum)throws IOException {
            if(expected>maximum)throw new ManagementException("文件超过允许的大小");long total=0;
            PathSafety.assertSafePathTree(target);
            try(OutputStream out=Files.newOutputStream(target)){byte[] buffer=new byte[128*1024];for(int n;(n=input.read(buffer))>=0;){if(n==0)continue;total+=n;if(total>maximum)throw new ManagementException("文件超过允许的大小");charge(n);out.write(buffer,0,n);}}
            if(expected>=0&&expected!=total)throw new ManagementException("传输未完成，请复查后重试");AtomicFiles.force(target);
        }
        @Override public void close(){if(closed)return;closed=true;try{Files.deleteIfExists(file);}catch(IOException e){throw new ManagementException("存储租约待清理："+file,e);}finally{LOCAL_LEASES.remove(file);try{lock.close();}catch(IOException e){throw new ManagementException("存储租约关闭失败",e);}}}
    }

    public Preview preview(boolean forgetUndo) {
        List<Category> categories=new ArrayList<>();List<Candidate> candidates=new ArrayList<>();
        try {
            if(database!=null)categories.addAll(objectCategories());
            else categories.add(new Category("objects",treeBytes(paths.objects()),"所有对象保守保留"));
            categories.add(new Category("recoverable",recoverableBytes(),"原文件归档与撤销记录；只有明确选择放弃撤销才回收"));
            categories.add(new Category("staging",stagingBytes(),"活动任务和未完成恢复受保护；未提交暂存默认 24 小时过期"));
            categories.add(new Category("receipts",metadataBytes(),"幂等结果与恢复日志永久保留，不作为缓存清理"));
            categories.add(new Category("tasks",treeBytes(safe("application-tasks")),"详细任务结果保留七天、最多 64 MiB；过期身份不自动重做"));
            if(!activeReservations()&&Files.isDirectory(paths.temporary(),LinkOption.NOFOLLOW_LINKS)){
                try(var files=Files.list(paths.temporary())){for(Path temporary:files.sorted().toList()){
                    String name=temporary.getFileName().toString();
                    if((name.startsWith(".dfs-web-deployment-download-")||name.startsWith("backup-")||name.startsWith("database-")||name.startsWith("object-"))&&Files.getLastModifiedTime(temporary,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(Instant.now().minus(Duration.ofHours(limits.expiryHours()))))addCandidate(candidates,temporary,"已过期且没有活动租约的可重建中间数据",false);
                }}
            }
            for(String kind:List.of("source-imports","player-imports")) {
                Path root=safe(kind);if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))continue;
                for(Path project:children(root))for(Path directory:children(project)){
                    String projectId=project.getFileName().toString(),id=directory.getFileName().toString();
                    if(!id.matches("[a-f0-9-]{36}"))continue;
                    if(Files.exists(safe("source-transactions/"+projectId+"/"+id+".json"))||Files.exists(directory.resolve("publish-intent.json"))&&!Files.exists(directory.resolve("published.json")))continue;
                    if(Files.exists(directory.resolve("receipt.json"))) {
                        addCandidate(candidates,directory.resolve("payload.bin"),"已提交、可重建的导入 payload",false);
                        addCandidate(candidates,directory.resolve("payload.jar"),"已提交、可重建的导入 payload",false);
                        if(forgetUndo)addCandidate(candidates,directory.resolve("before"),"明确放弃此项文件导入的撤销能力",true);
                    } else if(Files.exists(directory.resolve("published.json"))) {
                        addCandidate(candidates,directory.resolve("package.zip"),"玩家程序正式对象与收据已提交",false);
                        addCandidate(candidates,directory.resolve("bundle"),"玩家程序可重建解压副本",false);
                    } else if(Files.getLastModifiedTime(directory).toInstant().isBefore(Instant.now().minus(Duration.ofHours(limits.expiryHours())))) {
                        addCandidate(candidates,directory,"未提交暂存已过期（结果变为明确过期）",false);
                    }
                }
            }
            String stamp=CryptoSupport.sha256(json.write(List.of(candidates,limits,forgetUndo)));
            return new Preview(stamp,List.copyOf(categories),List.copyOf(candidates),limits);
        }catch(IOException e){throw new ManagementException("无法检查存储；未进行清理",e);}
    }
    public CleanResult clean(String stamp,boolean forgetUndo) {
        List<String> removed=new ArrayList<>(),failed=new ArrayList<>();long released=0;
        try(ProjectLock ignored=ProjectLock.acquire(paths.locks().resolve("storage-budget.lock"))){
            Preview plan=preview(forgetUndo);if(!Objects.equals(stamp,plan.stamp()))throw new ManagementException("存储内容或清理选择已变化，请重新预览并确认");
            for(Candidate candidate:plan.candidates()){
                Path file=safe(candidate.path());Path relative=paths.root().relativize(file);
                if(relative.getName(0).toString().equals("temp")){
                    try{if(activeReservations())throw new ManagementException("仍有活动暂存任务");if(!candidate.fingerprint().equals(fingerprint(file)))throw new ManagementException("候选内容已变化");AtomicFiles.deleteRecursively(file);released+=candidate.bytes();removed.add(candidate.path());}catch(IOException|RuntimeException error){failed.add(candidate.path()+"："+error.getMessage());}
                    continue;
                }
                String projectId=relative.getName(1).toString(),id=relative.getName(2).toString();
                try(ProjectLock project=ProjectLock.acquire(paths.locks().resolve(projectId+".lock"));ProjectLock upload=ProjectLock.acquire(paths.locks().resolve("upload-"+id+".lock"));ProjectLock pack=ProjectLock.acquire(paths.locks().resolve("package-"+id+".lock"))){
                    if(Files.exists(safe("source-transactions/"+projectId+"/"+id+".json")))throw new ManagementException("仍有恢复日志");
                    if(!candidate.fingerprint().equals(fingerprint(file)))throw new ManagementException("候选内容已变化");
                    if(relative.getNameCount()==3){
                        String expiredKind=relative.getName(0).toString().equals("source-imports")?"source-import-expired":"player-import-expired";
                        AtomicFiles.write(safe(expiredKind+"/"+projectId+"/"+id+".json"),json.write(Map.of("id",id,"state","EXPIRED","expiredAt",Instant.now())));
                    }
                    AtomicFiles.deleteRecursively(file);released+=candidate.bytes();removed.add(candidate.path());
                }catch(IOException|RuntimeException e){failed.add(candidate.path()+"："+e.getMessage());}
            }
            return new CleanResult(released,List.copyOf(removed),List.copyOf(failed));
        }catch(IOException e){throw new ManagementException("清理锁不可用；未确认的内容已保留",e);}
    }
    private List<Category> objectCategories()throws IOException {
        Set<String> current=new HashSet<>(),retained=new HashSet<>();
        var programs=new PlayerProgramService(paths,database,json);
        for(var project:database.listProjects()){
            addBranding(current,project.branding());
            var latest=database.latestRelease(project.id());
            for(var release:database.listReleases(project.id())){
                var manifest=database.readManifest(release);Set<String> refs=new HashSet<>();
                manifest.files().forEach(file->refs.add(file.sha256()));addBranding(refs,manifest.branding());retained.addAll(refs);
                if(latest.isPresent()&&latest.get().releaseId().equals(release.releaseId()))current.addAll(refs);
            }
            for(String platform:programs.listPlatforms(project.id())){
                var selected=programs.latest(project.id(),platform);
                for(var program:programs.list(project.id(),platform)){
                    var manifest=json.read(program.manifestPath(),PlayerProgramManifest.class);
                    for(var file:manifest.files()){retained.add(file.sha256());if(selected.isPresent()&&selected.get().version().equals(program.version()))current.add(file.sha256());}
                }
            }
        }
        retained.addAll(current);long currentBytes=objectBytes(current);Set<String> history=new HashSet<>(retained);history.removeAll(current);long historyBytes=objectBytes(history);
        return List.of(new Category("currentObjects",currentBytes,"当前发布、所选玩家程序与未发布品牌资源保留"),
                new Category("historyObjects",historyBytes,"全部历史发布引用保留，回滚仍可创建新签名发布"),
                new Category("unreferencedObjects",Math.max(0,treeBytes(paths.objects())-currentBytes-historyBytes),"无已知引用或未知对象也保守保留，本轮清理不删除对象"));
    }
    private static void addBranding(Set<String> refs,Branding branding){
        if(branding.coverObject()!=null)refs.add(branding.coverObject());
        if(branding.musicTracks()!=null)branding.musicTracks().forEach(track->refs.add(track.sha256()));
        if(branding.presentationImages()!=null)branding.presentationImages().forEach(image->refs.add(image.sha256()));
    }
    private long objectBytes(Set<String> refs)throws IOException {long bytes=0;for(String hash:refs){if(!Hex.isSha256(hash))throw new ManagementException("对象引用格式无效，保留存储待核对");Path file=paths.objectPath(hash);PathSafety.assertSafePathTree(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new ManagementException("被引用的发布对象缺失："+hash);bytes=Math.addExact(bytes,Files.size(file));}return bytes;}

    private void addCandidate(List<Candidate> list,Path path,String reason,boolean losesUndo)throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        PathSafety.assertSafePathTree(path);list.add(new Candidate(paths.root().relativize(path).toString().replace('\\','/'),treeBytes(path),fingerprint(path),reason,losesUndo));
    }
    private boolean activeReservations()throws IOException {
        Path leases=safe("storage-leases");if(!Files.isDirectory(leases,LinkOption.NOFOLLOW_LINKS))return false;
        try(var files=Files.list(leases)){for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).toList()){
            PathSafety.assertSafePathTree(file);if(LOCAL_LEASES.contains(file))return true;
            String id=file.getFileName().toString().replace(".json","");if(!id.matches("[a-f0-9-]{36}"))throw new ManagementException("存储租约标识损坏");
            try(ProjectLock ignored=ProjectLock.acquire(paths.locks().resolve("storage-"+id+".lock"))){}catch(ManagementException busy){return true;}
        }}return false;
    }
    private long stagingBytes()throws IOException {
        long bytes=0;
        for(String kind:List.of("source-imports","player-imports")){
            Path root=safe(kind);if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))continue;
            for(Path project:children(root))for(Path directory:children(project)){
                if(!Files.exists(directory.resolve("receipt.json"))&&!Files.exists(directory.resolve("published.json")))bytes+=treeBytes(directory);
                else if(Files.exists(directory.resolve("receipt.json")))bytes+=treeBytes(directory.resolve("payload.bin"))+treeBytes(directory.resolve("payload.jar"));
                else bytes+=treeBytes(directory.resolve("package.zip"))+treeBytes(directory.resolve("bundle"));
            }
        }
        return bytes+treeBytes(paths.temporary());
    }
    private long recoverableBytes()throws IOException {
        long bytes=treeBytes(safe("source-archive"));Path root=safe("source-imports");
        if(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))for(Path project:children(root))for(Path directory:children(project))if(Files.exists(directory.resolve("receipt.json")))bytes+=treeBytes(directory.resolve("before"));
        return bytes;
    }
    private long metadataBytes()throws IOException {
        long bytes=treeBytes(safe("source-transactions"))+treeBytes(safe("source-operations"))+treeBytes(safe("source-import-expired"))+treeBytes(safe("player-import-expired"));
        for(String kind:List.of("source-imports","player-imports")){Path root=safe(kind);if(!Files.exists(root))continue;try(var stream=Files.walk(root)){for(Path file:stream.filter(p->p.getFileName().toString().endsWith(".json")).toList()){PathSafety.assertSafePathTree(file);bytes+=Files.size(file);}}}
        return bytes;
    }
    private List<Path> children(Path root)throws IOException {PathSafety.assertSafePathTree(root);try(var list=Files.list(root)){List<Path> result=list.sorted().toList();for(Path file:result)PathSafety.assertSafePathTree(file);return result.stream().filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList();}}
    static long treeBytes(Path root)throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return 0;PathSafety.assertSafePathTree(root);long total=0;
        try(var files=Files.walk(root)){for(Path file:files.toList()){PathSafety.assertSafePathTree(file);if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))total=Math.addExact(total,Files.size(file));}}return total;
    }
    private String fingerprint(Path root)throws IOException {
        List<String> files=new ArrayList<>();try(var stream=Files.walk(root)){for(Path path:stream.sorted().toList()){PathSafety.assertSafePathTree(path);files.add(root.relativize(path).toString()+":"+(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)?CryptoSupport.sha256(path):"directory"));}}
        return CryptoSupport.sha256(json.write(files));
    }
    static void requireFree(Path path,long bytes){try {Path existing=path;while(!Files.exists(existing))existing=existing.getParent();requireFree(Files.getFileStore(existing),bytes);}catch(IOException e){throw new ManagementException("无法检查磁盘余量",e);}}
    private static void requireFree(FileStore store,long bytes){try{if(store.getUsableSpace()<bytes)throw new ManagementException("磁盘可用空间不足，保留现有内容；需要至少 "+bytes+" 字节余量");}catch(IOException error){throw new ManagementException("无法检查磁盘余量",error);}}
    private Path safe(String relative){try{return PathSafety.resolveInside(paths.root(),relative);}catch(IOException e){throw new ManagementException("存储路径无效",e);}}
}
