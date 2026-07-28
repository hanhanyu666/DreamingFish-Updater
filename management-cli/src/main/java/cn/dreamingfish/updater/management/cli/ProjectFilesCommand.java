package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.RemovalAction;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
        name = "files",
        description = "List, import, or remove files in the standard source directory"
)
final class ProjectFilesCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0", description = "Project ID")
    String projectId;
    @CommandLine.Option(names = "--import", description = "Import a server-local file")
    Path importFile;
    @CommandLine.Option(names = "--target-directory", defaultValue = "",
            description = "Managed destination directory, for example mods")
    String targetDirectory;
    @CommandLine.Option(names = "--overwrite", description = "Archive and replace an existing source file")
    boolean overwrite;
    @CommandLine.Option(names = "--remove", description = "Remove this managed source path")
    String removePath;
    @CommandLine.Option(names = "--action", description = "Player action: ${COMPLETION-CANDIDATES}")
    RemovalAction action;
    @CommandLine.Option(names = "--yes", description = "Apply a file mutation without confirmation")
    boolean yes;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        if (importFile != null && removePath != null) {
            throw new ManagementException("Use either --import or --remove, not both");
        }
        var service = root.services().sourceFiles();
        if (importFile != null) {
            Confirmations.require(root, yes,
                    "Import " + importFile + " into project " + projectId + "?");
            var result = service.importFile(
                    projectId, importFile, targetDirectory, overwrite);
            printMutation(root, "Imported", result);
            return;
        }
        if (removePath != null) {
            if (action == null) {
                throw new ManagementException("--action DELETE or RELEASE is required with --remove");
            }
            Confirmations.require(root, yes,
                    "Remove " + removePath + " from the source and apply " + action + "?");
            var result = service.remove(projectId, removePath, action);
            printMutation(root, "Removed", result);
            return;
        }
        var files = service.list(projectId);
        if (root.jsonOutput) {
            root.printJson(files);
            return;
        }
        root.out().println("Managed source files: " + files.size());
        files.forEach(file -> root.out().printf("  %-12s %10s  %s%n",
                file.forcedByDirectory() ? "DIR-FORCED"
                        : file.forcedByFile() ? "FILE-FORCED"
                        : file.policy().name(),
                HumanSize.format(file.size()), file.path()));
    }

    private static void printMutation(
            ManagementCli root, String verb,
            cn.dreamingfish.updater.management.SourceFileService.SourceMutation result) {
        if (root.jsonOutput) {
            root.printJson(result);
            return;
        }
        root.out().println(verb + " source file: " + result.path());
        if (result.archivedPreviousFile() != null) {
            root.out().println("Archived previous file: " + result.archivedPreviousFile());
        }
        root.out().println("Publish preview updated: " + result.preview().previewId());
    }
}
