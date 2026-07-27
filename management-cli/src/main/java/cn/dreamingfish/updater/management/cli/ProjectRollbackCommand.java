package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "rollback", description = "Publish an old desired state as a new release")
final class ProjectRollbackCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;
    @CommandLine.Parameters(index = "1", description = "Historical release ID")
    String releaseId;
    @CommandLine.Option(names = "--version", required = true)
    String version;
    @CommandLine.Option(names = "--changelog")
    String changelog;
    @CommandLine.Option(names = "--yes")
    boolean yes;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        Confirmations.require(root, yes, "Publish rollback of " + releaseId + " as " + version + "?");
        var release = root.services().publisher().rollback(projectId, releaseId, version, changelog);
        if (root.jsonOutput) root.printJson(CliOutput.releaseMap(release));
        else root.out().printf("Published rollback %s (sequence %d).%n", release.releaseId(), release.sequence());
    }
}
