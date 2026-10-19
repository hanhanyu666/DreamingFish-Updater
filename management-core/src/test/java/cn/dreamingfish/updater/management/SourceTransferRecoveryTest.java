package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceTransferRecoveryTest {
    @TempDir Path temporary;
    private static final List<String> PHASES=List.of("PREPARED","TARGET_WRITTEN","PATHS_REMOVED","RULES_WRITTEN","BEFORE_RECEIPT","RECEIPT_WRITTEN");
    private SourceTransferService service(ManagementFixture f){return new SourceTransferService(f.paths,f.database,f.json);}
    private SourceTransferService.Plan setup(ManagementFixture f)throws Exception {
        var project=f.createProject();
        Files.createDirectories(f.source.resolve("mods"));
        Files.write(f.source.resolve("mods/test-1.jar"),SourceTransferServiceTest.mod("test","1"));
        f.database.updateProject("demo",project.displayName(),project.sourceDirectory(),project.publicBaseUrl(),project.branding(),project.rules().withPresets(List.of(new PresetRule("mods/test-1.jar",false,cn.dreamingfish.updater.protocol.MaintenancePreset.REQUIRED))));
        byte[] bytes=SourceTransferServiceTest.mod("test","2");
        return service(f).stage("demo","mods/test-2.jar",new ByteArrayInputStream(bytes),bytes.length);
    }
    private void crash(ManagementFixture f,String id,String direction,String phase)throws Exception {
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),SourceTransferCrashWorker.class.getName(),f.paths.root().toString(),id,direction,phase)
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"child did not exit");
        assertEquals(73,process.exitValue(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void separateProcessExitAtEveryCommitBoundaryRecoversOneResult()throws Exception {
        for(String phase:PHASES){
            var f=new ManagementFixture(temporary.resolve(phase));var plan=setup(f);
            crash(f,plan.id(),"commit",phase);
            var recovered=service(f).plan("demo",plan.id());assertTrue(recovered.committed(),phase);
            assertFalse(Files.exists(f.source.resolve("mods/test-1.jar")));assertTrue(Files.exists(f.source.resolve("mods/test-2.jar")));
            assertEquals(cn.dreamingfish.updater.protocol.MaintenancePreset.REQUIRED,f.database.requireProject("demo").rules().presetFor("mods/test-2.jar"));
            service(f).commit("demo",plan.id(),"REPLACE_MOD",plan.stamp(),false);
            assertEquals(1,service(f).history("demo").size());
        }
    }
    @Test void recoveryCanExitAgainAndResumeWithoutDuplicatingTheImport()throws Exception {
        var f=new ManagementFixture(temporary);var plan=setup(f);crash(f,plan.id(),"commit","TARGET_WRITTEN");
        crash(f,plan.id(),"recover","RULES_WRITTEN");
        assertTrue(service(f).plan("demo",plan.id()).committed());assertEquals(1,service(f).history("demo").size());
    }
    @Test void manualChangesAndMissingArchivesBlockPublishingAndPreserveEvidence()throws Exception {
        for(boolean missing:List.of(false,true)){
            var f=new ManagementFixture(temporary.resolve("conflict-"+missing));var plan=setup(f);crash(f,plan.id(),"commit","TARGET_WRITTEN");
            Path stage=f.paths.root().resolve("source-imports/demo/"+plan.id());
            if(missing)Files.delete(stage.resolve("before/mods/test-1.jar"));else Files.writeString(f.source.resolve("mods/test-2.jar"),"human edit");
            assertThrows(ManagementException.class,()->f.scanner.createPreview("demo"));
            assertTrue(Files.isRegularFile(f.paths.root().resolve("source-transactions/demo/"+plan.id()+".json")));
            assertTrue(Files.exists(stage.resolve("payload.jar")));
            if(!missing)assertEquals("human edit",Files.readString(f.source.resolve("mods/test-2.jar")));
        }
    }
    @Test void interruptedUndoAlsoRecoversItsFinalFact()throws Exception {
        for(String phase:List.of("PREPARED","PATHS_REMOVED","RULES_WRITTEN","RECEIPT_WRITTEN")){
            var f=new ManagementFixture(temporary.resolve("undo-"+phase));var plan=setup(f);service(f).commit("demo",plan.id(),"REPLACE_MOD",plan.stamp(),false);
            crash(f,plan.id(),"undo",phase);
            assertTrue(service(f).plan("demo",plan.id()).undone());assertTrue(Files.exists(f.source.resolve("mods/test-1.jar")));assertFalse(Files.exists(f.source.resolve("mods/test-2.jar")));
        }
    }
    @Test void stableRequestIdentityReturnsTheExistingStageWithoutReadingTheBody()throws Exception {
        var f=new ManagementFixture(temporary);f.createProject();String id=UUID.randomUUID().toString();
        var first=service(f).stage("demo","a.txt",new ByteArrayInputStream(new byte[]{1}),1,id);
        var again=service(f).stage("demo","a.txt",new InputStream(){public int read(){throw new AssertionError("must not upload twice");}},1,id);
        assertEquals(first.id(),again.id());assertEquals(first.sha256(),again.sha256());
    }

    @Test void conflictingRetryPreservesTheOriginalStageAndPayload()throws Exception {
        var f=new ManagementFixture(temporary);f.createProject();var transfers=service(f);
        String id=UUID.randomUUID().toString();byte[] bytes="original upload".getBytes();
        var plan=transfers.stage("demo","config/first.txt",new ByteArrayInputStream(bytes),bytes.length,id);
        Path directory=f.paths.root().resolve("source-imports/demo/"+id);
        byte[] stage=Files.readAllBytes(directory.resolve("stage.json"));
        assertThrows(ManagementException.class,()->transfers.stage("demo","config/second.txt",new InputStream(){public int read(){throw new AssertionError("conflict must not read the new body");}},bytes.length,id));
        assertArrayEquals(stage,Files.readAllBytes(directory.resolve("stage.json")));
        assertArrayEquals(bytes,Files.readAllBytes(directory.resolve("payload.bin")));
        assertEquals(plan,transfers.plan("demo",id));
        transfers.commit("demo",id,"ADD",plan.stamp(),false);
        assertArrayEquals(bytes,Files.readAllBytes(f.source.resolve("config/first.txt")));
    }

    @Test void failedNewUploadOnlyCleansItsOwnIncompletePayload()throws Exception {
        var f=new ManagementFixture(temporary);f.createProject();var transfers=service(f);
        String id=UUID.randomUUID().toString();
        assertThrows(ManagementException.class,()->transfers.stage("demo","config/a.txt",new ByteArrayInputStream(new byte[]{1}),2,id));
        assertFalse(Files.exists(f.paths.root().resolve("source-imports/demo/"+id)));
        assertNotNull(transfers.stage("demo","config/a.txt",new ByteArrayInputStream(new byte[]{1,2}),2,id));
    }

    @Test void separateProcessExitInsideCopyRebuildsScratchForCommitAndUndo()throws Exception {
        for(boolean undo:List.of(false,true)){
            var f=new ManagementFixture(temporary.resolve("partial-"+undo));f.createProject();
            byte[] before=new byte[512*1024],after=new byte[512*1024];
            Arrays.fill(before,(byte)1);Arrays.fill(after,(byte)2);
            Path target=f.source.resolve("config/large.bin");Files.createDirectories(target.getParent());Files.write(target,before);
            var plan=service(f).stage("demo","config/large.bin",new ByteArrayInputStream(after),after.length);
            if(undo)service(f).commit("demo",plan.id(),"OVERWRITE",plan.stamp(),false);
            crash(f,plan.id(),undo?"undo":"commit","COPY_PROGRESS");
            Path scratch=scratch(target,plan.id());
            assertTrue(Files.size(scratch)>0&&Files.size(scratch)<before.length,"must interrupt inside the copy, not after it");
            assertArrayEquals(undo?after:before,Files.readAllBytes(target));
            // Recovery itself can also stop halfway through rebuilding the scratch.
            crash(f,plan.id(),"recover","COPY_PROGRESS");
            var recovered=service(f).plan("demo",plan.id());
            assertEquals(undo,recovered.undone());assertEquals(!undo,recovered.committed());
            assertArrayEquals(undo?before:after,Files.readAllBytes(target));assertFalse(Files.exists(scratch));
        }
    }

    @Test void partialScratchDoesNotPermitOverwritingLaterHumanChanges()throws Exception {
        var f=new ManagementFixture(temporary);var plan=setup(f);
        crash(f,plan.id(),"commit","PREPARED");
        Path target=f.source.resolve(plan.path()),scratch=scratch(target,plan.id());
        Files.writeString(scratch,"partial");Files.writeString(target,"human edit");
        assertThrows(ManagementException.class,()->service(f).plan("demo",plan.id()));
        assertEquals("human edit",Files.readString(target));assertEquals("partial",Files.readString(scratch));
    }

    private static Path scratch(Path target,String id){
        String suffix=cn.dreamingfish.updater.protocol.CryptoSupport.sha256(target.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,16);
        return target.resolveSibling(".dfs-import-"+id+"-"+suffix+".tmp");
    }
}
