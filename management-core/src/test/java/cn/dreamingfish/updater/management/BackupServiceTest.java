package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupServiceTest {
    @TempDir
    Path temporary;

    @Test
    void encryptedBackupRestoresPublishingIdentityAndObjects() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("original"));
        fixture.createProject();
        Files.createDirectories(fixture.source.resolve("mods"));
        Files.writeString(fixture.source.resolve("mods/example.jar"), "content");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0.0", "0.1.0", "Ready");
        Path playerSource = Files.createDirectories(temporary.resolve("player-program"));
        Files.writeString(playerSource.resolve("player.cmd"), "player-content");
        new PlayerProgramService(fixture.paths, fixture.database, fixture.json).publish(
                "demo", "windows-x64", "0.2.0", playerSource, "player.cmd", "0.1.0");

        Path archive = temporary.resolve("complete.dfs-backup");
        char[] password = "correct horse battery staple".toCharArray();
        new BackupService(fixture.paths, fixture.database, fixture.json).create(archive, password);

        ManagementPaths restoredPaths = ManagementPaths.at(temporary.resolve("restored-data"));
        JsonCodec json = new JsonCodec();
        ManagementDatabase restoredDatabase = new ManagementDatabase(restoredPaths, json);
        new BackupService(restoredPaths, restoredDatabase, json).restore(archive, password, false);

        assertEquals(1, restoredDatabase.listProjects().size());
        assertEquals(1, restoredDatabase.listReleases("demo").size());
        ProjectRecord original = fixture.database.requireProject("demo");
        ProjectRecord restored = restoredDatabase.requireProject("demo");
        assertEquals(original.publicKey(), restored.publicKey());
        assertEquals(Files.readString(original.privateKeyFile()), Files.readString(restored.privateKeyFile()));
        var restoredPrograms = new PlayerProgramService(
                restoredPaths, restoredDatabase, json).list("demo", "windows-x64");
        assertEquals(1, restoredPrograms.size());
        assertEquals("0.2.0", restoredPrograms.getFirst().version());

        Files.writeString(fixture.source.resolve("mods/example.jar"), "content-after-restore");
        ScanService restoredScanner = new ScanService(restoredPaths, restoredDatabase, json);
        PublishService restoredPublisher = new PublishService(
                restoredPaths, restoredDatabase, restoredScanner, json);
        restoredScanner.createPreview("demo");
        StoredRelease continued = restoredPublisher.publish(
                "demo", "2.0.0", "0.1.0", "Published after restore");
        assertEquals(2, continued.sequence());
        byte[] continuedManifest = Files.readAllBytes(continued.manifestPath());
        assertEquals(original.publicKey(), restoredDatabase.requireProject("demo").publicKey());
        assertEquals(true, CryptoSupport.verify(
                continuedManifest,
                java.util.Base64.getDecoder().decode(continued.signature()),
                CryptoSupport.decodePublicKey(original.publicKey())));
    }

    @Test
    void wrongPasswordDoesNotCreateDestinationData() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("original"));
        fixture.createProject();
        Path archive = temporary.resolve("complete.dfs-backup");
        new BackupService(fixture.paths, fixture.database, fixture.json)
                .create(archive, "strong-password-one".toCharArray());

        ManagementPaths destination = ManagementPaths.at(temporary.resolve("wrong-restore"));
        BackupService restore = new BackupService(destination,
                new ManagementDatabase(destination, new JsonCodec()), new JsonCodec());
        assertThrows(ManagementException.class,
                () -> restore.restore(archive, "strong-password-two".toCharArray(), false));
        assertFalse(Files.exists(destination.database()));
    }
}
