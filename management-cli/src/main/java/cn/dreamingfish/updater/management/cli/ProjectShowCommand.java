package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "show", description = "Show project configuration")
final class ProjectShowCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;

    @CommandLine.Parameters(index = "0")
    String projectId;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        CliOutput.project(root, root.services().database().requireProject(projectId));
    }
}
