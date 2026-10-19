package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded application jobs. Only public identity and sanitized outcomes are durable;
 * closures and credentials stay in memory. A restart never replays a publication.
 */
public final class ApplicationTasks implements AutoCloseable {
    public enum State {QUEUED,RUNNING,COMMITTING,SUCCEEDED,FAILED,UNKNOWN,CANCELLED,WAITING_FOR_INPUT,EXPIRED}
    public record Snapshot(String id,String projectId,String kind,String sourceRequestId,String requestDigest,boolean needsCredentials,
                           State state,Instant createdAt,Instant updatedAt,String phase,long completed,long total,
                           JsonNode result,String error,int status){}
    private final ManagementPaths paths;private final JsonCodec json;
    private final ThreadPoolExecutor executor;
    private final Map<String,FutureTask<Object>> futures=new ConcurrentHashMap<>();
    private final Map<String,ApplicationTaskLease> leases=new ConcurrentHashMap<>();
    private final Map<String,Long> recordBytes=new HashMap<>();
    private final Map<String,Snapshot> states=new ConcurrentHashMap<>();
    private static final ThreadLocal<Running> CURRENT=new ThreadLocal<>();
    private record Running(ApplicationTasks owner,String id){}

    public ApplicationTasks(ManagementPaths paths,JsonCodec json){this(paths,json,2,8);}
    public ApplicationTasks(ManagementPaths paths,JsonCodec json,int workers,int queue){
        this.paths=paths;this.json=json;
        executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queue),Thread.ofPlatform().daemon().name("dfs-application-task-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        loadInterrupted();
    }
    public synchronized Snapshot submit(String id,String projectId,String kind,String sourceRequestId,String requestDigest,boolean needsCredentials,Callable<?> work){
        path(id);
        if(kind.equals("backup/restore"))throw new ManagementException("替换管理数据根请使用同步的 backup restore 入口，不能排入普通任务队列");
        // Reserve the root before admission. Recovery takes the exclusive side of
        // this same process-shared lease, so it cannot mistake a live owner for a crash.
        final ApplicationTaskLease admissionLease;
        try{admissionLease=ApplicationTaskLease.acquire(paths.root(),false);}
        catch(IOException error){throw new ManagementException("无法保留管理任务的数据根",error);}
        boolean handedOff=false;
        try(ProjectLock admission=ProjectLock.acquire(paths.locks().resolve("task-admission-"+id+".lock"))){
        Snapshot existing=find(id);
        if(existing!=null){if(!Objects.equals(existing.projectId(),projectId)||!existing.kind().equals(kind)||!Objects.equals(existing.sourceRequestId(),sourceRequestId)||!Objects.equals(existing.requestDigest(),requestDigest))throw new ManagementException("任务 ID 已用于其他操作");return existing;}
        leases.put(id,admissionLease);
        Instant now=Instant.now();Snapshot task=new Snapshot(id,projectId,kind,sourceRequestId,requestDigest,needsCredentials,State.QUEUED,now,now,"等待执行",0,1,null,"",202);
        try{save(task);}catch(RuntimeException error){releaseLease(id);throw error;}
        FutureTask<Object> future=new FutureTask<>(()->{
            CURRENT.set(new Running(this,id));
            try{
                transition(id,State.RUNNING,"正在执行",0,1,null,"",202);
                Object result=work.call();JsonNode encoded=json.read(json.write(result),JsonNode.class);
                transition(id,State.SUCCEEDED,"已完成",1,1,sanitize(encoded),"",200);return result;
            }catch(CancellationException cancelled){if(require(id).state()==State.CANCELLED)return null;throw cancelled;
            }catch(Exception error){
                String message=needsCredentials?"外部上传失败，需要重新提供凭据；不会自动重发":Objects.requireNonNullElse(error.getMessage(),error.getClass().getSimpleName());
                State outcome=require(id).state()==State.COMMITTING?State.UNKNOWN:State.FAILED;
                transition(id,outcome,outcome==State.UNKNOWN?"提交结果待核对":"执行失败",0,1,null,message,error instanceof Failure known?known.status():400);throw error;
            }finally{CURRENT.remove();releaseLease(id);}
        }){@Override protected void done(){futures.remove(id);releaseLease(id);}};
        futures.put(id,future);
        try{executor.execute(future);}
        catch(RejectedExecutionException full){transition(id,State.FAILED,"队列已满",0,1,null,"任务队列已满，请稍后重新确认",503);future.cancel(false);futures.remove(id);throw new ManagementException("任务队列已满；未开始执行");}
        handedOff=true;
        return states.get(id);
        }catch(IOException error){throw new ManagementException("无法锁定任务身份，请保留原任务 ID 后复查",error);}
        finally{if(!handedOff)try{admissionLease.close();}catch(IOException error){throw new ManagementException("任务数据根预留释放失败",error);}}
    }
    private void releaseLease(String id){var lease=leases.remove(id);if(lease!=null)try{lease.close();}catch(IOException error){throw new ManagementException("任务数据根预留释放失败",error);}}
    public Object await(String id)throws Exception {
        FutureTask<Object> future=futures.get(id);
        if(future!=null){try{return future.get();}catch(ExecutionException error){if(error.getCause() instanceof Exception known)throw known;throw new ManagementException("任务异常退出",error.getCause());}}
        Snapshot task=require(id);if(task.state()==State.SUCCEEDED)return task.result();
        throw new ManagementException(task.error().isBlank()?"任务结果尚不能确认":task.error());
    }
    public synchronized Snapshot require(String id){Snapshot task=find(id);if(task==null)throw new ManagementException("任务不存在或已过期");return task;}
    public synchronized List<Snapshot> list(){loadInterrupted();return states.values().stream().sorted(Comparator.comparing(Snapshot::createdAt).reversed()).limit(256).map(ApplicationTasks::summary).toList();}
    public synchronized boolean hasPending(){loadInterrupted();return states.values().stream().anyMatch(ApplicationTasks::inProgress);}
    public int queued(){return executor.getQueue().size();}
    public int active(){return executor.getActiveCount();}
    public synchronized int cachedRecords(){return states.size();}
    public synchronized long cachedBytes(){return recordBytes.values().stream().mapToLong(Long::longValue).sum();}
    public synchronized Snapshot cancel(String id){
        Snapshot task=require(id);if(task.state()!=State.QUEUED)throw new ManagementException("只能取消未开始的任务；提交中断开连接不能撤销已完成事实");
        FutureTask<Object> future=futures.get(id);if(future==null)throw new ManagementException("此任务由另一个管理进程执行，请在原管理端取消排队或查询最终结果");
        transition(id,State.CANCELLED,"已取消排队",0,1,null,"",200);
        if(!future.cancel(false))throw new ManagementException("任务已经开始，请查询最终结果");executor.remove(future);return require(id);
    }
    public static final class Failure extends RuntimeException {private final int status;public Failure(int status,String message){super(message);this.status=status;}public int status(){return status;}}
    public synchronized Snapshot resolved(String id,Object result){Snapshot old=require(id);if(old.state()!=State.UNKNOWN&&old.state()!=State.WAITING_FOR_INPUT)throw new ManagementException("只有结果待核对的任务可以按持久收据恢复");transition(id,State.SUCCEEDED,"已按持久收据确认",1,1,sanitize(json.read(json.write(result),JsonNode.class)),"",200);return require(id);}
    public boolean isClosed(){return executor.isShutdown();}
    public static void committing(String phase){Running running=CURRENT.get();if(running!=null)running.owner.transition(running.id,State.COMMITTING,phase,0,1,null,"",202);}
    public static void progress(String phase,long completed,long total){Running running=CURRENT.get();if(running!=null)running.owner.transition(running.id,State.RUNNING,phase,completed,total,null,"",202);}
    private synchronized void transition(String id,State state,String phase,long completed,long total,JsonNode result,String error,int status){
        Snapshot old=require(id);if(old.state()==State.CANCELLED&&state==State.RUNNING)throw new CancellationException("Task cancelled before start");
        save(new Snapshot(id,old.projectId(),old.kind(),old.sourceRequestId(),old.requestDigest(),old.needsCredentials(),state,old.createdAt(),Instant.now(),phase,completed,total,result,error,status));
        trimResults();
    }
    private void trimResults(){
        var ordered=states.values().stream().sorted(Comparator.comparing(Snapshot::createdAt)).toList();
        long detailed=ordered.stream().filter(task->task.result()!=null).mapToLong(task->recordBytes.getOrDefault(task.id(),0L)).sum();
        for(var task:ordered){
            boolean evict=states.size()>256&&!futures.containsKey(task.id());
            if(task.result()!=null&&(detailed>64L*1024*1024||evict||task.updatedAt().isBefore(Instant.now().minus(java.time.Duration.ofDays(7))))){
                detailed-=recordBytes.getOrDefault(task.id(),0L);
                save(new Snapshot(task.id(),task.projectId(),task.kind(),task.sourceRequestId(),task.requestDigest(),task.needsCredentials(),State.EXPIRED,task.createdAt(),Instant.now(),"详细结果已过期；请查询原项目与持久收据",0,1,null,"详细任务结果已过期，原操作不会自动重做",410));
            }
            if(evict){states.remove(task.id());recordBytes.remove(task.id());}
        }
    }
    private Snapshot find(String id){
        Snapshot cached=states.get(id);if(cached!=null&&futures.containsKey(id))return cached;
        Path file=path(id);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        try(var access=InstanceAccess.acquire(paths.root(),false)){
            PathSafety.assertSafePathTree(file);
            long size=Files.size(file);if(size>16L*1024*1024)throw new ManagementException("任务记录超过 16 MiB；保留文件待核对");
            var task=json.read(file,Snapshot.class);
            if(!Objects.equals(task.id(),id))throw new ManagementException("任务记录身份不匹配");
            states.put(id,task);recordBytes.put(id,size);
            if(inProgress(task)){
                // A second service can observe live jobs. Only an exclusive root
                // reservation proves every admitted executor has released its work.
                try(var recovery=ApplicationTaskLease.tryAcquire(paths.root(),true)){
                    if(recovery!=null){task=readRecord(paths,json,id);if(task!=null&&inProgress(task)){task=interrupted(task);save(task);}}
                }
            }
            trimResults(); // Cold GETs must obey the same cache budget as transitions/startup.
            Snapshot retained=states.get(id);
            return retained!=null?retained:json.read(file,Snapshot.class);
        }catch(IOException e){throw new ManagementException("任务记录损坏；保留文件待核对",e);}
    }
    private void save(Snapshot task){try{byte[] bytes=json.write(task);if(bytes.length>16*1024*1024)throw new ManagementException("任务结果超过 16 MiB，请通过原项目查询；任务不会自动重发");AtomicFiles.write(path(task.id()),bytes);states.put(task.id(),task);recordBytes.put(task.id(),(long)bytes.length);}catch(IOException e){throw new ManagementException("无法持久保存任务结果；请按任务 ID 复查实际内容",e);}}
    private Path path(String id){return recordPath(paths,id);}
    private static Path recordPath(ManagementPaths paths,String id){if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new ManagementException("任务 ID 无效");try{return PathSafety.resolveInside(paths.root(),"application-tasks/"+id+".json");}catch(IOException e){throw new ManagementException("任务记录路径无效",e);}}
    private void loadInterrupted(){
        Path directory=paths.root().resolve("application-tasks");
        Set<String> missing=new HashSet<>(states.keySet());
        if(!Files.exists(directory)){forgetMissing(missing);return;}
        try(var recovery=ApplicationTaskLease.tryAcquire(paths.root(),true);
            var access=InstanceAccess.acquire(paths.root(),false);var files=Files.list(directory)){
            var iterator=files.filter(p->p.getFileName().toString().endsWith(".json")).iterator();
            while(iterator.hasNext()){Path file=iterator.next();
                String id=file.getFileName().toString().replaceFirst("[.]json$","");
                Snapshot task=readRecord(paths,json,id);if(task==null)continue;
                missing.remove(id);
                states.put(task.id(),task);recordBytes.put(task.id(),Files.size(file));
                if(recovery!=null&&inProgress(task))save(interrupted(task));
                trimResults();
            }
            forgetMissing(missing);
        }catch(IOException e){throw new ManagementException("无法恢复应用任务记录",e);}
    }
    private void forgetMissing(Set<String> ids){for(String id:ids)if(!futures.containsKey(id)){states.remove(id);recordBytes.remove(id);}}

    private static boolean inProgress(Snapshot task){return Set.of(State.QUEUED,State.RUNNING,State.COMMITTING).contains(task.state());}
    private static Snapshot summary(Snapshot task){return new Snapshot(task.id(),task.projectId(),task.kind(),task.sourceRequestId(),task.requestDigest(),task.needsCredentials(),task.state(),task.createdAt(),task.updatedAt(),task.phase(),task.completed(),task.total(),null,task.error(),task.status());}
    private static Snapshot interrupted(Snapshot task){return new Snapshot(task.id(),task.projectId(),task.kind(),task.sourceRequestId(),task.requestDigest(),task.needsCredentials(),task.needsCredentials()?State.WAITING_FOR_INPUT:State.UNKNOWN,task.createdAt(),task.updatedAt(),"执行者已结束，等待核对",0,1,null,"原执行者已释放任务，但最终结果尚未记录；不会自动重做或再次发布",409);}
    private static Snapshot readRecord(ManagementPaths paths,JsonCodec json,String id)throws IOException {
        Path file=recordPath(paths,id);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        PathSafety.assertSafePathTree(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>16L*1024*1024)throw new ManagementException("任务记录不可用或超过 16 MiB；保留文件待核对");
        Snapshot task=json.read(file,Snapshot.class);
        if(!Objects.equals(task.id(),id)||task.state()==null)throw new ManagementException("任务记录身份或状态无效");
        return task;
    }
    /** Read-only CLI query. Orphan detection changes only the returned view, never the record. */
    public static Snapshot query(ManagementPaths paths,JsonCodec json,String id){
        recordPath(paths,id);
        if(!Files.isDirectory(paths.root(),LinkOption.NOFOLLOW_LINKS))throw new ManagementException("管理数据目录不存在");
        try(var recovery=ApplicationTaskLease.tryAcquire(paths.root(),true);var access=InstanceAccess.acquire(paths.root(),false)){
            Snapshot task=readRecord(paths,json,id);if(task==null)throw new ManagementException("任务不存在或已过期");
            return recovery!=null&&inProgress(task)?interrupted(task):task;
        }catch(IOException error){throw new ManagementException("无法查询任务记录",error);}
    }
    public static List<Snapshot> query(ManagementPaths paths,JsonCodec json){
        Path directory=paths.root().resolve("application-tasks");if(!Files.exists(directory))return List.of();
        try(var recovery=ApplicationTaskLease.tryAcquire(paths.root(),true);var access=InstanceAccess.acquire(paths.root(),false);var files=Files.list(directory)){
            var recent=new PriorityQueue<Snapshot>(Comparator.comparing(Snapshot::createdAt));
            var iterator=files.filter(p->p.getFileName().toString().endsWith(".json")).iterator();
            while(iterator.hasNext()){
                String id=iterator.next().getFileName().toString().replaceFirst("[.]json$","");
                Snapshot task=readRecord(paths,json,id);if(task==null)continue;
                recent.add(summary(recovery!=null&&inProgress(task)?interrupted(task):task));if(recent.size()>256)recent.poll();
            }
            return recent.stream().sorted(Comparator.comparing(Snapshot::createdAt).reversed()).toList();
        }catch(IOException error){throw new ManagementException("无法查询任务列表",error);}
    }
    private JsonNode sanitize(JsonNode input){
        if(input==null)return null;
        if(input.isObject()){ObjectNode result=JsonNodeFactory.instance.objectNode();input.fields().forEachRemaining(entry->{String key=entry.getKey().toLowerCase(Locale.ROOT);result.set(entry.getKey(),key.contains("password")||key.contains("secret")||key.contains("token")||key.contains("privatekey")||key.contains("accesskey")?TextNode.valueOf("[redacted]"):sanitize(entry.getValue()));});return result;}
        if(input.isArray()){ArrayNode result=JsonNodeFactory.instance.arrayNode();input.forEach(value->result.add(sanitize(value)));return result;}
        if(input.isTextual())return TextNode.valueOf(input.asText().replaceAll("(https?://)[^/@\\s]+:[^/@\\s]+@","$1[redacted]@"));
        return input;
    }
    @Override public void close(){
        executor.shutdown();
        try{if(!executor.awaitTermination(10,TimeUnit.SECONDS))synchronized(this){
            for(String id:new ArrayList<>(futures.keySet())){
                Snapshot task=states.get(id);if(task!=null&&task.state()==State.QUEUED)cancel(id);
            }
        }}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
