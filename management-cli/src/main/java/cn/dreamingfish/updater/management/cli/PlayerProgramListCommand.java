package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "list", description = "List published player updater versions")
final class PlayerProgramListCommand implements Runnable {
    @CommandLine.ParentCommand
    PlayerProgramCommand parent;
    @CommandLine.Parameters(index = "0", description = "Project ID")
    String projectId;
    @CommandLine.Option(names = "--platform", required = true)
    String platform;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        var programs = root.services().playerPrograms().list(projectId, platform);
        if (root.jsonOutput) {
            root.printJson(programs);
        } else if (programs.isEmpty()) {
            root.out().println("No player programs have been published.");
        } else {
            for (var program : programs) {
                root.out().printf("%s  %s  %s%n", program.version(), program.platform(), program.createdAt());
            }
        }
    }
}
