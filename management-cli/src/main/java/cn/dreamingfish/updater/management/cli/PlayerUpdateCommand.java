package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.*;
import picocli.CommandLine;

@CommandLine.Command(name = "updates", description = "Check and publish official player versions, or pause, resume and automate project updates")
final class PlayerUpdateCommand implements Runnable {
    enum Action { status, check, publish, prepare, approve, pause, resume, auto }
    @CommandLine.ParentCommand PlayerProgramCommand parent;
    @CommandLine.Parameters(index = "0") String projectId;
    @CommandLine.Option(names = "--action", defaultValue = "status") Action action;
    @CommandLine.Option(names = "--platform", defaultValue = "windows-x64") String platform;
    @CommandLine.Option(names = "--version") String version;
    @CommandLine.Option(names = "--sha256", description = "Official archive hash for publish and prepare; project manifest hash for approve") String hash;
    @CommandLine.Option(names = "--revision", defaultValue = "-1") long revision;
    @CommandLine.Option(names = "--enabled", description = "For auto: publish newer official versions automatically") Boolean enabled;

    @Override public void run() {
        var root = parent.root;
        var services = root.services();
        services.database().requireProject(projectId);
        var policies = new PlayerUpdatePolicyService(services.paths(), services.database(), services.json());
        if ((action == Action.publish || action == Action.approve || action == Action.pause || action == Action.resume) && revision < 0)
            throw new ManagementException("请先查询 status，再通过 --revision 提供当前策略修订号");
        if ((action == Action.publish || action == Action.approve || action == Action.prepare) && (version == null || hash == null))
            throw new ManagementException("请提供 --version 和 --sha256");
        if (action == Action.auto && enabled == null) throw new ManagementException("请通过 --enabled true 或 --enabled false 选择是否自动发布");
        var publisher = new OfficialPlayerPublisher(services.paths(), services.database(), services.json());
        Object result = switch (action) {
            case status -> java.util.Map.of("policy", policies.read(projectId), "autoPublish", publisher.auto(projectId));
            case check -> { try (var official = new OfficialPlayerService(services.paths(), services.database(), services.json())) { yield official.check(true); } }
            case publish -> root.tracked(projectId, "official-player-publish", false, () ->
                    publisher.publish(projectId, platform, version, hash, revision, ManagementCli.VERSION));
            case prepare -> root.tracked(projectId, "official-player-prepare", false, () -> {
                try (var official = new OfficialPlayerService(services.paths(), services.database(), services.json())) {
                    return official.prepare(projectId, platform, version, hash, ManagementCli.VERSION);
                }
            });
            case approve -> policies.approve(projectId, platform, version, hash, revision);
            case pause -> policies.setEnabled(projectId, false, revision);
            case resume -> policies.setEnabled(projectId, true, revision);
            case auto -> publisher.setAuto(projectId, enabled);
        };
        root.printJson(result);
    }
}
