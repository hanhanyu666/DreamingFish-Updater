package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;

final class BackupPasswords {
    private BackupPasswords() {
    }

    static char[] read(String environmentVariable, boolean confirm) {
        String fromEnvironment = System.getenv(environmentVariable);
        if (fromEnvironment != null) return fromEnvironment.toCharArray();
        java.io.Console console = System.console();
        if (console == null) {
            throw new ManagementException("Set backup password in environment variable " + environmentVariable);
        }
        char[] first = console.readPassword("Backup password: ");
        if (!confirm) return first;
        char[] second = console.readPassword("Confirm password: ");
        try {
            if (!java.util.Arrays.equals(first, second)) {
                throw new ManagementException("Backup passwords do not match");
            }
            return first;
        } finally {
            java.util.Arrays.fill(second, '\0');
        }
    }
}
