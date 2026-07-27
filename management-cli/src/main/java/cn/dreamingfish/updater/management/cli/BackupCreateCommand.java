package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Arrays;

@CommandLine.Command(name = "create", description = "Create a complete encrypted backup")
final class BackupCreateCommand implements Runnable {
    @CommandLine.ParentCommand
    BackupCommand parent;
    @CommandLine.Option(names = "--output", required = true)
    Path output;
    @CommandLine.Option(names = "--password-env", defaultValue = "DFS_BACKUP_PASSWORD")
    String passwordEnvironment;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        char[] password = BackupPasswords.read(passwordEnvironment, true);
        try {
            Path archive = root.services().backups().create(output, password);
            if (root.jsonOutput) root.printJson(java.util.Map.of("archive", archive.toString()));
            else root.out().println("Created encrypted backup " + archive);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
