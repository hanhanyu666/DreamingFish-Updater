package cn.dreamingfish.updater.management.cli;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsIntegrationTest {
    @Test
    void pathPickerUsesATopmostOwnerAndAlwaysDisposesIt() throws Exception {
        Field field = WindowsPathPicker.class.getDeclaredField("SCRIPT");
        field.setAccessible(true);
        String script = (String) field.get(null);

        assertTrue(script.contains("$owner.TopMost = $true"));
        assertTrue(script.contains("ShowDialog($owner)"));
        assertTrue(script.contains("finally"));
        assertTrue(script.contains("$owner.Dispose()"));
    }

    @Test
    void windowsConsoleInstallsANativeControlHandler() {
        if (!System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")) return;
        try (ConsoleInterrupt interrupt = ConsoleInterrupt.install()) {
            assertTrue(interrupt.supported());
        }
    }
}
