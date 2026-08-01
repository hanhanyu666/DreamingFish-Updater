package cn.dreamingfish.updater.management.cli;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsIntegrationTest {
    @Test
    void windowsConsoleInstallsANativeControlHandler() {
        if (!System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")) return;
        try (ConsoleInterrupt interrupt = ConsoleInterrupt.install()) {
            assertTrue(interrupt.supported());
        }
    }
}
