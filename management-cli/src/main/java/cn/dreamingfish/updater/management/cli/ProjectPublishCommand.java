package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "publish", description = "Confirm and publish the current preview")
final class ProjectPublishCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;
    @CommandLine.Option(names = "--version", required = true)
    String version;
    @CommandLine.Option(names = "--minimum-player-version", defaultValue = "0.1.0")
    String minimumPlayerVersion;
    @CommandLine.Option(names = "--changelog", defaultValue = "")
    String changelog;
    @CommandLine.Option(names = "--yes", description = "Publish without interactive confirmation")
    boolean yes;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        var services = root.services();
        var preview = services.scanner().load(projectId);
        if (!root.jsonOutput) {
            root.out().printf("About to publish preview %s with %d changes (%s download).%n",
                    preview.previewId(), preview.changes().size(), HumanSize.format(preview.estimatedDownloadBytes()));
        }
        Confirmations.require(root, yes, "Publish immutable release " + version + "?");
        var release = services.publisher().publish(projectId, version, minimumPlayerVersion, changelog);
        if (root.jsonOutput) root.printJson(CliOutput.releaseMap(release));
        else root.out().printf("Published %s as %s (sequence %d).%n",
                release.displayVersion(), release.releaseId(), release.sequence());
    }
}
