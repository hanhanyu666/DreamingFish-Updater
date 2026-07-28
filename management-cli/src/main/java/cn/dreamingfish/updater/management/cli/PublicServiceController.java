package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.PublicFileServer;

import java.net.InetSocketAddress;

final class PublicServiceController implements AutoCloseable {
    private final ManagementCli root;
    private PublicFileServer server;

    PublicServiceController(ManagementCli root) {
        this.root = root;
    }

    synchronized Status start() {
        if (server != null) return status();
        ManagementSettings settings = root.settings();
        ManagementCli.Services services = root.services();
        PublicFileServer candidate = new PublicFileServer(
                services.database(), services.objects(),
                new InetSocketAddress(settings.httpHost(), settings.httpPort()));
        try {
            candidate.start();
            server = candidate;
            return status();
        } catch (RuntimeException e) {
            candidate.close();
            throw e;
        }
    }

    synchronized Status stop() {
        if (server != null) {
            server.close();
            server = null;
        }
        return status();
    }

    synchronized boolean running() {
        return server != null;
    }

    synchronized Status status() {
        ManagementSettings settings = root.settings();
        String address = "http://" + settings.httpHost() + ":" + settings.httpPort() + "/";
        return new Status(server != null, address, address + "healthz");
    }

    @Override
    public void close() {
        stop();
    }

    record Status(boolean running, String address, String healthCheck) {
    }
}
