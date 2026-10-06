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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void preservesBothRecoveryCandidatesWhenInstallAndRollbackFail() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("double-failure"));
        fixture.createProject();
        Path marker = fixture.paths.root().resolve("original-marker.txt");
        Files.writeString(marker, "sole old data");
        Path archive = temporary.resolve("failure.dfs-backup");
        char[] password = "strong-password-for-recovery".toCharArray();
        new BackupService(fixture.paths, fixture.database, fixture.json).create(archive, password);
        Path[] retained = new Path[2];
        BackupRestoreFaultInjector faults = new BackupRestoreFaultInjector() {
            @Override public void beforeInstall(Path restored, Path destination) throws java.io.IOException {
                retained[0] = restored;
                throw new java.io.IOException("Injected install failure");
            }
            @Override public void beforeRollback(Path previous, Path destination) throws java.io.IOException {
                retained[1] = previous;
                throw new java.io.IOException("Injected rollback failure");
            }
        };
        ManagementException error = assertThrows(ManagementException.class, () ->
                new BackupService(fixture.paths, fixture.database, fixture.json, faults)
                        .restore(archive, password, true));
        assertEquals("sole old data", Files.readString(retained[1].resolve("original-marker.txt")));
        assertTrue(Files.isRegularFile(retained[0].resolve("management.db")));
        assertTrue(error.getMessage().contains(retained[0].toString()));
        assertTrue(error.getMessage().contains(retained[1].toString()));
    }

    @Test
    void restoresOriginalDirectoryWhenInstallingTheBackupFails() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("single-failure"));
        fixture.createProject();
        Files.writeString(fixture.paths.root().resolve("original-marker.txt"), "old data");
        Path archive = temporary.resolve("single-failure.dfs-backup");
        char[] password = "strong-password-for-recovery".toCharArray();
        new BackupService(fixture.paths, fixture.database, fixture.json).create(archive, password);
        BackupRestoreFaultInjector faults = new BackupRestoreFaultInjector() {
            @Override public void beforeInstall(Path restored, Path destination) throws java.io.IOException {
                throw new java.io.IOException("Injected install failure");
            }
        };
        assertThrows(ManagementException.class, () ->
                new BackupService(fixture.paths, fixture.database, fixture.json, faults)
                        .restore(archive, password, true));
        assertEquals("old data", Files.readString(fixture.paths.root().resolve("original-marker.txt")));
        assertEquals(1, fixture.database.listProjects().size());
    }
}
