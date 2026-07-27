package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.PublishPreview;
import picocli.CommandLine;

@CommandLine.Command(name = "scan", description = "Scan the standard modpack directory and persist a publish preview")
final class ProjectScanCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        PublishPreview preview = root.services().scanner().createPreview(projectId);
        if (root.jsonOutput) {
            root.printJson(preview);
            return;
        }
        root.out().printf("Preview %s: %d managed files, %d changes, %s download%n",
                preview.previewId(), preview.files().size(), preview.changes().size(),
                HumanSize.format(preview.estimatedDownloadBytes()));
        for (var change : preview.changes()) {
            root.out().printf("  %-14s %s", change.kind(), change.path());
            if (change.downloadSize() > 0) root.out().printf("  (%s)", HumanSize.format(change.downloadSize()));
            root.out().println();
        }
    }
}
