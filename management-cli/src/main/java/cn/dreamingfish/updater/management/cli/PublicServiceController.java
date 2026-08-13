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
    private PublicServiceControl control;

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
            control = PublicServiceControl.register(
                    root, settings.httpHost(), settings.httpPort(),
                    this::stopLocalFromControl);
            return status();
        } catch (RuntimeException e) {
            if (control != null) {
                control.close();
                control = null;
            }
            server = null;
            if (candidate != null) candidate.close();
            if (isPortOccupied(settings)) {
                throw new ManagementException("无法启动下载服务：端口 "
                        + settings.httpPort() + " 已被其他进程占用", e);
            }
            throw e;
        }
    }

    Status stop() {
        Status current;
        synchronized (this) {
            if (server != null) {
                stopLocal();
                return status();
            }
            current = status();
        }
        if (!current.running()) return current;
        if (!current.controllable()) {
            throw new ManagementException(
                    "这个下载服务没有注册安全控制通道。请先在原终端停止它；"
                            + "使用新版管理端重新启动后即可在概览中管理");
        }
        if (!PublicServiceControl.requestStop(root)) {
            throw new ManagementException(
                    "下载服务控制请求失败，请刷新状态后重试");
        }
        waitUntilStopped();
        return status();
    }

    Status restart() {
        Status current = status();
        if (current.running()) stop();
        Status stopped = status();
        if (stopped.portOccupied()) {
            throw new ManagementException(
                    "下载服务停止后端口仍被占用，无法安全重启");
        }
        return start();
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
            return new Status(true, true, control != null, true,
                    address, address + "healthz",
                    "由当前 Web 管理端运行，可以在这里停止");
        }
        Probe probe = probe(settings);
        if (probe == Probe.HEALTHY) {
            boolean controllable = PublicServiceControl.available(root);
            return new Status(true, false, controllable, true,
                    address, address + "healthz",
                    controllable
                            ? "已识别另一个管理端进程启动的下载服务，可以在这里停止或重启"
                            : "已识别另一个管理端进程中的下载服务，但它没有注册新版安全控制通道");
        }
        if (probe == Probe.OCCUPIED) {
            return new Status(false, false, false, true,
                    address, address + "healthz",
                    "端口已被占用，但下载服务没有正常响应");
        }
        return new Status(false, false, false, false,
                address, address + "healthz",
                "下载服务尚未启动");
    }

    @Override
    public void close() {
        synchronized (this) {
            stopLocal();
        }
    }

    private synchronized void stopLocalFromControl() {
        stopLocal();
    }

    private void stopLocal() {
        PublicServiceControl activeControl = control;
        control = null;
        if (activeControl != null) activeControl.close();
        PublicFileServer activeServer = server;
        server = null;
        if (activeServer != null) activeServer.close();
    }

    private void waitUntilStopped() {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (probe(root.settings()) == Probe.FREE) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ManagementException(
                        "等待下载服务停止时被中断", e);
            }
        }
        throw new ManagementException(
                "下载服务已收到停止请求，但端口在 5 秒后仍未释放");
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

    record Status(boolean running, boolean managed, boolean controllable,
                  boolean portOccupied,
                  String address, String healthCheck, String detail) {
    }
}
