package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProgramUpdaterTest {
    @TempDir
    Path temporary;

    @Test
    void installsAProgramVersionAndAtomicallySwitchesTheActiveConfiguration() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("instance"));
            Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
            Path oldLauncher = playerHome.resolve("app/0.1.0/player.cmd");
            Files.createDirectories(oldLauncher.getParent());
            Files.writeString(oldLauncher, "old", StandardCharsets.UTF_8);
            ActivePlayerState old = new ActivePlayerState("0.1.0", "app/0.1.0/player.cmd",
                    "app/0.1.0", List.of("--old"), 90);
            ActivePlayerState.activate(playerHome, old, null);

            PlayerProgramFile launcher = server.playerFile("bin/player.cmd", "new-player", true);
            PlayerProgramFile library = server.playerFile("lib/runtime.jar", "runtime", false);
            PlayerProgramManifest manifest = server.playerProgram(
                    "0.2.0", "bin/player.cmd", launcher, library);
            server.servePlayerProgram(manifest);

            PlayerProgramUpdater updater = new PlayerProgramUpdater();
            PlayerProgramUpdateResult installed = updater.checkAndInstall(
                    request(instance, playerHome, server), "0.1.0", "windows-x64", null);

            assertEquals(PlayerProgramUpdateOutcome.INSTALLED_RESTART_REQUIRED, installed.outcome());
            ActivePlayerState active = ActivePlayerState.load(playerHome).orElseThrow();
            assertEquals("0.2.0", active.version());
            assertEquals(cn.dreamingfish.updater.protocol.CryptoSupport.sha256(
                    new cn.dreamingfish.updater.protocol.JsonCodec().write(manifest)),
                    active.manifestSha256());
            assertTrue(active.launcher().endsWith("/bin/player.cmd"));
            assertEquals("new-player", Files.readString(playerHome.resolve(active.launcher())));

            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(
                    playerHome.resolve("state/active-player.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            assertEquals("0.1.0", properties.getProperty("fallbackVersion"));
            assertEquals("app/0.1.0/player.cmd", properties.getProperty("fallbackLauncher"));
            assertEquals("--old", properties.getProperty("fallbackArg.0"));

            PlayerProgramUpdateResult current = updater.checkAndInstall(
                    request(instance, playerHome, server, "0.2.0"),
                    "0.1.0", "windows-x64", null);
            assertEquals(PlayerProgramUpdateOutcome.CURRENT, current.outcome());
            assertEquals(0, current.downloadedBytes());

            PlayerProgramFile changedLauncher = server.playerFile(
                    "bin/player.cmd", "changed-at-the-same-version", true);
            server.servePlayerProgram(server.playerProgram(
                    "0.2.0", "bin/player.cmd", changedLauncher));
            UpdateException changedVersion = assertThrows(UpdateException.class,
                    () -> updater.checkAndInstall(
                            request(instance, playerHome, server, "0.2.0"),
                            "0.1.0", "windows-x64", null));
            assertEquals(UpdateErrorCode.REPLAY_DETECTED, changedVersion.code());
        }
    }

    @Test
    void aRunningFallbackRemainsTheFallbackAfterRepairingTheActiveVersion() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("fallback-repair-instance"));
            Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
            Path fallbackLauncher = playerHome.resolve("app/0.1.0/player.cmd");
            Path damagedLauncher = playerHome.resolve("app/0.2.0/player.cmd");
            Files.createDirectories(fallbackLauncher.getParent());
            Files.createDirectories(damagedLauncher.getParent());
            Files.writeString(fallbackLauncher, "fallback", StandardCharsets.UTF_8);
            Files.writeString(damagedLauncher, "damaged", StandardCharsets.UTF_8);
            PlayerProgramFile launcher = server.playerFile("player.cmd", "repaired", true);
            PlayerProgramManifest repairedManifest = server.playerProgram(
                    "0.2.0", "player.cmd", launcher);
            String acceptedHash = cn.dreamingfish.updater.protocol.CryptoSupport.sha256(
                    new cn.dreamingfish.updater.protocol.JsonCodec().write(repairedManifest));
            Files.createDirectories(playerHome.resolve("state"));
            Files.writeString(playerHome.resolve("state/active-player.properties"),
                    "schema=1\n"
                            + "version=0.2.0\n"
                            + "launcher=app/0.2.0/player.cmd\n"
                            + "programRoot=app/0.2.0\n"
                            + "manifestSha256=" + acceptedHash + "\n"
                            + "fallbackVersion=0.1.0\n"
                            + "fallbackLauncher=app/0.1.0/player.cmd\n"
                            + "fallbackProgramRoot=app/0.1.0\n"
                            + "fallbackManifestSha256="
                            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n"
                            + "timeoutSeconds=90\n",
                    StandardCharsets.UTF_8);

            server.servePlayerProgram(repairedManifest);
            PlayerProgramUpdateResult result = new PlayerProgramUpdater().checkAndInstall(
                    request(instance, playerHome, server, "0.1.0"),
                    "0.1.0", "windows-x64", null);
            assertEquals(PlayerProgramUpdateOutcome.INSTALLED_RESTART_REQUIRED, result.outcome());

            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(
                    playerHome.resolve("state/active-player.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            assertEquals("0.1.0", properties.getProperty("fallbackVersion"));
            assertEquals("app/0.1.0/player.cmd", properties.getProperty("fallbackLauncher"));
            assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    properties.getProperty("fallbackManifestSha256"));
        }
    }

    @Test
    void rejectsManifestAndObjectTamperingWithoutActivatingTheProgram() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("tamper-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            PlayerProgramFile launcher = server.playerFile("player.cmd", "trusted", true);
            server.servePlayerProgram(server.playerProgram("0.2.0", "player.cmd", launcher));

            server.invalidSignature = true;
            UpdateException badSignature = assertThrows(UpdateException.class,
                    () -> new PlayerProgramUpdater().checkAndInstall(
                            request(instance, playerHome, server), "0.1.0", "windows-x64", null));
            assertEquals(UpdateErrorCode.INVALID_SIGNATURE, badSignature.code());
            assertFalse(Files.exists(playerHome.resolve("state/active-player.properties")));

            server.invalidSignature = false;
            server.tamperObject(launcher.sha256(), "tampered");
            UpdateException badObject = assertThrows(UpdateException.class,
                    () -> new PlayerProgramUpdater().checkAndInstall(
                            request(instance, playerHome, server), "0.1.0", "windows-x64", null));
            assertEquals(UpdateErrorCode.HASH_MISMATCH, badObject.code());
            assertFalse(Files.exists(playerHome.resolve("state/active-player.properties")));
        }
    }

    @Test
    void distinguishesAnUnpublishedProgramFromAnUnavailableCheck() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("availability-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            PlayerProgramUpdater updater = new PlayerProgramUpdater();

            PlayerProgramUpdateResult unpublished = updater.checkAndInstall(
                    request(instance, playerHome, server), "0.1.0", "windows-x64", null);
            assertEquals(PlayerProgramUpdateOutcome.NOT_PUBLISHED, unpublished.outcome());

            server.unavailable = true;
            PlayerProgramUpdateResult unavailable = updater.checkAndInstall(
                    request(instance, playerHome, server), "0.1.0", "windows-x64", null);
            assertEquals(PlayerProgramUpdateOutcome.CHECK_UNAVAILABLE, unavailable.outcome());
            assertNotNull(unavailable);
        }
    }

    @Test
    void installsPlayerProgramFromStaticSignatureSidecar() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("static-player-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            PlayerProgramFile launcher = server.playerFile(
                    "player.cmd", "static-player", true);
            server.servePlayerProgram(server.playerProgram(
                    "0.2.0", "player.cmd", launcher));
            server.signatureSidecarsOnly = true;

            PlayerProgramUpdateResult result = new PlayerProgramUpdater().checkAndInstall(
                    request(instance, playerHome, server),
                    "0.1.0", "windows-x64", null);

            assertEquals(PlayerProgramUpdateOutcome.INSTALLED_RESTART_REQUIRED,
                    result.outcome());
        }
    }

    private UpdateRequest request(Path instance, Path playerHome, TestUpdateServer server) {
        return request(instance, playerHome, server, "0.1.0");
    }

    private UpdateRequest request(Path instance, Path playerHome, TestUpdateServer server,
                                  String playerVersion) {
        return UpdateRequest.defaults(instance, playerHome, server.binding(), playerVersion, Set.of());
    }
}
