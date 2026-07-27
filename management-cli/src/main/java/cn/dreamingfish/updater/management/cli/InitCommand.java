package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "init", description = "Initialize the management data directory")
final class InitCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;

    @Override
    public void run() {
        ManagementCli.Services services = root.services();
        if (root.jsonOutput) {
            root.printJson(java.util.Map.of(
                    "status", "initialized",
                    "dataDirectory", services.paths().root().toString()
            ));
        } else {
            root.out().println("Initialized management data at " + services.paths().root());
        }
    }
}
