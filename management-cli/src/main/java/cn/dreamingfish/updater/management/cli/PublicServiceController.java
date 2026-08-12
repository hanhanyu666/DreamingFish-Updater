package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.PublicFileServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class PublicServiceController implements AutoCloseable {
    private static final int PROBE_TIMEOUT_MILLIS = 700;
    private static final int PROBE_BODY_LIMIT = 512;

    private final ManagementCli root;
    private PublicFileServer server;

    PublicServiceController(ManagementCli root) {
        this.root = root;
    }

    synchronized Status start() {
        if (server != null) return status();
        Status existing = status();
        if (existing.running()) return existing;
        if (existing.portOccupied()) {
            throw new ManagementException("下载服务端口 "
                    + root.settings().httpPort()
                    + " 已被占用，但现有服务没有正常响应。"
                    + "请先停止占用该端口的进程，或在服务设置中更换端口");
        }
        ManagementSettings settings = root.settings();
        ManagementCli.Services services = root.services();
        PublicFileServer candidate = null;
        try {
            candidate = new PublicFileServer(
                    services.database(), services.objects(),
                    new InetSocketAddress(settings.httpHost(), settings.httpPort()));
            candidate.start();
            server = candidate;
            return status();
        } catch (RuntimeException e) {
            if (candidate != null) candidate.close();
            if (isPortOccupied(settings)) {
                throw new ManagementException("无法启动下载服务：端口 "
                        + settings.httpPort() + " 已被其他进程占用", e);
            }
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

    synchronized Status restartIfRunning() {
        if (server == null) return status();
        stop();
        return start();
    }

    synchronized boolean managedRunning() {
        return server != null;
    }

    synchronized Status status() {
        ManagementSettings settings = root.settings();
        String address = "http://" + settings.httpHost() + ":" + settings.httpPort() + "/";
        if (server != null) {
            return new Status(true, true, true, address, address + "healthz",
                    "由当前 Web 管理端运行，可以在这里停止");
        }
        Probe probe = probe(settings);
        if (probe == Probe.HEALTHY) {
            return new Status(true, false, true, address, address + "healthz",
                    "已识别到由另一个管理端进程启动的下载服务");
        }
        if (probe == Probe.OCCUPIED) {
            return new Status(false, false, true, address, address + "healthz",
                    "端口已被占用，但下载服务没有正常响应");
        }
        return new Status(false, false, false, address, address + "healthz",
                "下载服务尚未启动");
    }

    @Override
    public void close() {
        stop();
    }

    private static Probe probe(ManagementSettings settings) {
        String host = probeHost(settings.httpHost());
        HttpURLConnection connection = null;
        try {
            URI uri = new URI("http", null, host, settings.httpPort(),
                    "/healthz", null, null);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(PROBE_TIMEOUT_MILLIS);
            connection.setReadTimeout(PROBE_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");
            int status = connection.getResponseCode();
            String serverName = connection.getHeaderField("Server");
            InputStream input = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            byte[] body;
            if (input == null) {
                body = new byte[0];
            } else {
                try (input) {
                    body = input.readNBytes(PROBE_BODY_LIMIT);
                }
            }
            String normalized = new String(body, StandardCharsets.UTF_8)
                    .replaceAll("\\s+", "");
            return status == 200
                    && serverName != null
                    && serverName.startsWith("DreamingFishUpdateSystem/")
                    && normalized.contains("\"status\":\"ok\"")
                    ? Probe.HEALTHY : Probe.OCCUPIED;
        } catch (Exception ignored) {
            return isPortOccupied(settings) ? Probe.OCCUPIED : Probe.FREE;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isPortOccupied(ManagementSettings settings) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(
                    probeHost(settings.httpHost()), settings.httpPort()),
                    PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String probeHost(String configured) {
        String host = configured == null ? "" : configured.trim();
        if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("*")) {
            return "127.0.0.1";
        }
        if (host.equals("::") || host.equals("0:0:0:0:0:0:0:0")) {
            return "::1";
        }
        return host;
    }

    private enum Probe {
        FREE,
        HEALTHY,
        OCCUPIED
    }

    record Status(boolean running, boolean managed, boolean portOccupied,
                  String address, String healthCheck, String detail) {
    }
}
