package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.PublicFileServer;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

@CommandLine.Command(name = "serve", description = "Run the public read-only HTTP artifact service")
final class ServeCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;
    @CommandLine.Option(names = "--host", defaultValue = "0.0.0.0")
    String host;
    @CommandLine.Option(names = "--port", defaultValue = "8080")
    int port;

    @Override
    public void run() {
        ManagementCli.Services services = root.services();
        PublicFileServer server = new PublicFileServer(
                services.database(), services.objects(), new InetSocketAddress(host, port));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "dfs-http-shutdown"));
        server.start();
        root.out().printf("Public file service listening on http://%s:%d/%n",
                server.address().getHostString(), server.address().getPort());
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
        }
    }
}
