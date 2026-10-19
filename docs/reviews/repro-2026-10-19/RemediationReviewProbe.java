package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Generated fixtures only; exercises current production code without modifying it. */
public final class RemediationReviewProbe {
    public static void main(String[] args)throws Exception {
        if(args.length==3&&args[0].equals("read-task")){
            Path data=Path.of(args[1]).toAbsolutePath().normalize();
            if(!data.startsWith(Path.of("target/review-2026-10-19").toAbsolutePath().normalize()))throw new IllegalArgumentException("fixture boundary");
            try(var reader=new ApplicationTasks(ManagementPaths.at(data),new JsonCodec())){System.out.println("CHILD_READ state="+reader.require(args[2]).state());}
            return;
        }
        Path root=Path.of(args[0]);
        if(!root.isAbsolute()||Files.exists(root))throw new IllegalArgumentException("fresh absolute fixture root required");
        Files.createDirectories(root);
        mismatchedStage(root.resolve("stage-identity"));
        partialCopy(root.resolve("partial-copy"));
        liveTasks(root.resolve("live-tasks"));
        System.out.println("Artifacts: "+root);
    }
    private static void mismatchedStage(Path root)throws Exception {
        var f=new ManagementFixture(root);f.createProject();
        var service=new SourceTransferService(f.paths,f.database,f.json);
        String id=UUID.randomUUID().toString();byte[] bytes="original upload".getBytes();
        var plan=service.stage("demo","config/first.txt",new ByteArrayInputStream(bytes),bytes.length,id);
        boolean rejected=false;
        try{service.stage("demo","config/second.txt",new ByteArrayInputStream(bytes),bytes.length,id);}
        catch(ManagementException expected){rejected=true;}
        System.out.println("STAGE_CONFLICT rejected="+rejected+" originalStageRetained="+service.hasStage("demo",plan.id()));
    }
    private static void partialCopy(Path root)throws Exception {
        var f=new ManagementFixture(root);f.createProject();
        var service=new SourceTransferService(f.paths,f.database,f.json);
        byte[] bytes="complete synthetic payload".getBytes();
        var plan=service.stage("demo","config/target.txt",new ByteArrayInputStream(bytes),bytes.length);
        Path target=f.source.resolve(plan.path());Files.createDirectories(target.getParent());
        String suffix=CryptoSupport.sha256(target.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16);
        Path partial=target.resolveSibling(".dfs-import-"+plan.id()+"-"+suffix+".tmp");
        // The exact transaction-owned scratch state left by an exit during Files.copy.
        Files.writeString(partial,"complete");
        boolean commitFailed=false,scanBlocked=false;
        try{service.commit("demo",plan.id(),"ADD",plan.stamp(),false);}catch(ManagementException expected){commitFailed=true;}
        try{f.scanner.createPreview("demo");}catch(ManagementException expected){scanBlocked=true;}
        System.out.println("PARTIAL_COPY commitFailed="+commitFailed+" restartRecoveryBlocked="+scanBlocked+" scratchRetained="+Files.exists(partial)+" targetExists="+Files.exists(target));
    }
    private static void liveTasks(Path root)throws Exception {
        var f=new ManagementFixture(root);f.createProject();
        String id=UUID.randomUUID().toString();var running=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var owner=new ApplicationTasks(f.paths,f.json)){
            owner.submit(id,"demo","scan",null,"fixture",false,()->{running.countDown();if(!release.await(45,TimeUnit.SECONDS))throw new IllegalStateException("probe deadline");return Map.of("ok",true);});
            if(!running.await(5,TimeUnit.SECONDS))throw new IllegalStateException("worker did not start");
            try {
                var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-cp",System.getProperty("java.class.path"),RemediationReviewProbe.class.getName(),"read-task",f.paths.root().toString(),id).redirectErrorStream(true).start();
                if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);throw new IllegalStateException("reader child deadline: "+new String(child.getInputStream().readAllBytes()));}
                if(child.exitValue()!=0)throw new IllegalStateException(new String(child.getInputStream().readAllBytes()));
                var disk=f.json.read(f.paths.root().resolve("application-tasks/"+id+".json"),ApplicationTasks.Snapshot.class);
                System.out.println("LIVE_TASK owner="+owner.require(id).state()+" disk="+disk.state()+" "+new String(child.getInputStream().readAllBytes()).trim());
            }finally{release.countDown();}
            owner.await(id);
        }
    }
}
