package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "publish", description = "Publish an immutable signed player updater version")
final class PlayerProgramPublishCommand implements Runnable {
    @CommandLine.ParentCommand
    PlayerProgramCommand parent;
    @CommandLine.Parameters(index = "0", description = "Project ID")
    String projectId;
    @CommandLine.Option(names = "--platform", required = true, description = "Platform ID, for example windows-x64")
    String platform;
    @CommandLine.Option(names = "--version", required = true, description = "Semantic program version")
    String version;
    @CommandLine.Option(names = "--source", required = true, description = "Complete player program image directory")
    Path source;
    @CommandLine.Option(names = "--launcher", required = true, description = "Launcher path relative to the source directory")
    String launcher;
    @CommandLine.Option(names = "--minimum-bootstrap-version", defaultValue = "0.1.2")
    String minimumBootstrapVersion;
    @CommandLine.Option(names = "--yes", description = "Publish without interactive confirmation")
    boolean yes;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        Confirmations.require(root, yes,
                "Publish immutable player program " + version + " for " + platform + "?");
        var stored = root.services().playerPrograms().publish(projectId, platform, version,
                source, launcher, minimumBootstrapVersion);
        if (root.jsonOutput) root.printJson(stored);
        else root.out().printf("Published player program %s for %s/%s.%n",
                stored.version(), stored.projectId(), stored.platform());
    }
}
