package cn.dreamingfish.updater.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MinecraftLaunchContextTest {
    @Test
    void capturesPlayerAndLauncherInformationFromTheMinecraftProcess() {
        String oldCommand = System.getProperty("sun.java.command");
        String oldBrand = System.getProperty("minecraft.launcher.brand");
        String oldVersion = System.getProperty("minecraft.launcher.version");
        try {
            System.setProperty("sun.java.command",
                    "net.minecraft.client.main.Main --username \"Dream Fish\" "
                            + "--uuid 8667ba71-b85a-4004-af54-457a9734eed7");
            System.setProperty("minecraft.launcher.brand", "PCL2");
            System.setProperty("minecraft.launcher.version", "2.9.4");

            MinecraftLaunchContext context = MinecraftLaunchContext.capture();

            assertEquals("Dream Fish", context.playerName());
            assertEquals("8667ba71-b85a-4004-af54-457a9734eed7", context.playerUuid());
            assertEquals("PCL2", context.launcherBrand());
            assertEquals("2.9.4", context.launcherVersion());
        } finally {
            restore("sun.java.command", oldCommand);
            restore("minecraft.launcher.brand", oldBrand);
            restore("minecraft.launcher.version", oldVersion);
        }
    }

    @Test
    void omitsInvalidOptionalValuesInsteadOfBlockingMinecraft() {
        MinecraftLaunchContext context = new MinecraftLaunchContext(
                "Player", "not-a-uuid", "Launcher\nName", "1.0");
        List<String> command = new ArrayList<String>();

        context.appendTo(command);

        assertEquals(Arrays.asList("--player-name", "Player", "--launcher-version", "1.0"), command);
        assertFalse(command.contains("--player-uuid"));
        assertFalse(command.contains("--launcher-brand"));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
