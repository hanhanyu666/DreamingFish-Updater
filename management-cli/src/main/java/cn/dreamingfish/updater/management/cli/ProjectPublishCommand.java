package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ChangeKind;
import cn.dreamingfish.updater.management.RemovalAction;
import cn.dreamingfish.updater.management.RemovalDecision;
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
    @CommandLine.Option(names = "--removed-files",
            description = "Apply one action to all removed files: DELETE or RELEASE")
    RemovalAction removedFiles;
    @CommandLine.Option(names = "--confirm-player-upgrade",
            description = "Publish although no player program supporting the maintenance policy is published")
    boolean confirmPlayerUpgrade;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        var services = root.services();
        var preview = services.scanner().load(projectId);
        if (removedFiles != null) {
            if (removedFiles == RemovalAction.DELETE_KEEP_SELF_MANAGED) {
                throw new cn.dreamingfish.updater.management.ManagementException(
                        "移除方式只能是 DELETE（移除玩家副本）或 RELEASE（停止维护，留给玩家）");
            }
            var decisions = preview.changes().stream()
                    .filter(change -> change.kind() == ChangeKind.REMOVED)
                    .map(change -> new RemovalDecision(
                            change.path(), removedFiles))
                    .toList();
            if (!decisions.isEmpty()) {
                preview = services.scanner().decideRemovals(
                        projectId, decisions);
            }
        }
        if (!root.jsonOutput) {
            for (var policy : preview.policyChanges()) {
                root.out().println("Policy: " + PolicyText.describe(policy));
            }
            for (var warning : preview.warnings()) {
                root.out().println("Warning: " + PolicyText.describe(warning));
            }
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
        if (preview.requiresPlayerProgramAcknowledgement() && !confirmPlayerUpgrade) {
            throw new cn.dreamingfish.updater.management.ManagementException(
                    "玩家端需要先升级到 " + cn.dreamingfish.updater.management.ScanService.POLICY_PLAYER_VERSION
                            + " 或更新版本才能使用这次发布。请先发布新版玩家端；"
                            + "如果已确认玩家会通过其他方式获得新版玩家端，请加上 --confirm-player-upgrade。");
        }
        Confirmations.require(root, yes, "Publish immutable release " + version + "?");
        var release = services.publisher().publish(
                projectId, version, minimumPlayerVersion, resolvedChangelog,
                preview.previewId(), preview.confirmationDigest());
        if (root.jsonOutput) root.printJson(CliOutput.releaseMap(release));
        else root.out().printf("Published %s as %s (sequence %d).%n",
                release.displayVersion(), release.releaseId(), release.sequence());
    }
}
