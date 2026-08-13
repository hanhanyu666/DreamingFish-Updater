package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ProjectRecord;
import cn.dreamingfish.updater.management.ProjectRules;
import cn.dreamingfish.updater.protocol.Branding;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "create", description = "Create a modpack project and Ed25519 identity")
final class ProjectCreateCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;

    @CommandLine.Parameters(index = "0", description = "Project ID")
    String projectId;

    @CommandLine.Option(names = "--name", required = true, description = "Project display name")
    String name;

    @CommandLine.Option(names = "--source", required = true, description = "Dedicated standard modpack directory")
    Path source;

    @CommandLine.Option(names = "--public-url", required = true, description = "Public HTTP base URL")
    String publicUrl;

    @CommandLine.Option(names = "--subtitle", defaultValue = "Minecraft modpack update")
    String subtitle;

    @CommandLine.Option(names = "--welcome-text", defaultValue = "欢迎来到")
    String welcomeText;

    @CommandLine.Option(names = "--server-address", defaultValue = "")
    String serverAddress;

    @CommandLine.Option(names = "--accent", defaultValue = "#2ee8df")
    String accent;

    @CommandLine.Option(names = "--secondary-accent", defaultValue = "#b06cff")
    String secondaryAccent;

    @CommandLine.Option(names = "--top-bar-color", defaultValue = "#030708")
    String topBarColor;

    @CommandLine.Option(names = "--card-color", defaultValue = "#030708")
    String cardColor;

    @CommandLine.Option(names = "--brand-name", defaultValue = "梦鱼服",
            description = "Chinese brand name shown in the player title bar")
    String brandName;

    @CommandLine.Option(names = "--brand-english-name", defaultValue = "DreamingFish",
            description = "English brand name shown in the player title bar")
    String brandEnglishName;

    @CommandLine.Option(names = "--rules", description = "ProjectRules JSON file")
    Path rulesFile;

    @CommandLine.Option(names = "--force-sync-directories",
            description = "Comma-separated top-level directories to mirror exactly")
    String forcedSyncDirectories;

    @CommandLine.Option(names = "--force-sync-files",
            description = "Comma-separated managed files that players cannot exempt")
    String forcedSyncFiles;

    @CommandLine.Option(names = "--cover", description = "Desktop cover image to import")
    Path cover;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        ManagementCli.Services services = root.services();
        ProjectRules rules = rulesFile == null
                ? ProjectRules.defaults()
                : readRules(services, rulesFile);
        if (forcedSyncDirectories != null) {
            rules = rules.withForcedSyncDirectories(parsePaths(forcedSyncDirectories));
        }
        if (forcedSyncFiles != null) {
            rules = rules.withForcedSyncFiles(parsePaths(forcedSyncFiles));
        }
        Branding branding = new Branding(name, subtitle, serverAddress, null,
                accent, secondaryAccent, brandName, brandEnglishName,
                java.util.List.of(), null, java.util.List.of(), java.util.List.of(),
                welcomeText, topBarColor, cardColor);
        ProjectRecord project = services.projects().create(
                projectId, name, source, publicUrl, branding, rules);
        if (cover != null) {
            project = services.projects().setCover(projectId, cover);
        }
        CliOutput.project(root, project);
    }

    static ProjectRules readRules(ManagementCli.Services services, Path file) {
        try {
            return services.json().read(file, ProjectRules.class);
        } catch (java.io.IOException e) {
            throw new cn.dreamingfish.updater.management.ManagementException("Unable to read rules file " + file, e);
        }
    }

    static java.util.List<String> parsePaths(String input) {
        if (input == null || input.isBlank() || input.trim().equals("-")) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(input.split("[,，]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
