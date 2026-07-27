package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "player",
        description = "Publish signed player updater programs",
        subcommands = {
                PlayerProgramPublishCommand.class,
                PlayerProgramListCommand.class
        }
)
final class PlayerProgramCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;

    @Override
    public void run() {
        new CommandLine(this).usage(root.out());
    }
}
