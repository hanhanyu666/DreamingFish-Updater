package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPolicyEngineTest {
    @TempDir Path temporary;
    @Test void pauseAndChangedApprovalNeverActivateTheDownloadedCandidate() throws Exception {
        try(var server=new TestUpdateServer()) {
            Path instance=Files.createDirectories(temporary.resolve("instance")), home=instance.resolve("DreamingFishUpdater");
            Files.createDirectories(home.resolve("app/0.3.2")); Files.writeString(home.resolve("app/0.3.2/player.cmd"),"old");
            ActivePlayerState.activate(home,new ActivePlayerState("0.3.2","app/0.3.2/player.cmd","app/0.3.2",List.of(),90),null);
            var program=server.playerProgram("0.3.3","player.cmd",server.playerFile("player.cmd","new",true));
            server.servePlayerProgram(program);
            String hash=CryptoSupport.sha256(new JsonCodec().write(program));
            var targets=Map.of("windows-x64",new PlayerUpdatePolicy.Target("0.3.3",hash));
            var paused=new PlayerUpdatePolicy(1,"demo",1,false,targets,Instant.now()); server.servePolicy(paused);
            var request=UpdateRequest.defaults(instance,home,server.binding(),"0.3.2",ProtocolConstants.RELEASE_CAPABILITIES);
            var updater=new PlayerProgramUpdater();
            assertEquals(PlayerProgramUpdateOutcome.PAUSED,updater.checkAndInstall(request,"0.1.2","windows-x64",null).outcome());
            assertEquals("0.3.2",ActivePlayerState.load(home).orElseThrow().version());
            var approved=new PlayerUpdatePolicy(1,"demo",2,true,targets,Instant.now()); server.servePolicy(approved);
            var duringDownload=new PlayerUpdatePolicy(1,"demo",3,false,targets,Instant.now());
            var outcome=updater.checkAndInstall(request,"0.1.2","windows-x64",event->{if(event.stage()==UpdateStage.DOWNLOADING)server.servePolicy(duringDownload);});
            assertEquals(PlayerProgramUpdateOutcome.PAUSED,outcome.outcome());
            assertEquals("0.3.2",ActivePlayerState.load(home).orElseThrow().version());
            server.servePolicy(approved);
            assertEquals(UpdateErrorCode.REPLAY_DETECTED,assertThrows(UpdateException.class,()->updater.checkAndInstall(request,"0.1.2","windows-x64",null)).code());
            server.playerPolicyBytes=null;
            assertEquals(PlayerProgramUpdateOutcome.CHECK_UNAVAILABLE,updater.checkAndInstall(request,"0.1.2","windows-x64",null).outcome());
            assertEquals("0.3.2",ActivePlayerState.load(home).orElseThrow().version());
        }
    }
}
