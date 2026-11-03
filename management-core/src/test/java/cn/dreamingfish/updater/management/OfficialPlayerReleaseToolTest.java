package cn.dreamingfish.updater.management;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfficialPlayerReleaseToolTest {
    @TempDir Path temporary;
    @Test void signedReleaseMustKeepItsKeyArchiveVersionAndDownloadTag() throws Exception {
        Path secret=temporary.resolve("signing/private.pk8"), publicKey=temporary.resolve("public.txt");
        OfficialPlayerReleaseTool.main(new String[]{"init-key",secret.toString(),publicKey.toString()});
        assertThrows(IllegalArgumentException.class,()->OfficialPlayerReleaseTool.main(new String[]{"init-key",secret.toString(),publicKey.toString()}));
        Path bundle=temporary.resolve("bundle"), root=Files.createDirectories(bundle.resolve("DreamingFishUpdater/app/0.3.3"));
        Files.createDirectories(bundle.resolve("DreamingFishUpdater/state"));
        Files.writeString(bundle.resolve("DreamingFishUpdater/state/active-player.properties"),"schema=1\nversion=0.3.3\n");
        Files.writeString(root.resolve("DreamingFishUpdater.exe"),"synthetic executable");
        Path notes=temporary.resolve("notes.md"), archive=temporary.resolve("player.zip");
        Files.writeString(notes,"Synthetic release test"); Files.writeString(archive,"synthetic archive");
        String prefix="https://github.com/example/project/releases/download/v0.3.2/";
        OfficialPlayerReleaseTool.main(new String[]{"sign",secret.toString(),publicKey.toString(),bundle.toString(),archive.toString(),prefix+"player.zip",notes.toString(),"0.1.2","0.3.2"});
        Path catalog=temporary.resolve("player-catalog.signed");
        String[] verify={"verify",catalog.toString(),publicKey.toString(),temporary.toString(),"0.3.3",prefix};
        OfficialPlayerReleaseTool.main(verify);
        OfficialPlayerReleaseTool.main(new String[]{"verify-catalog",catalog.toString(),publicKey.toString(),"0.3.3"});
        verify[5]=prefix.replace("v0.3.2","v0.3.4");
        assertThrows(IllegalArgumentException.class,()->OfficialPlayerReleaseTool.main(verify));
        verify[5]=prefix; Files.writeString(archive,"changed archive");
        assertThrows(IllegalArgumentException.class,()->OfficialPlayerReleaseTool.main(verify));
        assertThrows(IllegalArgumentException.class,()->OfficialPlayerReleaseTool.main(new String[]{"verify-catalog",catalog.toString(),publicKey.toString(),"0.3.2"}));
    }
}
