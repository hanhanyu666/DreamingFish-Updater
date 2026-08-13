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
        configureDedicatedHttpLimits();
        ManagementCli.Services services = root.services();
        PublicFileServer server = new PublicFileServer(
                services.database(), services.objects(), new InetSocketAddress(host, port));
        CountDownLatch stopped = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "dfs-http-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        server.start();
        try (PublicServiceControl ignored = PublicServiceControl.register(
                root, host, port, () -> {
                    server.close();
                    stopped.countDown();
                })) {
            root.out().printf("下载服务已启动：http://%s:%d/%n",
                    server.address().getHostString(), server.address().getPort());
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already in progress.
            }
        }
    }

    private static void configureDedicatedHttpLimits() {
        setDefault("sun.net.httpserver.maxReqTime", "15");
        setDefault("sun.net.httpserver.maxRspTime", "600");
        setDefault("sun.net.httpserver.idleInterval", "15");
        setDefault("sun.net.httpserver.maxIdleConnections", "64");
        setDefault("jdk.httpserver.maxConnections", "256");
        setDefault("sun.net.httpserver.nodelay", "true");
    }

    private static void setDefault(String name, String value) {
        if (System.getProperty(name) == null) System.setProperty(name, value);
    }
}
