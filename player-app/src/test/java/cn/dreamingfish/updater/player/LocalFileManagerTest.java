package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileManagerTest {
    @TempDir
    Path temporary;

    @Test
    void supportsFileAndDirectoryRulesWithoutCrossingPathBoundaries() throws Exception {
        LocalFileManager manager = new LocalFileManager(temporary.resolve("player-home"));
        ReleaseManifest release = release(List.of(),
                file("config/client.toml", FilePolicy.ENFORCED),
                file("config/visual/effects.toml", FilePolicy.ENFORCED),
                file("configuration/other.toml", FilePolicy.ENFORCED));

        LocalFileEntry client = entry(manager.scan(release), "config/client.toml");
        manager.setManaged(client, false);
        assertTrue(manager.snapshot().overrides().excludesPath("config/client.toml"));
        assertFalse(manager.snapshot().overrides().excludesPath("configuration/other.toml"));

        LocalFileEntry config = entry(manager.scan(release), "config");
        manager.setManaged(config, false);
        var excluded = manager.snapshot();
        assertTrue(excluded.overrides().excludesPath("config/client.toml"));
        assertTrue(excluded.overrides().excludesPath("config/visual/effects.toml"));
        assertFalse(excluded.overrides().excludesPath("configuration/other.toml"));
        assertEquals("config", entry(manager.scan(release),
                "config/visual/effects.toml").inheritedExclusion());

        manager.setManaged(entry(manager.scan(release), "config"), true);
        assertTrue(manager.snapshot().overrides().isEmpty());
        assertTrue(entry(manager.scan(release), "config/client.toml").managed());
    }

    @Test
    void marksForcedDirectoriesAsImmutableWhileKeepingDormantLocalRules() throws Exception {
        LocalFileManager manager = new LocalFileManager(temporary.resolve("forced-home"));
        ReleaseManifest normal = release(List.of(),
                file("mods/renderer.jar", FilePolicy.ENFORCED));
        manager.setManaged(entry(manager.scan(normal), "mods"), false);

        ReleaseManifest forced = release(List.of("mods"),
                file("mods/renderer.jar", FilePolicy.ENFORCED));
        LocalFileEntry directory = entry(manager.scan(forced), "mods");
        LocalFileEntry file = entry(manager.scan(forced), "mods/renderer.jar");
        assertTrue(directory.forced());
        assertTrue(directory.managed());
        assertTrue(file.forced());
        assertTrue(file.managed());
        assertThrows(java.io.IOException.class, () -> manager.setManaged(directory, false));

        assertTrue(manager.snapshot().overrides().excludesPath("mods/renderer.jar"));
        assertFalse(manager.snapshot().overrides().withForcedDirectories(List.of("mods"))
                .excludesPath("mods/renderer.jar"));
    }

    private static LocalFileEntry entry(List<LocalFileEntry> entries, String path) {
        return entries.stream().filter(entry -> entry.path().equals(path))
                .findFirst().orElseThrow();
    }

    private static ManifestFile file(String path, FilePolicy policy) {
        return new ManifestFile(path, "0".repeat(64), 1, policy, false);
    }

    private static ReleaseManifest release(List<String> forced, ManifestFile... files) {
        return new ReleaseManifest(ProtocolConstants.RELEASE_SCHEMA_VERSION,
                "demo", "release-1", 1, Instant.now(), "1.0", "0.1.0", "test",
                forced.isEmpty() ? Set.of() : Set.of(
                        ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC),
                forced, Branding.empty(), List.of(files));
    }
}
