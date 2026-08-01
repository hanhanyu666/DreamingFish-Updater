package cn.dreamingfish.updater.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStorageMaintenanceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void retainsCurrentAndFallbackButRemovesOnlyUpdaterOwnedReproducibleData() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path playerHome = instance.resolve("DreamingFishUpdater");
        EnginePaths paths = EnginePaths.of(instance, playerHome);
        paths.createDirectories();

        Path current = createFile(playerHome.resolve("app/current/player.exe"));
        Path fallback = createFile(playerHome.resolve("app/fallback/player.exe"));
        Path obsolete = createFile(playerHome.resolve("app/obsolete/player.exe"));
        String currentHash = "a".repeat(64);
        String fallbackHash = "b".repeat(64);
        String obsoleteHash = "c".repeat(64);
        createFile(playerHome.resolve("state/player-programs/" + currentHash + "/manifest.json"));
        createFile(playerHome.resolve("state/player-programs/" + fallbackHash + "/manifest.json"));
        createFile(playerHome.resolve("state/player-programs/" + obsoleteHash + "/manifest.json"));
        Files.writeString(playerHome.resolve("state/active-player.properties"),
                "schema=1\n"
                        + "version=0.1.20\n"
                        + "launcher=app/current/player.exe\n"
                        + "programRoot=app/current\n"
                        + "manifestSha256=" + currentHash + "\n"
                        + "fallbackVersion=0.1.19\n"
                        + "fallbackLauncher=app/fallback/player.exe\n"
                        + "fallbackProgramRoot=app/fallback\n"
                        + "fallbackManifestSha256=" + fallbackHash + "\n"
                        + "timeoutSeconds=3600\n",
                StandardCharsets.UTF_8);

        Path cache = createFile(paths.cacheObject("d".repeat(64)));
        Path oldPartial = createFile(paths.downloads().resolve("e".repeat(64) + ".part"));
        Path recentPartial = createFile(paths.downloads().resolve("f".repeat(64) + ".part"));
        Path unrelatedStagingFile = createFile(paths.downloads().resolve("keep-me.txt"));
        Path oldProgramStaging = createFile(
                playerHome.resolve("staging/player-program/old/player.exe"));
        Files.setLastModifiedTime(oldPartial, FileTime.from(NOW.minusSeconds(8 * 24 * 60 * 60L)));
        Files.setLastModifiedTime(recentPartial, FileTime.from(NOW.minusSeconds(24 * 60 * 60L)));
        Files.setLastModifiedTime(oldProgramStaging.getParent(),
                FileTime.from(NOW.minusSeconds(8 * 24 * 60 * 60L)));

        Path playerFile = createFile(instance.resolve("mods/player-file.jar"));
        Path forcedBackup = createFile(paths.forcedSyncBackups().resolve("release/config.txt"));

        PlayerStorageMaintenance maintenance = new PlayerStorageMaintenance(
                Clock.fixed(NOW, ZoneOffset.UTC));
        maintenance.cleanExpiredStaging(paths);
        maintenance.cleanSupersededPrograms(paths);
        maintenance.cleanObjectCache(paths);

        assertTrue(Files.exists(current));
        assertTrue(Files.exists(fallback));
        assertFalse(Files.exists(obsolete));
        assertTrue(Files.exists(playerHome.resolve(
                "state/player-programs/" + currentHash + "/manifest.json")));
        assertTrue(Files.exists(playerHome.resolve(
                "state/player-programs/" + fallbackHash + "/manifest.json")));
        assertFalse(Files.exists(playerHome.resolve("state/player-programs/" + obsoleteHash)));
        assertFalse(Files.exists(cache));
        assertFalse(Files.exists(oldPartial));
        assertTrue(Files.exists(recentPartial));
        assertTrue(Files.exists(unrelatedStagingFile));
        assertFalse(Files.exists(oldProgramStaging));
        assertTrue(Files.exists(playerFile));
        assertTrue(Files.exists(forcedBackup));
    }

    @Test
    void refusesToPruneProgramVersionsWhenActiveStateIsMissingOrInvalid() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("invalid-state-instance"));
        Path playerHome = instance.resolve("DreamingFishUpdater");
        EnginePaths paths = EnginePaths.of(instance, playerHome);
        paths.createDirectories();
        Path oldProgram = createFile(playerHome.resolve("app/unknown/player.exe"));
        Path oldManifest = createFile(playerHome.resolve(
                "state/player-programs/" + "a".repeat(64) + "/manifest.json"));
        Files.writeString(playerHome.resolve("state/active-player.properties"),
                "schema=broken\nprogramRoot=app/current\n", StandardCharsets.UTF_8);

        new PlayerStorageMaintenance(Clock.fixed(NOW, ZoneOffset.UTC))
                .cleanSupersededPrograms(paths);

        assertTrue(Files.exists(oldProgram));
        assertTrue(Files.exists(oldManifest));
    }

    @Test
    void preservesAllProgramManifestsWhenTheRetainedHashCannotBeTrusted() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("invalid-hash-instance"));
        Path playerHome = instance.resolve("DreamingFishUpdater");
        EnginePaths paths = EnginePaths.of(instance, playerHome);
        paths.createDirectories();
        createFile(playerHome.resolve("app/current/player.exe"));
        Path oldManifest = createFile(playerHome.resolve(
                "state/player-programs/" + "a".repeat(64) + "/manifest.json"));
        Files.writeString(playerHome.resolve("state/active-player.properties"),
                "schema=1\n"
                        + "version=0.1.20\n"
                        + "launcher=app/current/player.exe\n"
                        + "programRoot=app/current\n"
                        + "manifestSha256=not-a-valid-sha256\n"
                        + "timeoutSeconds=3600\n",
                StandardCharsets.UTF_8);

        new PlayerStorageMaintenance(Clock.fixed(NOW, ZoneOffset.UTC))
                .cleanSupersededPrograms(paths);

        assertTrue(Files.exists(oldManifest));
    }

    private Path createFile(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "data", StandardCharsets.UTF_8);
        return path;
    }
}
