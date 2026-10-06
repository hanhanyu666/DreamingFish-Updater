package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.MaintenancePreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceTransferServiceTest {
    @TempDir Path temporary;
    private SourceTransferService service(ManagementFixture fixture) {
        return new SourceTransferService(fixture.paths, fixture.database, fixture.json);
    }
    private static SourceTransferService.Plan stage(SourceTransferService service, String path, byte[] bytes) {
        return service.stage("demo", path, new ByteArrayInputStream(bytes), bytes.length);
    }
    @Test void stagingDoesNotChangeFilesAndOverwriteHasVerifiedUndo() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject();
        Path file=fixture.source.resolve("config/client.txt"); Files.createDirectories(file.getParent()); Files.writeString(file,"old");
        var service=service(fixture); var plan=stage(service,"config/client.txt","new".getBytes());
        assertEquals("old",Files.readString(file));
        assertThrows(ManagementException.class,()->service.commit("demo",plan.id(),"ADD",plan.stamp()));
        service.commit("demo",plan.id(),"OVERWRITE",plan.stamp());
        assertEquals("new",Files.readString(file)); assertEquals(1,service.history("demo").size());
        service.undo("demo",plan.id()); assertEquals("old",Files.readString(file));
        assertTrue(service.history("demo").getFirst().undone());
        assertThrows(ManagementException.class,()->service.commit("demo",plan.id(),"OVERWRITE",plan.stamp()));
    }
    @Test void renamedModReplacementPreservesRulesAndGroupAndCanBeUndone() throws Exception {
        var fixture=new ManagementFixture(temporary); var project=fixture.createProject();
        Path old=fixture.source.resolve("mods/B-1.0.jar"); Files.createDirectories(old.getParent()); Files.write(old,mod("b","1.0"));
        var group=new OptionalGroupRule("visual","Visual","",true,List.of("b"),List.of("mods/B-1.0.jar"),List.of());
        var rules=project.rules().withPresets(List.of(new PresetRule("mods/B-1.0.jar",false,MaintenancePreset.SYNC))).withOptionalGroups(List.of(group));
        fixture.database.updateProject("demo",project.displayName(),project.sourceDirectory(),project.publicBaseUrl(),project.branding(),rules);
        var service=service(fixture); var plan=stage(service,"mods/B-1.1.jar",mod("b","1.1"));
        assertEquals("b",plan.metadata().componentId()); assertEquals(1,plan.replacements().size());
        assertThrows(ManagementException.class,()->service.commit("demo",plan.id(),"ADD",plan.stamp()));
        service.commit("demo",plan.id(),"REPLACE_MOD",plan.stamp());
        assertFalse(Files.exists(old)); assertTrue(Files.exists(fixture.source.resolve("mods/B-1.1.jar")));
        var current=fixture.database.requireProject("demo").rules();
        assertEquals(MaintenancePreset.SYNC,current.presetFor("mods/B-1.1.jar"));
        assertEquals(List.of("mods/B-1.1.jar"),current.optionalGroups().getFirst().files());
        assertEquals(List.of("b"),current.optionalGroups().getFirst().modIds());
        service.undo("demo",plan.id()); assertTrue(Files.exists(old));
        assertFalse(Files.exists(fixture.source.resolve("mods/B-1.1.jar")));
        assertEquals(rules,fixture.database.requireProject("demo").rules());
    }
    @Test void replacementKeepsARequiredSingleFileRequired() throws Exception {
        var fixture=new ManagementFixture(temporary); var project=fixture.createProject();
        Path old=fixture.source.resolve("mods/core-1.jar"); Files.createDirectories(old.getParent()); Files.write(old,mod("core","1.0"));
        var rules=project.rules().withPresets(List.of(new PresetRule("mods/core-1.jar",false,MaintenancePreset.REQUIRED)));
        fixture.database.updateProject("demo",project.displayName(),project.sourceDirectory(),project.publicBaseUrl(),project.branding(),rules);
        var service=service(fixture); var plan=stage(service,"mods/core-2.jar",mod("core","2.0"));
        service.commit("demo",plan.id(),"REPLACE_MOD",plan.stamp());
        assertEquals(MaintenancePreset.REQUIRED,fixture.database.requireProject("demo").rules().presetFor("mods/core-2.jar"));
    }
    @Test void stalePreviewAndUndoNeverOverwriteLaterEdits() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject();
        Path file=fixture.source.resolve("a.txt"); Files.writeString(file,"old"); var service=service(fixture);
        var plan=stage(service,"a.txt","new".getBytes()); Files.writeString(file,"later edit");
        assertThrows(ManagementException.class,()->service.commit("demo",plan.id(),"OVERWRITE",plan.stamp()));
        assertEquals("later edit",Files.readString(file));
        var checked=service.plan("demo",plan.id()); service.commit("demo",plan.id(),"OVERWRITE",checked.stamp());
        Files.writeString(file,"another edit"); assertThrows(ManagementException.class,()->service.undo("demo",plan.id()));
        assertEquals("another edit",Files.readString(file));
        service.commit("demo",plan.id(),"OVERWRITE",checked.stamp());
        assertEquals("another edit",Files.readString(file),"Retrying a completed import must not repeat its writes");
        assertTrue(service.plan("demo",plan.id()).committed());
    }
    @Test void modReplacementCannotOverwriteAnUnrelatedSameNamedFile() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject(); Files.createDirectories(fixture.source.resolve("mods"));
        byte[] unrelated=mod("other","1.0"); Files.write(fixture.source.resolve("mods/new.jar"),unrelated);
        Files.write(fixture.source.resolve("mods/old.jar"),mod("b","1.0"));
        var service=service(fixture); var plan=stage(service,"mods/new.jar",mod("b","2.0"));
        assertThrows(ManagementException.class,()->service.commit("demo",plan.id(),"REPLACE_MOD",plan.stamp()));
        assertArrayEquals(unrelated,Files.readAllBytes(fixture.source.resolve("mods/new.jar")));
        assertTrue(Files.exists(fixture.source.resolve("mods/old.jar")));
    }
    @Test void interruptedStagingAndUnsafeTargetsLeaveSourcesUntouched() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject(); var service=service(fixture);
        assertThrows(ManagementException.class,()->service.stage("demo","a.txt",new InputStream(){@Override public int read() throws IOException {throw new IOException("disconnected");}},10));
        assertFalse(Files.exists(fixture.source.resolve("a.txt"))); assertTrue(service.history("demo").isEmpty());
        assertThrows(RuntimeException.class,()->stage(service,"../outside.txt","bad".getBytes()));
        assertThrows(ManagementException.class,()->stage(service,"saves/world.dat","bad".getBytes()));
    }
    @Test void replacementCarriesTheOwnersRemovalChoiceIntoThePublishPreview() throws Exception {
        var fixture = new ManagementFixture(temporary); fixture.createProject();
        Path old = fixture.source.resolve("mods/b-1.jar"); Files.createDirectories(old.getParent()); Files.write(old, mod("b", "1.0"));
        fixture.scanner.createPreview("demo"); fixture.publisher.publish("demo", "1.0", "0.2.0", "initial");
        var service = service(fixture); var plan = stage(service, "mods/b-2.jar", mod("b", "2.0"));
        service.commit("demo", plan.id(), "REPLACE_MOD", plan.stamp(), false);
        var preview = service.checkImports("demo", List.of(plan.id()));
        var removal = preview.changes().stream().filter(change -> change.path().equals("mods/b-1.jar")).findFirst().orElseThrow();
        assertEquals(RemovalAction.DELETE, removal.removalAction());
    }
    static byte[] mod(String id,String version) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {zip.putNextEntry(new ZipEntry("fabric.mod.json")); zip.write(("{\"schemaVersion\":1,\"id\":\""+id+"\",\"name\":\""+id+"\",\"version\":\""+version+"\"}").getBytes()); zip.closeEntry();}
        return bytes.toByteArray();
    }
}
