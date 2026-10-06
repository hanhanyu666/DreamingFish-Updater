package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalModManagerTest {
    @TempDir
    Path temporary;

    @Test
    void disablesManagedModAcrossFilenameChangesAndRestoresPackDefaults() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path playerHome = instance.resolve("DreamingFishUpdater");
        Path oldJar = fabricJar(instance.resolve("mods/renderer-1.jar"),
                "renderer", "Renderer", "old");
        ReleaseManifest first = release("release-1", 1, manifestFile(oldJar,
                "mods/renderer-1.jar", "renderer", "Renderer"));
        LocalModManager manager = new LocalModManager(instance, playerHome);

        LocalModEntry entry = manager.scan(first).getFirst();
        assertTrue(entry.managed());
        assertTrue(entry.active());
        manager.setDisabled(entry, true);
        assertTrue(manager.snapshot().overrides().excludesComponent("renderer"));
        manager.reconcileDesiredState();
        assertFalse(Files.exists(oldJar));
        assertFalse(manager.scan(first).getFirst().active());

        Path newJar = fabricJar(instance.resolve("mods/renderer-2.jar"),
                "renderer", "Renderer", "new");
        ReleaseManifest second = release("release-2", 2, manifestFile(newJar,
                "mods/renderer-2.jar", "renderer", "Renderer"));
        manager.reconcileDesiredState();
        assertFalse(Files.exists(newJar));
        assertTrue(manager.snapshot().overrides().excludes(
                second.files().getFirst()));

        manager.setDisabled(manager.scan(second).getFirst(), false);
        assertTrue(manager.snapshot().overrides().isEmpty());
        manager.reconcileDesiredState();
        manager.finalizeSuccessfulUpdate();
        assertTrue(manager.scan(second).isEmpty());
        assertFalse(Files.exists(playerHome.resolve("state/local-mod-preferences.json"))
                && Files.readString(playerHome.resolve("state/local-mod-preferences.json"))
                .contains("renderer"));
    }

    @Test
    void returnsAPlayerAddedModToItsOriginalPathWhenReEnabled() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("custom-instance"));
        Path playerHome = instance.resolve("DreamingFishUpdater");
        Path custom = fabricJar(instance.resolve("mods/personal-map.jar"),
                "personal_map", "Personal Map", "custom");
        LocalModManager manager = new LocalModManager(instance, playerHome);
        ReleaseManifest empty = release("release-1", 1);

        LocalModEntry entry = manager.scan(empty).getFirst();
        assertFalse(entry.managed());
        manager.setDisabled(entry, true);
        manager.reconcileDesiredState();
        assertFalse(Files.exists(custom));

        manager.restoreDefaults();
        manager.reconcileDesiredState();
        manager.finalizeSuccessfulUpdate();
        assertTrue(Files.isRegularFile(custom));
        assertEquals("personal_map",
                cn.dreamingfish.updater.protocol.ModMetadataReader.read(custom)
                        .orElseThrow().componentId());
        assertTrue(manager.snapshot().overrides().isEmpty());
    }

    @Test
    void forcedSyncKeepsAModActiveEvenWhenAnOlderPreferenceDisabledIt() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("forced-mod-instance"));
        Path playerHome = instance.resolve("DreamingFishUpdater");
        Path jar = fabricJar(instance.resolve("mods/renderer.jar"),
                "renderer", "Renderer", "forced");
        ManifestFile managed = manifestFile(jar, "mods/renderer.jar", "renderer", "Renderer");
        LocalModManager manager = new LocalModManager(instance, playerHome);
        ReleaseManifest normal = release("release-1", 1, managed);

        manager.setDisabled(manager.scan(normal).getFirst(), true);
        ReleaseManifest forced = new ReleaseManifest(
                ProtocolConstants.RELEASE_SCHEMA_VERSION, "demo", "release-2", 2,
                Instant.now(), "1.0.2", "0.1.0", "test",
                Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC),
                List.of("mods"), Branding.empty(), List.of(managed));
        manager.reconcileDesiredState(forced);

        assertTrue(Files.isRegularFile(jar));
        LocalModEntry entry = manager.scan(forced).getFirst();
        assertTrue(entry.forced());
        assertTrue(entry.active());
        assertFalse(manager.snapshot().overrides().excludes(managed,
                cn.dreamingfish.updater.protocol.MaintenanceModel.of(forced)));
    }

    @Test
    void restoresAReleasedManagedModInsteadOfDeletingItsOnlyCopy() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("released-instance"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path jar = fabricJar(instance.resolve("mods/retired.jar"), "retired", "Retired", "player copy");
        byte[] original = Files.readAllBytes(jar);
        ReleaseManifest managed = release("r1", 1, manifestFile(jar, "mods/retired.jar", "retired", "Retired"));
        ReleaseManifest released = released("r2", 2, List.of("mods/retired.jar"));
        LocalModManager manager = new LocalModManager(instance, home);
        manager.setDisabled(manager.scan(managed).getFirst(), true);
        manager.reconcileDesiredState(managed);
        manager.reconcileDesiredState(released);
        manager.setDisabled(manager.scan(released).getFirst(), false);
        manager.reconcileDesiredState(released);
        manager.finalizeSuccessfulUpdate(released);
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(jar));
        assertTrue(manager.snapshot().overrides().isEmpty());
    }

    @Test
    void keepsAReleasedHistoricalCopyWhenTheComponentHasANewManagedFilename() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("renamed-release-instance"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path oldJar = fabricJar(instance.resolve("mods/old.jar"), "renderer", "Renderer", "old");
        LocalModManager manager = new LocalModManager(instance, home);
        ReleaseManifest first = release("r1", 1, manifestFile(oldJar, "mods/old.jar", "renderer", "Renderer"));
        manager.setDisabled(manager.scan(first).getFirst(), true);
        manager.reconcileDesiredState(first);
        Path newJar = fabricJar(instance.resolve("mods/new.jar"), "renderer", "Renderer", "new");
        ReleaseManifest next = released("r2", 2, List.of("mods/old.jar"),
                manifestFile(newJar, "mods/new.jar", "renderer", "Renderer"));
        manager.setDisabled(manager.scan(next).getFirst(), false);
        manager.reconcileDesiredState(next);
        manager.finalizeSuccessfulUpdate(next);
        assertTrue(Files.isRegularFile(newJar));
        LocalModPreferences preferences = new cn.dreamingfish.updater.protocol.JsonCodec()
                .read(home.resolve("state/local-mod-preferences.json"), LocalModPreferences.class);
        StoredLocalMod kept = preferences.mods().getFirst().storedFiles().getFirst();
        assertTrue(kept.playerOwned());
        assertTrue(Files.isRegularFile(home.resolve(kept.storedPath())));
        assertFalse(Files.exists(oldJar));
    }

    @Test
    void rollsBackFileMovesAndIndexesWhenALocalMoveFails() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("failed-move-instance"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path first = fabricJar(instance.resolve("mods/a.jar"), "first", "First", "first");
        Path second = fabricJar(instance.resolve("mods/b.jar"), "second", "Second", "second");
        LocalModManager manager = new LocalModManager(instance, home, new LocalModTransaction.Faults() {
            @Override public void afterMove(int index) throws java.io.IOException {
                if (index == 0) throw new java.io.IOException("Injected file move failure");
            }
        });
        for (LocalModEntry entry : manager.scan(null)) manager.setDisabled(entry, true);
        assertThrows(java.io.IOException.class, manager::reconcileDesiredState);
        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(second));
        LocalModPreferences preferences = new cn.dreamingfish.updater.protocol.JsonCodec()
                .read(home.resolve("state/local-mod-preferences.json"), LocalModPreferences.class);
        assertTrue(preferences.mods().stream().allMatch(p -> p.storedFiles().isEmpty()));
        assertFalse(Files.exists(home.resolve("state/local-mod-transaction/journal.json")));
    }

    @Test
    void recoversAfterACrashBetweenPreferenceWriteAndCommit() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("crashed-move-instance"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path jar = fabricJar(instance.resolve("mods/custom.jar"), "custom", "Custom", "original");
        byte[] original = Files.readAllBytes(jar);
        LocalModManager crashing = new LocalModManager(instance, home, new LocalModTransaction.Faults() {
            @Override public void afterPreferences() { throw new SimulatedCrash(); }
        });
        crashing.setDisabled(crashing.scan(null).getFirst(), true);
        assertThrows(SimulatedCrash.class, crashing::reconcileDesiredState);
        assertFalse(Files.exists(jar));
        var keys = CryptoSupport.generateEd25519KeyPair();
        var binding = new cn.dreamingfish.updater.protocol.ProjectBinding(1, "demo", "http://127.0.0.1:1",
                CryptoSupport.encodePublicKey(keys.getPublic()), "DreamingFishUpdater", null, Branding.empty());
        var request = cn.dreamingfish.updater.engine.UpdateRequest.defaults(instance, home, binding, "0.1.41", Set.of());
        var blocked = assertThrows(cn.dreamingfish.updater.engine.UpdateException.class,
                () -> new cn.dreamingfish.updater.engine.UpdateEngine().update(request, null));
        assertEquals(cn.dreamingfish.updater.engine.UpdateErrorCode.RECOVERY_FAILED, blocked.code());
        LocalModManager restarted = new LocalModManager(instance, home);
        restarted.recoverPendingTransaction();
        assertFalse(Files.exists(home.resolve("state/transactions/local-mods/journal.json")));
        restarted.recoverPendingTransaction();
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(jar));
        assertTrue(restarted.scan(null).getFirst().disabled());
        restarted.reconcileDesiredState();
        assertFalse(Files.exists(jar));
        restarted.restoreDefaults();
        restarted.reconcileDesiredState();
        assertTrue(Files.isRegularFile(jar));
    }

    private static final class SimulatedCrash extends Error { }

    @Test
    void preservesReadOnlyPlayerModsWhenDisablingAndRestoring() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Path instance = Files.createDirectories(temporary.resolve("readonly-instance"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path jar = fabricJar(instance.resolve("mods/readonly.jar"), "readonly", "Read Only", "original");
        Files.setAttribute(jar, "dos:readonly", true);
        LocalModManager manager = new LocalModManager(instance, home);
        try {
            manager.setDisabled(manager.scan(null).getFirst(), true);
            manager.reconcileDesiredState();
            manager.restoreDefaults();
            manager.reconcileDesiredState();
            assertTrue(Files.isRegularFile(jar));
            assertTrue((boolean) Files.getAttribute(jar, "dos:readonly"));
        } finally {
            try (var files = Files.walk(instance)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) Files.setAttribute(path, "dos:readonly", false);
            }
        }
    }

    private ReleaseManifest released(String id, long sequence, List<String> paths, ManifestFile... files) {
        return new ReleaseManifest(ProtocolConstants.RELEASE_SCHEMA_VERSION, "demo", id, sequence,
                Instant.now(), "1.0." + sequence, "0.1.0", "release",
                Set.of(ProtocolConstants.CAPABILITY_RELEASED_PATHS), List.of(), List.of(), paths,
                Branding.empty(), List.of(files));
    }

    private Path fabricJar(Path path, String id, String name, String marker) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write(("{\"schemaVersion\":1,\"id\":\"" + id
                    + "\",\"name\":\"" + name + "\",\"version\":\"1.0\"}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("marker.txt"));
            output.write(marker.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }

    private ManifestFile manifestFile(Path jar, String path, String componentId,
                                      String displayName) throws Exception {
        return new ManifestFile(path, CryptoSupport.sha256(jar), Files.size(jar),
                FilePolicy.ENFORCED, false, componentId, displayName);
    }

    private ReleaseManifest release(String id, long sequence, ManifestFile... files) {
        return new ReleaseManifest(
                ProtocolConstants.RELEASE_SCHEMA_VERSION, "demo", id, sequence,
                Instant.now(), "1.0." + sequence, "0.1.0", "test",
                Set.of(), List.of(), Branding.empty(), List.of(files));
    }
}
