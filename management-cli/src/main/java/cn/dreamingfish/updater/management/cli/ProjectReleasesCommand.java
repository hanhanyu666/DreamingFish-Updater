package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "releases", description = "List immutable releases")
final class ProjectReleasesCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        var releases = root.services().database().listReleases(projectId);
        if (root.jsonOutput) root.printJson(releases.stream().map(CliOutput::releaseMap).toList());
        else if (releases.isEmpty()) root.out().println("No releases.");
        else releases.forEach(release -> root.out().printf("%6d  %-22s %-16s %s%n",
                    release.sequence(), release.releaseId(), release.displayVersion(), release.createdAt()));
    }
}
