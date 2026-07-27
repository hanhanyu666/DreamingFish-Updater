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
        assertFalse(manager.snapshot().overrides().withForcedDirectories(List.of("mods"))
                .excludes(managed));
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
