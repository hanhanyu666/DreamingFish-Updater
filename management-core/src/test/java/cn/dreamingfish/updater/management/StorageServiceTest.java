package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageServiceTest {
    @TempDir Path temporary;
    private StorageService storage(ManagementFixture f){return new StorageService(f.paths,f.database,f.json);}
    @Test void concurrencyQuotaAndUnknownLengthCannotOverwriteExistingData()throws Exception {
        var f=new ManagementFixture(temporary);f.createProject();Files.writeString(f.source.resolve("keep.txt"),"keep");
        var small=new StorageService(f.paths,f.database,f.json,new StorageService.Limits(1024,1024,0,1,1));
        try(var lease=small.reserve(512,0)){assertThrows(ManagementException.class,()->small.reserve(1,0));assertThrows(ManagementException.class,()->lease.charge(513));}
        assertThrows(ManagementException.class,()->small.reserve(1025,0));
        String previous=System.getProperty("dfs.storage.stagingBytes");
        try {
            System.setProperty("dfs.storage.stagingBytes","1024");
            var transfers=new SourceTransferService(f.paths,f.database,f.json);
            assertThrows(ManagementException.class,()->transfers.stage("demo","keep.txt",new ByteArrayInputStream(new byte[2048]),-1));
            assertEquals("keep",Files.readString(f.source.resolve("keep.txt")));
        }finally{if(previous==null)System.clearProperty("dfs.storage.stagingBytes");else System.setProperty("dfs.storage.stagingBytes",previous);}
    }
    @Test void successReleasesPayloadButKeepsResultAndUndoUntilExplicitlyForgotten()throws Exception {
        var f=new ManagementFixture(temporary);f.createProject();Files.writeString(f.source.resolve("a.txt"),"old");
        var transfers=new SourceTransferService(f.paths,f.database,f.json);
        var plan=transfers.stage("demo","a.txt",new ByteArrayInputStream("new".getBytes()),3);
        transfers.commit("demo",plan.id(),"OVERWRITE",plan.stamp(),false);
        Path directory=f.paths.root().resolve("source-imports/demo/"+plan.id());
        assertFalse(Files.exists(directory.resolve("payload.bin")));assertTrue(Files.isRegularFile(directory.resolve("receipt.json")));assertEquals("old",Files.readString(directory.resolve("before/a.txt")));
        assertTrue(storage(f).preview(false).candidates().isEmpty());
        var preview=storage(f).preview(true);assertEquals(3,preview.candidates().stream().mapToLong(StorageService.Candidate::bytes).sum());
        var cleaned=storage(f).clean(preview.stamp(),true);assertEquals(3,cleaned.releasedBytes());assertTrue(cleaned.failed().isEmpty());
        assertTrue(transfers.plan("demo",plan.id()).committed());assertFalse(transfers.importView("demo",plan.id()).undoAvailable());
        transfers.commit("demo",plan.id(),"OVERWRITE",plan.stamp(),false);assertEquals("new",Files.readString(f.source.resolve("a.txt")));
        assertThrows(ManagementException.class,()->transfers.undo("demo",plan.id()));
    }
    @Test void expiredStagingHasATombstoneAndRepeatedCleanupIsSafe()throws Exception {
        var f=new ManagementFixture(temporary);f.createProject();var transfers=new SourceTransferService(f.paths,f.database,f.json);
        var plan=transfers.stage("demo","a.txt",new ByteArrayInputStream("new".getBytes()),3);
        Path directory=f.paths.root().resolve("source-imports/demo/"+plan.id());Files.setLastModifiedTime(directory,FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        var preview=storage(f).preview(false);assertEquals(1,preview.candidates().size());
        assertTrue(storage(f).clean(preview.stamp(),false).releasedBytes()>3);assertFalse(transfers.hasStage("demo",plan.id()));
        assertTrue(Files.isRegularFile(f.paths.root().resolve("source-import-expired/demo/"+plan.id()+".json")));
        var empty=storage(f).preview(false);assertEquals(0,storage(f).clean(empty.stamp(),false).releasedBytes());
    }
    @Test void changedCandidateInvalidatesPreviewAndActiveUploadIsNotDeleted()throws Exception {
        var f=new ManagementFixture(temporary);f.createProject();var transfers=new SourceTransferService(f.paths,f.database,f.json);
        var plan=transfers.stage("demo","a.txt",new ByteArrayInputStream("new".getBytes()),3);
        Path directory=f.paths.root().resolve("source-imports/demo/"+plan.id());Files.setLastModifiedTime(directory,FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        var preview=storage(f).preview(false);Files.writeString(directory.resolve("payload.bin"),"changed");
        assertThrows(ManagementException.class,()->storage(f).clean(preview.stamp(),false));assertTrue(Files.exists(directory));
        Files.setLastModifiedTime(directory,FileTime.from(Instant.now().minus(Duration.ofDays(2))));var activePreview=storage(f).preview(false);
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try {
            var owner=executor.submit(()->{try(var lock=ProjectLock.acquire(f.paths.locks().resolve("upload-"+plan.id()+".lock"))){held.countDown();release.await();}return null;});
            assertTrue(held.await(5,TimeUnit.SECONDS));var result=storage(f).clean(activePreview.stamp(),false);assertEquals(0,result.releasedBytes());assertEquals(1,result.failed().size());assertTrue(Files.exists(directory));
            release.countDown();owner.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void expiredDeploymentCacheIsReclaimableOnlyWithoutAnActiveLease()throws Exception {
        var fixture=new ManagementFixture(temporary);fixture.createProject();var storage=storage(fixture);
        Path cache=Files.createDirectories(fixture.paths.temporary().resolve(".dfs-web-deployment-download-test"));Files.writeString(cache.resolve("package.zip"),"temporary");Files.setLastModifiedTime(cache,FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        assertEquals(1,storage.preview(false).candidates().size());
        try(var lease=storage.reserve(1,0)){assertTrue(storage.preview(false).candidates().isEmpty());}
        var plan=storage.preview(false);assertEquals(9,storage.clean(plan.stamp(),false).releasedBytes());assertFalse(Files.exists(cache));
    }

}
