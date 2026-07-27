package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "backup",
        description = "Create or restore complete encrypted management backups",
        subcommands = {BackupCreateCommand.class, BackupRestoreCommand.class}
)
final class BackupCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;

    @Override
    public void run() {
        new CommandLine(this).usage(root.out());
    }
}
