package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

import java.nio.file.Path;

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
    @CommandLine.Option(names = "--changelog-file",
            description = "Read the changelog from a UTF-8 text file")
    Path changelogFile;
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
        if (changelogFile != null && changelog != null && !changelog.isBlank()) {
            throw new cn.dreamingfish.updater.management.ManagementException(
                    "Use either --changelog or --changelog-file, not both");
        }
        String resolvedChangelog = changelogFile == null
                ? changelog
                : ChangelogInput.utf8File(changelogFile);
        Confirmations.require(root, yes, "Publish immutable release " + version + "?");
        var release = services.publisher().publish(
                projectId, version, minimumPlayerVersion, resolvedChangelog);
        if (root.jsonOutput) root.printJson(CliOutput.releaseMap(release));
        else root.out().printf("Published %s as %s (sequence %d).%n",
                release.displayVersion(), release.releaseId(), release.sequence());
    }
}
