package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "cover", description = "Import or replace the project cover image")
final class ProjectCoverCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;
    @CommandLine.Parameters(index = "1")
    Path image;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        CliOutput.project(root, root.services().projects().setCover(projectId, image));
    }
}
