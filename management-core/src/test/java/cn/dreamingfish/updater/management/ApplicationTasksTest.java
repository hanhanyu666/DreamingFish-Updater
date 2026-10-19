package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationTasksTest {
    @TempDir Path temporary;
    private ManagementPaths paths()throws Exception{var paths=ManagementPaths.at(temporary.resolve("data"));paths.initialize();return paths;}
    private String id(){return UUID.randomUUID().toString();}
    @Test void coldQueriesKeepTheRecordCacheBoundedAndPreserveExpiredIdentity()throws Exception {
        var paths=paths();var json=new JsonCodec();var ids=new ArrayList<String>();
        Path directory=Files.createDirectories(paths.root().resolve("application-tasks"));
        for(int i=0;i<300;i++){
            String id=id();ids.add(id);var now=java.time.Instant.now().plusMillis(i);
            var snapshot=new ApplicationTasks.Snapshot(id,"demo","scan",null,"digest-"+i,false,
                    ApplicationTasks.State.SUCCEEDED,now,now,"已完成",1,1,json.read(json.write(Map.of("count",i)),com.fasterxml.jackson.databind.JsonNode.class),"",200);
            Files.write(directory.resolve(id+".json"),json.write(snapshot));
        }
        try(var tasks=new ApplicationTasks(paths,json)){
            for(String id:ids){
                var found=tasks.require(id);assertEquals(id,found.id());
                assertTrue(tasks.cachedRecords()<=256,"cold reads must not repopulate every historical record");
                assertTrue(tasks.cachedBytes()<64L*1024*1024);
            }
            var expired=tasks.require(ids.getFirst());assertEquals(ApplicationTasks.State.EXPIRED,expired.state());
            AtomicInteger reruns=new AtomicInteger();tasks.submit(expired.id(),"demo","scan",null,"digest-0",false,reruns::incrementAndGet);
            assertEquals(0,reruns.get(),"an evicted result must never become a fresh mutation");
        }
    }
    @Test void queueAndWorkersAreBoundedAndOnlyQueuedWorkCanBeCancelled()throws Exception {
        var paths=paths();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger writes=new AtomicInteger();
        try(var tasks=new ApplicationTasks(paths,new JsonCodec(),1,1)){
            String first=id(),second=id();tasks.submit(first,"a","scan",null,"one",false,()->{entered.countDown();release.await();return "first";});assertTrue(entered.await(5,TimeUnit.SECONDS));
            tasks.submit(second,"b","publish",null,"two",false,()->writes.incrementAndGet());
            assertEquals(1,tasks.active());assertEquals(1,tasks.queued());assertThrows(ManagementException.class,()->tasks.submit(id(),"b","publish",null,"three",false,()->"never"));
            assertThrows(ManagementException.class,()->tasks.cancel(first));assertEquals(ApplicationTasks.State.CANCELLED,tasks.cancel(second).state());assertEquals(0,tasks.queued());
            release.countDown();tasks.await(first);assertEquals(0,writes.get());assertFalse(tasks.hasPending());
        }finally{release.countDown();}
    }
    @Test void duplicateIdentityDoesNotRunAgainAndChangedInputIsRejected()throws Exception {
        var paths=paths();AtomicInteger count=new AtomicInteger();String id=id();
        try(var tasks=new ApplicationTasks(paths,new JsonCodec())){
            tasks.submit(id,"demo","publish",null,"same-body",false,()->count.incrementAndGet());tasks.await(id);
            tasks.submit(id,"demo","publish",null,"same-body",false,()->count.incrementAndGet());assertEquals(1,count.get());
            assertThrows(ManagementException.class,()->tasks.submit(id,"demo","publish",null,"different-body",false,()->0));
        }
    }
    @Test void credentialsAndCredentialErrorsAreNotPersisted()throws Exception {
        var paths=paths();var json=new JsonCodec();
        try(var tasks=new ApplicationTasks(paths,json)){
            String first=id();tasks.submit(first,"demo","distribution-s3",null,"body-digest",true,()->Map.of("password","secret-sentinel","accessKeyId","key-sentinel","destination","https://user:uri-sentinel@example.test/path"));tasks.await(first);
            String second=id();tasks.submit(second,"demo","distribution-webdav",null,"another-digest",true,()->{throw new IllegalArgumentException("password error-sentinel");});assertThrows(Exception.class,()->tasks.await(second));
            try(var files=Files.list(paths.root().resolve("application-tasks"))){for(Path file:files.toList()){String text=Files.readString(file);for(String secret:List.of("secret-sentinel","key-sentinel","uri-sentinel","error-sentinel"))assertFalse(text.contains(secret));}}
        }
    }
    @Test void separateProcessRestartMarksUnknownAndDoesNotPublishAgain()throws Exception {
        var paths=paths();String id=id();
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ApplicationTaskCrashWorker.class.getName(),paths.root().toString(),id).redirectErrorStream(true).start();
        assertTrue(child.waitFor(30,TimeUnit.SECONDS));assertEquals(73,child.exitValue(),new String(child.getInputStream().readAllBytes()));
        try(var tasks=new ApplicationTasks(paths,new JsonCodec())){
            assertEquals(ApplicationTasks.State.UNKNOWN,tasks.require(id).state());AtomicInteger reruns=new AtomicInteger();
            tasks.submit(id,"demo","publish",null,"stable",false,()->reruns.incrementAndGet());assertEquals(0,reruns.get());assertEquals("once",Files.readString(paths.root().resolve("executed-once.txt")));
        }
    }
    @Test void queuedTaskReservationPreventsRootReplacement()throws Exception {
        var paths=paths();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var tasks=new ApplicationTasks(paths,new JsonCodec(),1,1)){
            String first=id();tasks.submit(first,"demo","scan",null,"one",false,()->{entered.countDown();release.await();return 1;});assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(ManagementException.class,()->ApplicationTaskLease.acquire(paths.root(),true));
            release.countDown();tasks.await(first);
        }finally{release.countDown();}
        try(var exclusive=ApplicationTaskLease.acquire(paths.root(),true)){assertNotNull(exclusive);}
    }

    @Test void independentProcessQueriesAndDuplicateAdmissionPreserveLiveOwner()throws Exception {
        var paths=paths();var json=new JsonCodec();String id=id();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var owner=new ApplicationTasks(paths,json)){
            owner.submit(id,"demo","scan",null,"same",false,()->{entered.countDown();assertTrue(release.await(45,TimeUnit.SECONDS));return "finished";});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{
                var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ApplicationTaskQueryWorker.class.getName(),paths.root().toString(),id).redirectErrorStream(true).start();
                try{assertTrue(child.waitFor(30,TimeUnit.SECONDS),"observer timed out");assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes()));}
                finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
                assertEquals(ApplicationTasks.State.RUNNING,owner.require(id).state());
                assertEquals(ApplicationTasks.State.RUNNING,json.read(paths.root().resolve("application-tasks/"+id+".json"),ApplicationTasks.Snapshot.class).state());
            }finally{release.countDown();}
            owner.await(id);
        }
    }

    @Test void observerRefreshesForeignCompletionAndReadonlyQueriesNeverRewriteOrphanRecords()throws Exception {
        var paths=paths();var json=new JsonCodec();String id=id();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var owner=new ApplicationTasks(paths,json)){
            owner.submit(id,"demo","scan",null,"same",false,()->{entered.countDown();release.await();return "complete";});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try(var observer=new ApplicationTasks(paths,json)){
                assertEquals(ApplicationTasks.State.RUNNING,observer.require(id).state());
                release.countDown();owner.await(id);
                assertEquals(ApplicationTasks.State.SUCCEEDED,observer.require(id).state());
                assertEquals(ApplicationTasks.State.SUCCEEDED,observer.list().getFirst().state());
            }finally{release.countDown();}
        }
        var now=java.time.Instant.now();String orphan=id();Path file=paths.root().resolve("application-tasks/"+orphan+".json");
        Files.write(file,json.write(new ApplicationTasks.Snapshot(orphan,"demo","scan",null,"same",false,ApplicationTasks.State.RUNNING,now,now,"running",0,1,null,"",202)));
        byte[] before=Files.readAllBytes(file);
        assertEquals(ApplicationTasks.State.UNKNOWN,ApplicationTasks.query(paths,json,orphan).state());
        ApplicationTasks.query(paths,json);assertArrayEquals(before,Files.readAllBytes(file));
        try(var recovered=new ApplicationTasks(paths,json)){assertEquals(ApplicationTasks.State.UNKNOWN,recovered.require(orphan).state());}
        assertEquals(ApplicationTasks.State.UNKNOWN,json.read(file,ApplicationTasks.Snapshot.class).state());
    }
}
