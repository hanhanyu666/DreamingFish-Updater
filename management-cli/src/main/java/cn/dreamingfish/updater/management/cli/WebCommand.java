package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

import java.util.concurrent.CountDownLatch;

@CommandLine.Command(
        name = "web",
        description = "Run the authenticated Web management console"
)
final class WebCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;

    @Override
    public void run() {
        try (AdminWebServer server = new AdminWebServer(root)) {
            Thread shutdown = new Thread(server::close, "dfs-admin-web-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            server.start();
            root.out().println(
                    "Web management console listening on http://" + root.settings().webHost() + ":"
                            + server.address().getPort() + "/");
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
