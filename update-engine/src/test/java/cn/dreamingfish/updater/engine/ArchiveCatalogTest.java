package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.dreamingfish.updater.protocol.MaintenancePreset.DEFAULT_CONFIG;
import static cn.dreamingfish.updater.protocol.MaintenancePreset.SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveCatalogTest {
    private static final Set<String> CAPABILITIES = ProtocolConstants.RELEASE_CAPABILITIES;

    @TempDir
    Path temporary;

    @Test
    void listsBackupsAndRestoresOnlyWhatTheNextUpdateWouldKeep() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var config = server.file("config/client.toml", "render=8", FilePolicy.ENFORCED);
            var synced = server.file("config/sync.toml", "sync=1", FilePolicy.ENFORCED);
            var bad = server.jarMod("mods/jei-15.2.jar", "jei", "15.2", "bad");
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            Path instance = Files.createDirectories(temporary.resolve("instance"));
            Path home = instance.resolve("DreamingFishUpdater");
            ReleaseManifest first = server.policy(1, "r1").file(config, DEFAULT_CONFIG)
                    .file(synced, SYNC).file(bad, SYNC).file(a, SYNC).cleanup("mods").build();
            server.bundle(instance, first, true);
            Files.writeString(instance.resolve("config/client.toml"), "render=16");
            Files.writeString(instance.resolve("config/sync.toml"), "sync=player");
            Files.writeString(instance.resolve("mods/extra.jar"), "extra");

            ReleaseManifest second = server.policy(2, "r2").file(config, DEFAULT_CONFIG)
                    .file(synced, SYNC).file(a, SYNC).cleanup("mods")
                    .withdraw("bad-jei", "崩服", bad).build();
            server.serve(second);
            update(instance, LocalFileOverrides.NONE.withResetRequests(List.of("config/client.toml")), server);

            ArchiveCatalog catalog = new ArchiveCatalog(instance, home);
            List<ArchiveCatalog.Archive> archives = catalog.list();
            assertEquals(1, archives.size());
            ArchiveCatalog.Archive archive = archives.getFirst();
            assertFalse(archive.legacy());
            Map<String, ArchivedFile> files = new java.util.HashMap<>();
            archive.files().forEach(file -> files.put(file.originalPath(), file));
            assertEquals(Set.of("config/client.toml", "config/sync.toml", "mods/extra.jar",
                    "mods/jei-15.2.jar"), files.keySet());
            assertEquals(ArchiveReason.WITHDRAWN, files.get("mods/jei-15.2.jar").reason());

            ReleaseManifest installed = second;
            assertFalse(catalog.check(archive.id(), "mods/jei-15.2.jar", installed, LocalFileOverrides.NONE).allowed());
            assertFalse(catalog.check(archive.id(), "mods/extra.jar", installed, LocalFileOverrides.NONE).allowed());
            assertFalse(catalog.check(archive.id(), "config/sync.toml", installed, LocalFileOverrides.NONE).allowed());
            // The default configuration was replaced at the player's request; the file exists again.
            assertFalse(catalog.check(archive.id(), "config/client.toml", installed, LocalFileOverrides.NONE).allowed());

            // A self-managed file may be restored once the owner's copy is moved away.
            LocalFileOverrides selfManaged = new LocalFileOverrides(Set.of(), Set.of("config/sync.toml"));
            Files.delete(instance.resolve("config/sync.toml"));
            assertTrue(catalog.check(archive.id(), "config/sync.toml", installed, selfManaged).allowed());
            catalog.restore(archive.id(), "config/sync.toml", installed, selfManaged);
            assertEquals("sync=player", Files.readString(instance.resolve("config/sync.toml")));
            ArchivedFile restored = catalog.find(archive.id()).orElseThrow().files().stream()
                    .filter(file -> file.originalPath().equals("config/sync.toml")).findFirst().orElseThrow();
            assertNotNull(restored.restoredAt());
            assertThrows(java.io.IOException.class, () ->
                    catalog.restore(archive.id(), "config/sync.toml", installed, selfManaged));

            catalog.delete(archive.id());
            assertTrue(catalog.list().isEmpty());
        }
    }

    @Test
    void readsForcedSyncArchivesFromEarlierPlayerVersions() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("legacy"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path legacy = Files.createDirectories(home.resolve(
                "backups/forced-sync/2026-08-01_12-00-00-000_r1_abcd1234/mods"));
        Files.writeString(legacy.resolve("old.jar"), "old");
        Files.writeString(legacy.getParent().resolve("archived-files.txt"), """
                DreamingFish forced sync archive
                Release: 1.0 (r1)
                Remote management forced directories: mods
                Archived files: 1

                mods/old.jar
                """);
        List<ArchiveCatalog.Archive> archives = new ArchiveCatalog(instance, home).list();
        assertEquals(1, archives.size());
        assertTrue(archives.getFirst().legacy());
        assertEquals("r1", archives.getFirst().releaseId());
        assertEquals(ArchiveReason.LEGACY, archives.getFirst().files().getFirst().reason());
        assertEquals(3, archives.getFirst().files().getFirst().size());
    }

    private static void update(Path instance, LocalFileOverrides choices, TestUpdateServer server) {
        new UpdateEngine().update(new UpdateRequest(instance, instance.resolve("DreamingFishUpdater"),
                server.binding(), "0.2.0", CAPABILITIES, null, null, null, CancellationToken.NEVER, choices), null);
    }
}
