package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "project",
        description = "Manage independent modpack projects",
        subcommands = {
                ProjectCreateCommand.class,
                ProjectListCommand.class,
                ProjectShowCommand.class,
                ProjectConfigureCommand.class,
                ProjectCoverCommand.class,
                ProjectBindingCommand.class,
                ProjectScanCommand.class,
                ProjectPublishCommand.class,
                ProjectReleasesCommand.class,
                ProjectRollbackCommand.class
        }
)
final class ProjectCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;

    @Override
    public void run() {
        new CommandLine(this).usage(root.out());
    }
}
