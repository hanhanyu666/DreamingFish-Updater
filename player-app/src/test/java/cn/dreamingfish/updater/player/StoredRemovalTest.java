package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.*;
import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class StoredRemovalTest {
    @TempDir Path temporary;

    @Test void disabledOfficialAndInitialPlayerCopiesAreArchivedWithARestoreBlock() throws Exception {
        for (MaintenancePreset preset : List.of(MaintenancePreset.SYNC, MaintenancePreset.INITIAL)) {
            Path instance = Files.createDirectories(temporary.resolve(preset.name()));
            Path home = instance.resolve("DreamingFishUpdater");
            Path d = jar(instance.resolve("mods/d.jar"), "d", "4");
            byte[] bytes = Files.readAllBytes(d);
            ManifestFile file = file(d, preset);
            LocalModManager mods = new LocalModManager(instance, home);
            ReleaseManifest four = release(List.of(file), List.of(), List.of());
            mods.setDisabled(mods.scan(four).getFirst(), true);
            mods.reconcileDesiredState(four);
            assertFalse(Files.exists(d));
            ReleaseManifest five = release(List.of(), List.of(removal(file)), List.of());
            mods.reconcileDesiredState(five);
            ArchiveCatalog catalog = new ArchiveCatalog(instance, home);
            var archive = catalog.list().getFirst();
            assertEquals(1, archive.files().size());
            var archived = archive.files().getFirst();
            assertEquals(ArchiveReason.OWNER_REMOVED, archived.reason());
            assertArrayEquals(bytes, Files.readAllBytes(archive.directory().resolve(archived.storedPath())));
            assertFalse(catalog.check(archive.id(), "mods/d.jar", five, LocalFileOverrides.NONE).allowed());
            assertEquals(1, mods.drainStoredArchives().size());
        }
    }

    @Test void anUnverifiedLocalManifestCannotMoveStoredCopiesIntoOwnerBackups() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("unverified"));
        Path home = instance.resolve("DreamingFishUpdater");
        ManifestFile file = file(jar(instance.resolve("mods/d.jar"), "d", "4"), MaintenancePreset.SYNC);
        LocalModManager mods = new LocalModManager(instance, home);
        ReleaseManifest four = release(List.of(file), List.of(), List.of());
        mods.setDisabled(mods.scan(four).getFirst(), true);
        mods.reconcileDesiredState(four);
        mods.reconcileDesiredState(release(List.of(), List.of(removal(file)), List.of()), Map.of(), false);
        assertTrue(new ArchiveCatalog(instance, home).list().isEmpty());
        assertEquals(1, mods.snapshot().overrides().storedModHashes().get("d").size());
    }

    @Test void archiveAndIndexMoveFailureRestoresStoredCopiesAndPreferences() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("failure"));
        Path home = instance.resolve("DreamingFishUpdater");
        ManifestFile file = file(jar(instance.resolve("mods/d.jar"), "d", "4"), MaintenancePreset.SYNC);
        LocalModManager setup = new LocalModManager(instance, home);
        ReleaseManifest four = release(List.of(file), List.of(), List.of());
        setup.setDisabled(setup.scan(four).getFirst(), true);
        setup.reconcileDesiredState(four);
        byte[] before = Files.readAllBytes(home.resolve("state/local-mod-preferences.json"));
        LocalModManager failing = new LocalModManager(instance, home, new LocalModTransaction.Faults() {
            @Override public void afterMove(int index) throws java.io.IOException {
                if (index == 1) throw new java.io.IOException("index move failed");
            }
        });
        assertThrows(java.io.IOException.class, () -> failing.reconcileDesiredState(release(List.of(), List.of(removal(file)), List.of())));
        assertArrayEquals(before, Files.readAllBytes(home.resolve("state/local-mod-preferences.json")));
        assertTrue(new ArchiveCatalog(instance, home).list().isEmpty());
        LocalModManager resumed = new LocalModManager(instance, home);
        resumed.reconcileDesiredState(release(List.of(), List.of(removal(file)), List.of()));
        assertEquals(1, new ArchiveCatalog(instance, home).list().size());
    }

    @Test void aOnceCorrectionDoesNotArchiveLaterPersonalChangesAgain() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("once"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path d = jar(instance.resolve("mods/d.jar"), "d", "4");
        ManifestFile old = file(d, MaintenancePreset.INITIAL);
        LocalModManager mods = new LocalModManager(instance, home);
        ReleaseManifest four = release(List.of(old), List.of(), List.of());
        mods.setDisabled(mods.scan(four).getFirst(), true);
        mods.reconcileDesiredState(four);
        Path correct = jar(temporary.resolve("correct/d.jar"), "d", "5");
        ManifestFile current = new ManifestFile("mods/d.jar", CryptoSupport.sha256(correct), Files.size(correct), FilePolicy.ENFORCED,
                false, "d", "d", MaintenancePreset.INITIAL, null, "5");
        Correction instruction = new Correction("fix-d", "mods/d.jar", CorrectionMode.ONCE, List.of(), "修复", Instant.now());
        ReleaseManifest five = release(List.of(current), List.of(), List.of(instruction));
        Files.createDirectories(home.resolve("state"));
        Files.write(home.resolve("state/maintenance-state.json"), new JsonCodec().writePretty(
                MaintenanceState.empty("demo").with(List.of(), List.of("fix-d"))));
        Files.copy(correct, d);
        mods.reconcileDesiredState(five);
        assertEquals(1, new ArchiveCatalog(instance, home).list().size());
        jar(d, "d", "personal");
        mods.reconcileDesiredState(five);
        assertEquals(1, new ArchiveCatalog(instance, home).list().size(), "one-time cleanup must not repeat");
    }

    private static Withdrawal removal(ManifestFile file) {
        return new Withdrawal("remove-d", "服主已移除D", Instant.now(), List.of(new WithdrawalItem(file.sha256(), file.size(),
                file.path(), file.componentId(), file.version())), Withdrawal.Kind.REMOVAL);
    }
    private static ReleaseManifest release(List<ManifestFile> files, List<Withdrawal> withdrawals, List<Correction> corrections) {
        return new ReleaseManifest(1, "demo", "r5", 5, Instant.now(), "5.0", "0.2.0", "", ProtocolConstants.RELEASE_CAPABILITIES,
                List.of(), List.of(), List.of(), Branding.empty(), files, List.of(), List.of(), List.of(), withdrawals, corrections);
    }
    private static ManifestFile file(Path jar, MaintenancePreset preset) throws Exception {
        return new ManifestFile("mods/d.jar", CryptoSupport.sha256(jar), Files.size(jar), FilePolicy.ENFORCED,
                false, "d", "d", preset, null, "4");
    }
    private static Path jar(Path path, String id, String version) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
