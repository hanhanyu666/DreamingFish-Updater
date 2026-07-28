package cn.dreamingfish.updater.management.cli;

import java.io.IOException;
import java.util.Locale;

final class WindowsConsoleEncoding {
    private WindowsConsoleEncoding() {
    }

    static void enableUtf8() {
        if (!System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")) {
            return;
        }
        try {
            Process process = new ProcessBuilder(
                    "cmd.exe", "/d", "/c", "chcp", "65001")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The adaptive input reader still handles the native code page.
        }
    }
}
