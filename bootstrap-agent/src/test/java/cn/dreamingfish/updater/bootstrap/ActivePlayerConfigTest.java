package cn.dreamingfish.updater.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivePlayerConfigTest {
    @TempDir
    Path temporary;

    @Test
    void buildsThePlayerCommandWithoutShellParsing() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("player home"));
        Path launcher = home.resolve("app/current/player.exe");
        Files.createDirectories(launcher.getParent());
        Files.write(launcher, new byte[]{0});
        Path state = Files.createDirectories(home.resolve("state"));
        Files.write(state.resolve("active-player.properties"), (
                "schema=1\n"
                        + "version=0.1.0\n"
                        + "launcher=app/current/player.exe\n"
                        + "arg.0=--quiet\n"
                        + "timeoutSeconds=90\n").getBytes(StandardCharsets.UTF_8));

        ActivePlayerConfig config = ActivePlayerConfig.load(home);
        Path instance = temporary.resolve("instance");
        Path binding = instance.resolve(".dreamingfish-bootstrap/project-binding.json");
        MinecraftLaunchContext launchContext = new MinecraftLaunchContext(
                "Hanyu", "8667ba71b85a4004af54457a9734eed7", "PCL2", "2.9.4");
        List<String> command = config.command(12345, "token", instance, binding, launchContext);

        assertEquals(launcher.toAbsolutePath().normalize().toString(), command.get(0));
        assertEquals("--quiet", command.get(1));
        assertEquals("--bootstrap-port", command.get(2));
        assertEquals("12345", command.get(3));
        assertEquals("token", command.get(5));
        assertEquals("Hanyu", command.get(command.indexOf("--player-name") + 1));
        assertEquals("PCL2", command.get(command.indexOf("--launcher-brand") + 1));
        assertEquals(90_000L, config.timeoutMillis());
    }
}
