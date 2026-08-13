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
    @CommandLine.Option(names = "--name", description = "Replace the project display name")
    String displayName;
    @CommandLine.Option(names = "--public-url")
    String publicUrl;
    @CommandLine.Option(names = "--product-name")
    String productName;
    @CommandLine.Option(names = "--welcome-text")
    String welcomeText;
    @CommandLine.Option(names = "--subtitle")
    String subtitle;
    @CommandLine.Option(names = "--server-address")
    String serverAddress;
    @CommandLine.Option(names = "--accent")
    String accent;
    @CommandLine.Option(names = "--secondary-accent")
    String secondaryAccent;
    @CommandLine.Option(names = "--top-bar-color")
    String topBarColor;
    @CommandLine.Option(names = "--card-color")
    String cardColor;
    @CommandLine.Option(names = "--brand-name")
    String brandName;
    @CommandLine.Option(names = "--brand-english-name")
    String brandEnglishName;
    @CommandLine.Option(names = "--rules")
    Path rulesFile;
    @CommandLine.Option(names = "--force-sync-directories",
            description = "Replace the comma-separated top-level mirror directories")
    String forcedSyncDirectories;
    @CommandLine.Option(names = "--force-sync-files",
            description = "Replace the comma-separated forced managed files")
    String forcedSyncFiles;
    @CommandLine.Option(names = "--clear-force-sync",
            description = "Disable forced directory sync for this project")
    boolean clearForceSync;
    @CommandLine.Option(names = "--clear-force-sync-files",
            description = "Disable forced file sync for this project")
    boolean clearForceSyncFiles;

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
                secondaryAccent == null ? old.secondaryAccentColor() : secondaryAccent,
                brandName == null ? old.brandName() : brandName,
                brandEnglishName == null
                        ? old.brandEnglishName() : brandEnglishName,
                old.newsArticles(), old.customPage(), old.contentPages(), old.musicTracks(),
                welcomeText == null ? old.welcomeText() : welcomeText,
                topBarColor == null ? old.topBarColor() : topBarColor,
                cardColor == null ? old.cardColor() : cardColor
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
                    ProjectCreateCommand.parsePaths(forcedSyncDirectories));
        }
        if (clearForceSyncFiles && forcedSyncFiles != null) {
            throw new IllegalArgumentException(
                    "Use either --clear-force-sync-files or --force-sync-files, not both");
        }
        if (clearForceSyncFiles) {
            rules = rules.withForcedSyncFiles(java.util.List.of());
        } else if (forcedSyncFiles != null) {
            rules = rules.withForcedSyncFiles(
                    ProjectCreateCommand.parsePaths(forcedSyncFiles));
        }
        CliOutput.project(root, services.projects().configure(
                projectId, displayName, source, publicUrl, branding, rules));
    }
}
