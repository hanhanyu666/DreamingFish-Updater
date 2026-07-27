package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProgramBootstrapperTest {
    @TempDir
    Path temporary;

    @Test
    void preparesAndVerifiesTheInitialSignedPlayerProgram() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        Path playerHome = Files.createDirectories(temporary.resolve("instance/DreamingFishUpdater"));
        Path programRoot = Files.createDirectories(playerHome.resolve("app/0.1.0"));
        Path launcher = programRoot.resolve("player.exe");
        Files.writeString(launcher, "trusted-player", StandardCharsets.UTF_8);
        Path state = Files.createDirectories(playerHome.resolve("state"));
        Files.writeString(state.resolve("active-player.properties"), """
                schema=1
                version=0.1.0
                launcher=app/0.1.0/player.exe
                programRoot=app/0.1.0
                manifestSha256=
                timeoutSeconds=3600
                """, StandardCharsets.UTF_8);

        StoredPlayerProgram published = new PlayerProgramService(
                fixture.paths, fixture.database, fixture.json).publish(
                "demo", "windows-x64", "0.1.0", programRoot, "player.exe", "0.1.0");
        PlayerProgramBootstrapper bootstrapper = new PlayerProgramBootstrapper(
                fixture.paths, fixture.database, fixture.json);
        var prepared = bootstrapper.prepare("demo", "windows-x64", playerHome);

        assertEquals(published.manifestSha256(), prepared.manifestSha256());
        assertTrue(Files.isRegularFile(prepared.manifestPath()));
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(
                state.resolve("active-player.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertEquals(published.manifestSha256(), properties.getProperty("manifestSha256"));

        Files.writeString(launcher, "tampered-player", StandardCharsets.UTF_8);
        assertThrows(ManagementException.class,
                () -> bootstrapper.prepare("demo", "windows-x64", playerHome));
    }
}
