package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ProjectRules;
import cn.dreamingfish.updater.protocol.Branding;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "configure", description = "Update project source, rules, endpoint, or branding")
final class ProjectConfigureCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;

    @CommandLine.Parameters(index = "0")
    String projectId;

    @CommandLine.Option(names = "--source")
    Path source;
    @CommandLine.Option(names = "--public-url")
    String publicUrl;
    @CommandLine.Option(names = "--product-name")
    String productName;
    @CommandLine.Option(names = "--subtitle")
    String subtitle;
    @CommandLine.Option(names = "--server-address")
    String serverAddress;
    @CommandLine.Option(names = "--accent")
    String accent;
    @CommandLine.Option(names = "--secondary-accent")
    String secondaryAccent;
    @CommandLine.Option(names = "--rules")
    Path rulesFile;
    @CommandLine.Option(names = "--force-sync-directories",
            description = "Replace the comma-separated top-level mirror directories")
    String forcedSyncDirectories;
    @CommandLine.Option(names = "--clear-force-sync",
            description = "Disable forced directory sync for this project")
    boolean clearForceSync;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        ManagementCli.Services services = root.services();
        var current = services.database().requireProject(projectId);
        Branding old = current.branding();
        Branding branding = new Branding(
                productName == null ? old.productName() : productName,
                subtitle == null ? old.subtitle() : subtitle,
                serverAddress == null ? old.serverAddress() : serverAddress,
                old.coverObject(),
                accent == null ? old.accentColor() : accent,
                secondaryAccent == null ? old.secondaryAccentColor() : secondaryAccent
        );
        ProjectRules rules = rulesFile == null ? current.rules() : ProjectCreateCommand.readRules(services, rulesFile);
        if (clearForceSync && forcedSyncDirectories != null) {
            throw new IllegalArgumentException(
                    "Use either --clear-force-sync or --force-sync-directories, not both");
        }
        if (clearForceSync) {
            rules = rules.withForcedSyncDirectories(java.util.List.of());
        } else if (forcedSyncDirectories != null) {
            rules = rules.withForcedSyncDirectories(
                    ProjectCreateCommand.parseDirectories(forcedSyncDirectories));
        }
        CliOutput.project(root, services.projects().configure(projectId, source, publicUrl, branding, rules));
    }
}
