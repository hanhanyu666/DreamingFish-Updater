package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "interactive", description = "打开中文交互式管理终端")
final class InteractiveCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;

    @Override
    public void run() {
        root.runInteractive();
    }
}
