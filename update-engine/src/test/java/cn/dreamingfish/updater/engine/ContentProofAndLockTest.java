package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentProofAndLockTest {
    @TempDir Path temporary;
    @Test void strictIndexBypassesStoredHashForSameSizeAndMtimeAndCorruptIndex()throws Exception {
        Path file=temporary.resolve("file"),index=temporary.resolve("index.json");Files.writeString(file,"good");
        var fast=LocalFileIndex.load(index);String hash=fast.sha256(file,"file");fast.save();
        var time=Files.getLastModifiedTime(file);Files.writeString(file,"evil");Files.setLastModifiedTime(file,time);
        assertFalse(LocalFileIndex.verified(index).matches(file,"file",hash,4));
        Files.writeString(index,"{");assertFalse(LocalFileIndex.verified(index).matches(file,"file",hash,4));
    }
    @Test void offlineProofRejectsChangedBytesEvenWhenCachedMetadataMatches()throws Exception {
        try(var server=new TestUpdateServer()) {
            Path instance=Files.createDirectories(temporary.resolve("instance")),home=instance.resolve("DreamingFishUpdater");
            var file=server.file("config/required.txt","good",FilePolicy.ENFORCED);
            var release=server.policy(1,"r1").file(file,MaintenancePreset.REQUIRED).build();
            server.bundle(instance,release,false);server.serve(release);
            var request=UpdateRequest.defaults(instance,home,server.binding(),"0.2.0",ProtocolConstants.RELEASE_CAPABILITIES);
            new UpdateEngine().update(request,null);
            Path target=instance.resolve(file.path());var time=Files.getLastModifiedTime(target);
            Files.writeString(target,"evil");Files.setLastModifiedTime(target,time);server.unavailable=true;
            UpdateException rejected=assertThrows(UpdateException.class,()->new UpdateEngine().update(request,null));
            assertTrue(Set.of(UpdateErrorCode.LOCAL_CONTENT_CHANGED,UpdateErrorCode.LOCAL_STATE_INVALID).contains(rejected.code()));
            assertEquals("evil",Files.readString(target));
            server.unavailable=false;new UpdateEngine().update(request,null);assertEquals("good",Files.readString(target));
        }
    }
    @Test void anotherProcessHoldingGameLockBlocksOfflineMutationAndMaintenanceCommit()throws Exception {
        try(var server=new TestUpdateServer()) {
            Path instance=Files.createDirectories(temporary.resolve("locked")),home=instance.resolve("DreamingFishUpdater");
            var good=server.file("config/good.txt","good",FilePolicy.ENFORCED);
            var bad=server.file("config/bad.txt","withdrawn",FilePolicy.ENFORCED);
            var base=server.policy(1,"r1").file(good,MaintenancePreset.SYNC).withdraw("known-bad","test",bad).build();
            var release=new ReleaseManifest(base.schemaVersion(),base.projectId(),base.releaseId(),base.sequence(),base.createdAt(),base.displayVersion(),"0.2.0",base.changelog(),ProtocolConstants.RELEASE_CAPABILITIES,base.forcedSyncDirectories(),base.forcedSyncFiles(),base.releasedPaths(),base.branding(),base.files(),base.cleanupDirectories(),base.optionalGroups(),base.retainedSelfManagedPaths(),base.withdrawals(),base.corrections());
            server.bundle(instance,release,true);Files.write(instance.resolve(bad.path()),bad.bytes());server.unavailable=true;
            Path marker=instance.resolve(".dreamingfish-bootstrap/game.lock");
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),GameLockWorker.class.getName(),marker.toString()).redirectErrorStream(true).start();
            try {
                var output=new BufferedReader(new InputStreamReader(child.getInputStream()));
                assertEquals("LOCKED",java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return output.readLine();}catch(IOException e){throw new UncheckedIOException(e);}}).get(10,java.util.concurrent.TimeUnit.SECONDS));
                Map<String,String> before=snapshot(home);
                var request=UpdateRequest.defaults(instance,home,server.binding(),"0.2.0",ProtocolConstants.RELEASE_CAPABILITIES);
                assertEquals(UpdateErrorCode.GAME_RUNNING,assertThrows(UpdateException.class,()->new UpdateEngine().update(request,null)).code());
                assertEquals("withdrawn",Files.readString(instance.resolve(bad.path())));assertEquals(before,snapshot(home));
            } finally { child.getOutputStream().close();if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))child.destroyForcibly(); }
        }
    }
    private Map<String,String> snapshot(Path root)throws Exception {
        Map<String,String> result=new TreeMap<>();if(!Files.exists(root))return result;
        try(var files=Files.walk(root)){for(Path file:files.filter(Files::isRegularFile).toList())if(!file.getFileName().toString().endsWith(".lock"))result.put(root.relativize(file).toString(),CryptoSupport.sha256(file));}
        return result;
    }
}
