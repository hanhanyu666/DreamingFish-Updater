package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationServiceTest {
    @TempDir Path temporary;
    WorkspaceOperationService operations(ManagementFixture f){return new WorkspaceOperationService(f.paths,f.database,f.json);}

    @Test void settingsHistoryIsDurableAndUndoRetainsUnrelatedProjectChanges()throws Exception{
        var f=new ManagementFixture(temporary);var p=f.createProject();
        Files.createDirectories(f.source.resolve("config"));Files.writeString(f.source.resolve("config/client.toml"),"value=1");
        new ProjectPolicyService(f.paths,f.database).setPreset("demo","config/client.toml",false,MaintenancePreset.INITIAL);
        var restarted=new ManagementDatabase(f.paths,new JsonCodec());restarted.initialize();
        assertEquals(1,restarted.settingsOperations("demo").size());
        var id=operations(f).history("demo").getFirst().id();
        var current=f.database.requireProject("demo");
        f.database.updateProject("demo","Renamed",current.sourceDirectory(),current.publicBaseUrl(),current.branding(),current.rules());
        var plan=operations(f).previewUndo("demo",id);
        operations(f).undo("demo",id,plan.stamp());
        assertEquals("Renamed",f.database.requireProject("demo").displayName());
        assertEquals(p.rules(),f.database.requireProject("demo").rules());
        assertTrue(operations(f).history("demo").stream().anyMatch(e->e.id().equals(id)&&e.undone()));
        operations(f).undo("demo",id,plan.stamp());
        assertEquals(p.rules(),f.database.requireProject("demo").rules());
    }

    @Test void stalePlanAndLaterPolicyEditsCannotBeOverwritten()throws Exception{
        var f=new ManagementFixture(temporary);f.createProject();
        Files.writeString(f.source.resolve("a.txt"),"a");
        var policies=new ProjectPolicyService(f.paths,f.database);
        policies.setPreset("demo","a.txt",false,MaintenancePreset.INITIAL);
        var id=operations(f).history("demo").getFirst().id();var plan=operations(f).previewUndo("demo",id);
        Files.writeString(f.source.resolve("a.txt"),"changed file");
        assertThrows(ManagementException.class,()->operations(f).undo("demo",id,plan.stamp()));
        assertEquals(MaintenancePreset.INITIAL,f.database.requireProject("demo").rules().presetFor("a.txt"));
        policies.setPreset("demo","a.txt",false,MaintenancePreset.REQUIRED);
        assertThrows(ManagementException.class,()->operations(f).previewUndo("demo",id));
        assertEquals(MaintenancePreset.REQUIRED,f.database.requireProject("demo").rules().presetFor("a.txt"));
    }

    @Test void publishedRestorePreservesExactFolderInheritanceAndSignedBytes()throws Exception{
        var f=new ManagementFixture(temporary);f.createProject();
        Files.createDirectories(f.source.resolve("config"));Files.writeString(f.source.resolve("config/a.txt"),"one");
        var policies=new ProjectPolicyService(f.paths,f.database);
        policies.setPreset("demo","config",true,MaintenancePreset.INITIAL);
        f.scanner.createPreview("demo");var release=f.publisher.publish("demo","1.0.0","0.2.0","initial");
        byte[] signed=Files.readAllBytes(release.manifestPath());long sequence=f.database.requireProject("demo").nextSequence();
        policies.setPreset("demo","config",true,MaintenancePreset.REQUIRED);
        var plan=operations(f).previewRestorePublished("demo");assertFalse(plan.changes().isEmpty());
        operations(f).restorePublished("demo",plan.stamp());
        var rules=f.database.requireProject("demo").rules();
        assertEquals(List.of(new PresetRule("config",true,MaintenancePreset.INITIAL)),rules.presets());
        assertEquals(MaintenancePreset.INITIAL,rules.presetFor("config/new.txt"));
        assertTrue(rules.cleanupDirectories().isEmpty());
        assertArrayEquals(signed,Files.readAllBytes(release.manifestPath()));
        assertEquals(sequence,f.database.requireProject("demo").nextSequence());
        assertTrue(operations(f).history("demo").stream().anyMatch(e->e.title().contains("恢复已发布")));
        assertTrue(operations(f).previewRestorePublished("demo").changes().isEmpty());
    }

    @Test void restoringPublishedRejectsInterveningPublication()throws Exception{
        var f=new ManagementFixture(temporary);f.createProject();Files.writeString(f.source.resolve("a.txt"),"a");
        f.scanner.createPreview("demo");f.publisher.publish("demo","1.0","0.2.0","");
        new ProjectPolicyService(f.paths,f.database).setPreset("demo","a.txt",false,MaintenancePreset.INITIAL);
        var plan=operations(f).previewRestorePublished("demo");
        f.scanner.createPreview("demo");f.publisher.publish("demo","2.0","0.2.0","");
        assertThrows(ManagementException.class,()->operations(f).restorePublished("demo",plan.stamp()));
        assertEquals(MaintenancePreset.INITIAL,f.database.requireProject("demo").rules().presetFor("a.txt"));
    }

    @Test void fileDeletionCanBeUndoneButCannotOverwriteALaterReplacement()throws Exception{
        var f=new ManagementFixture(temporary);f.createProject();Files.writeString(f.source.resolve("a.txt"),"original");
        var files=new SourceFileService(f.paths,f.database,f.json);files.remove("demo","a.txt",null);
        var entry=operations(f).history("demo").stream().filter(e->e.kind().equals("FILES")).findFirst().orElseThrow();
        Files.writeString(f.source.resolve("a.txt"),"later file");
        assertThrows(ManagementException.class,()->operations(f).previewUndo("demo",entry.id()));
        assertEquals("later file",Files.readString(f.source.resolve("a.txt")));
        Files.delete(f.source.resolve("a.txt"));var plan=operations(f).previewUndo("demo",entry.id());
        operations(f).undo("demo",entry.id(),plan.stamp());
        assertEquals("original",Files.readString(f.source.resolve("a.txt")));
        operations(f).undo("demo",entry.id(),plan.stamp());
    }

    @Test void sourceOverwriteUndoAndFolderUndoAreProtected()throws Exception{
        var f=new ManagementFixture(temporary);f.createProject();Files.writeString(f.source.resolve("a.txt"),"original");
        var files=new SourceFileService(f.paths,f.database,f.json);
        files.upload("demo","a.txt",new ByteArrayInputStream("new".getBytes()),3,true);
        var entry=operations(f).history("demo").stream().filter(e->e.kind().equals("FILES")).findFirst().orElseThrow();
        var plan=operations(f).previewUndo("demo",entry.id());operations(f).undo("demo",entry.id(),plan.stamp());
        assertEquals("original",Files.readString(f.source.resolve("a.txt")));
        files.createDirectory("demo","empty");
        entry=operations(f).history("demo").stream().filter(e->e.title().startsWith("新建文件夹")).findFirst().orElseThrow();
        String id=entry.id();Files.writeString(f.source.resolve("empty/file.txt"),"keep");
        assertThrows(ManagementException.class,()->operations(f).previewUndo("demo",id));
        assertEquals("keep",Files.readString(f.source.resolve("empty/file.txt")));
    }

    @Test void failureToPersistHistoryRestoresRemovedOrOverwrittenFiles()throws Exception{
        var f=new ManagementFixture(temporary);var p=f.createProject();Files.writeString(f.source.resolve("a.txt"),"keep");
        new ProjectPolicyService(f.paths,f.database).setPreset("demo","a.txt",false,MaintenancePreset.INITIAL);
        var before=f.database.requireProject("demo").rules();
        Path history=f.paths.root().resolve("source-operations");Files.writeString(history,"blocks directory creation");
        var files=new SourceFileService(f.paths,f.database,f.json);
        assertThrows(ManagementException.class,()->files.remove("demo","a.txt",null));
        assertEquals("keep",Files.readString(f.source.resolve("a.txt")));assertEquals(before,f.database.requireProject("demo").rules());
        assertThrows(ManagementException.class,()->files.upload("demo","a.txt",new ByteArrayInputStream("new".getBytes()),3,true));
        assertEquals("keep",Files.readString(f.source.resolve("a.txt")));
    }
}
