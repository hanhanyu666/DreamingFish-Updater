package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.*;
import picocli.CommandLine;

@CommandLine.Command(name = "pack-root", description = "Configure an explicitly granted outer modpack root")
final class ProjectPackRootCommand implements Runnable {
    @CommandLine.ParentCommand ProjectCommand parent;
    @CommandLine.Parameters(index = "0") String projectId;
    @CommandLine.Option(names = "--relative-root", required = true) String relativeRoot;
    @CommandLine.Option(names = "--expected-id", defaultValue = "") String expectedId;
    @CommandLine.Option(names = "--layout", description = "Folder names from the root down to the instance, * for any, e.g. .minecraft/versions/*") String layout;
    @Override public void run() {
        var services = parent.root.services();
        var project = new PackRootService(services.paths(), services.database()).configure(projectId, relativeRoot, expectedId,
                layout == null || layout.isBlank() ? null : java.util.List.of(layout.trim().replace('\\', '/').split("/")));
        parent.root.printJson(java.util.Map.of("packRoot", project.rules().packRoot(), "sourceDirectory",
                project.sourceDirectory().resolve(project.rules().packRoot().prefix())));
    }
}
