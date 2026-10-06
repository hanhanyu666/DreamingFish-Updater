package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPackageServiceTest {
    @TempDir Path temporary;
    @Test void validatesZipThenPublishesItsRecognizedVersionWithoutChangingPack() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject();
        var service=new PlayerPackageService(fixture.paths,fixture.database,fixture.json);
        byte[] bytes=bundle(Map.of("DreamingFishUpdater/state/active-player.properties","schema=1\nversion=0.2.1\n",
                "DreamingFishUpdater/app/0.2.1/DreamingFishUpdater.exe","launcher",
                "DreamingFishUpdater/app/0.2.1/app/player-app.jar","application",
                "DreamingFishUpdater/app/0.2.1/runtime/release","runtime"));
        var plan=service.stage("demo","player.zip",new ByteArrayInputStream(bytes),bytes.length);
        assertEquals("0.2.1",plan.version()); assertEquals("windows-x64",plan.platform()); assertTrue(plan.publishable());
        assertTrue(fixture.database.latestRelease("demo").isEmpty());
        var program=service.publish("demo",plan.id(),"0.1.2"); assertEquals("0.2.1",program.version());
        assertTrue(fixture.database.latestRelease("demo").isEmpty());
        var same=service.stage("demo","same.zip",new ByteArrayInputStream(bytes),bytes.length);
        assertFalse(same.publishable()); assertThrows(ManagementException.class,()->service.publish("demo",same.id(),"0.1.2"));
        service.discard("demo",plan.id()); assertEquals("0.2.1",new PlayerProgramService(fixture.paths,fixture.database,fixture.json).latest("demo","windows-x64").orElseThrow().version());
    }
    @Test void rejectsTraversalAndCaseCollisionsBeforePublishing() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject();
        var service=new PlayerPackageService(fixture.paths,fixture.database,fixture.json);
        byte[] traversal=bundle(Map.of("../outside.txt","bad"));
        assertThrows(RuntimeException.class,()->service.stage("demo","bad.zip",new ByteArrayInputStream(traversal),traversal.length));
        assertFalse(Files.exists(temporary.resolve("outside.txt")));
        byte[] collision=bundle(Map.of("App/a.txt","one","app/A.txt","two"));
        assertThrows(RuntimeException.class,()->service.stage("demo","bad.zip",new ByteArrayInputStream(collision),collision.length));
        assertTrue(new PlayerProgramService(fixture.paths,fixture.database,fixture.json).list("demo","windows-x64").isEmpty());
    }
    static byte[] bundle(Map<String,String> entries) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {for(var entry:entries.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes()); zip.closeEntry();}}
        return bytes.toByteArray();
    }
}
