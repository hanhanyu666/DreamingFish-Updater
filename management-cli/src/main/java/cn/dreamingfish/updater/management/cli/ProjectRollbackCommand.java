package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.ScanService;
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
    @CommandLine.Option(names = "--confirm-player-upgrade",
            description = "Publish although no player program supporting the maintenance policy is published")
    boolean confirmPlayerUpgrade;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        if (!confirmPlayerUpgrade && !root.services().scanner().policyPlayerPublished(projectId)) {
            throw new ManagementException("回滚发布同样需要玩家端 " + ScanService.POLICY_PLAYER_VERSION
                    + " 或更新版本。请先发布新版玩家端；如果已确认玩家会通过其他方式获得新版玩家端，"
                    + "请加上 --confirm-player-upgrade。");
        }
        Confirmations.require(root, yes, "Publish rollback of " + releaseId + " as " + version + "?");
        var release = root.services().publisher().rollback(projectId, releaseId, version, changelog);
        if (root.jsonOutput) root.printJson(CliOutput.releaseMap(release));
        else root.out().printf("Published rollback %s (sequence %d).%n", release.releaseId(), release.sequence());
    }
}
