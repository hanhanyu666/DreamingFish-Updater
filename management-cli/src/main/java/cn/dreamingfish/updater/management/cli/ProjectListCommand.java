package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "list", description = "List projects")
final class ProjectListCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        var projects = root.services().projects().list();
        if (root.jsonOutput) {
            root.printJson(projects.stream().map(CliOutput::projectMap).toList());
        } else if (projects.isEmpty()) {
            root.out().println("No projects.");
        } else {
            projects.forEach(project -> root.out().printf("%-24s %s%n", project.id(), project.displayName()));
        }
    }
}
