package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
        name = "deployment",
        description = "Create a player updater overlay without a complete Minecraft instance"
)
final class ProjectDeploymentCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;
    @CommandLine.Option(names = "--output", required = true,
            description = "Existing parent directory for the generated deployment folder")
    Path outputParent;
    @CommandLine.Option(names = "--release", required = true,
            description = "Release ID represented by the target modpack")
    String releaseId;
    @CommandLine.Option(names = "--platform", defaultValue = "windows-x64")
    String platform;
    @CommandLine.Option(names = "--yes")
    boolean yes;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        Confirmations.require(root, yes,
                "Create a first-deployment overlay for " + projectId
                        + " at " + outputParent + "?");
        var result = root.services().deployments().create(
                projectId, platform, releaseId, outputParent,
                root.bootstrapAgentPath());
        if (root.jsonOutput) root.printJson(result);
        else root.out().println("Created player deployment overlay: "
                + result.outputDirectory());
    }
}
