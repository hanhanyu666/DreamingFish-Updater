package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProgramServiceTest {
    @TempDir
    Path temporary;

    @Test
    void resolvesEveryCommonExtractedPlayerDirectoryLevel() throws Exception {
        Path outer = Files.createDirectories(temporary.resolve("player-release"));
        Path home = Files.createDirectories(outer.resolve("DreamingFishUpdater"));
        Path program = Files.createDirectories(home.resolve("app/0.1.13"));
        Files.writeString(program.resolve("DreamingFishUpdater.exe"), "launcher");
        Files.createDirectories(program.resolve("app"));
        Files.createDirectories(program.resolve("runtime"));
        Path state = Files.createDirectories(home.resolve("state"));
        Files.writeString(state.resolve("active-player.properties"),
                "schema=1\nversion=0.1.13\n");

        for (Path selected : java.util.List.of(
                outer, home, home.resolve("app"), program)) {
            PlayerProgramService.ResolvedSource resolved =
                    PlayerProgramService.resolveSource(selected, "", "");
            assertEquals(program, resolved.root());
            assertEquals("0.1.13", resolved.version());
            assertEquals("DreamingFishUpdater.exe", resolved.launcher());
        }
    }

    @Test
    void autoPublishesFromTheOuterDirectoryAndUsesAChinesePathError() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        Path outer = Files.createDirectories(temporary.resolve("bundle"));
        Path home = Files.createDirectories(outer.resolve("DreamingFishUpdater"));
        Path program = Files.createDirectories(home.resolve("app/0.2.0"));
        Files.writeString(program.resolve("DreamingFishUpdater.exe"), "launcher");
        Files.createDirectories(program.resolve("app"));
        Files.writeString(program.resolve("app/player-app.jar"), "application");
        Files.createDirectories(program.resolve("runtime"));
        Path state = Files.createDirectories(home.resolve("state"));
        Files.writeString(state.resolve("active-player.properties"),
                "schema=1\nversion=0.2.0\n");

        StoredPlayerProgram stored = new PlayerProgramService(
                fixture.paths, fixture.database, fixture.json)
                .publishAuto("demo", "windows-x64", outer, "0.1.2");
        assertEquals("0.2.0", stored.version());

        Path wrong = Files.createDirectories(temporary.resolve("wrong"));
        ManagementException failure = assertThrows(ManagementException.class,
                () -> PlayerProgramService.resolveSource(wrong, "", ""));
        assertTrue(failure.getMessage().contains("玩家端版本号"));
    }
}
