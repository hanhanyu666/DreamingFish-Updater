package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;

final class Confirmations {
    private Confirmations() {
    }

    static void require(ManagementCli root, boolean yes, String prompt) {
        if (yes) return;
        java.io.Console console = System.console();
        if (console == null) {
            throw new ManagementException("Interactive confirmation is unavailable; review the preview and pass --yes explicitly");
        }
        String answer = console.readLine("%s [y/N] ", prompt);
        if (!"y".equalsIgnoreCase(answer) && !"yes".equalsIgnoreCase(answer)) {
            throw new ManagementException("Operation cancelled");
        }
    }
}
