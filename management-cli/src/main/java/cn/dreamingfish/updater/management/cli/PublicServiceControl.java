package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback-only control channel shared by management processes using one data
 * directory. It allows the Web overview to stop a known DreamingFish public
 * service without killing its Java process or touching an unrelated listener.
 */
final class PublicServiceControl implements AutoCloseable {
    private static final String FILE_NAME = "public-http-control.properties";
    private static final String TOKEN_HEADER = "X-DFS-Service-Control";
    private static final int TIMEOUT_MILLIS = 1_500;

    private final Path descriptorPath;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PublicServiceControl(Path descriptorPath, String token,
                                 HttpServer server, ExecutorService executor) {
        this.descriptorPath = descriptorPath;
        this.token = token;
        this.server = server;
        this.executor = executor;
    }

    static PublicServiceControl register(ManagementCli root, String publicHost,
                                         int publicPort, Runnable stopAction) {
        Path descriptorPath = descriptorPath(root);
        String token = randomToken();
        HttpServer controlServer = null;
        ExecutorService executor = null;
        try {
            controlServer = HttpServer.create(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), 0), 0);
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dfs-http-control");
                thread.setDaemon(true);
                return thread;
            });
            PublicServiceControl control = new PublicServiceControl(
                    descriptorPath, token, controlServer, executor);
            controlServer.setExecutor(executor);
            controlServer.createContext("/status", exchange ->
                    control.handleStatus(exchange, publicHost, publicPort));
            controlServer.createContext("/stop", exchange ->
                    control.handleStop(exchange, stopAction));
            controlServer.start();
            writeDescriptor(descriptorPath, new Descriptor(
                    publicHost, publicPort,
                    controlServer.getAddress().getPort(), token));
            return control;
        } catch (IOException | RuntimeException e) {
            if (controlServer != null) controlServer.stop(0);
            if (executor != null) executor.shutdownNow();
            throw new ManagementException(
                    "Unable to register the download service control channel", e);
        }
    }

    static boolean available(ManagementCli root) {
        Descriptor descriptor = matchingDescriptor(root);
        return descriptor != null && request(descriptor, "GET", "/status");
    }

    static boolean requestStop(ManagementCli root) {
        Descriptor descriptor = matchingDescriptor(root);
        if (descriptor == null) return false;
        boolean accepted = request(descriptor, "POST", "/stop");
        if (!accepted) deleteDescriptorIfTokenMatches(
                descriptorPath(root), descriptor.token());
        return accepted;
    }

    private void handleStatus(HttpExchange exchange, String publicHost,
                              int publicPort) throws IOException {
        if (!authorized(exchange) || !exchange.getRequestMethod().equals("GET")) {
            send(exchange, 404, "{}");
            return;
        }
        send(exchange, 200, "{\"status\":\"ok\",\"host\":\""
                + jsonEscape(publicHost) + "\",\"port\":" + publicPort + "}");
    }

    private void handleStop(HttpExchange exchange, Runnable stopAction)
            throws IOException {
        if (!authorized(exchange) || !exchange.getRequestMethod().equals("POST")) {
            send(exchange, 404, "{}");
            return;
        }
        send(exchange, 202, "{\"status\":\"stopping\"}");
        Thread.ofVirtual().name("dfs-http-control-stop").start(() -> {
            try {
                stopAction.run();
            } catch (RuntimeException ignored) {
                // The requesting Web process will report a still-running port.
            }
        });
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        return supplied != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static boolean request(Descriptor descriptor, String method,
                                   String path) {
        HttpURLConnection connection = null;
        try {
            URI uri = new URI("http", null, "127.0.0.1",
                    descriptor.controlPort(), path, null, null);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestMethod(method);
            connection.setRequestProperty(TOKEN_HEADER, descriptor.token());
            connection.setRequestProperty("Connection", "close");
            if (method.equals("POST")) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (input != null) {
                try (input) {
                    input.readNBytes(512);
                }
            }
            return method.equals("POST") ? status == 202 : status == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Descriptor matchingDescriptor(ManagementCli root) {
        Path path = descriptorPath(root);
        Descriptor descriptor = readDescriptor(path);
        if (descriptor == null) return null;
        ManagementSettings settings = root.settings();
        if (descriptor.publicPort() != settings.httpPort()
                || !normalizeHost(descriptor.publicHost())
                .equals(normalizeHost(settings.httpHost()))) {
            return null;
        }
        return descriptor;
    }

    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty() || host.equals("*")) return "0.0.0.0";
        if (host.equals("0:0:0:0:0:0:0:0")) return "::";
        return host;
    }

    private static Path descriptorPath(ManagementCli root) {
        return Path.of(root.settings().dataDirectory())
                .toAbsolutePath().normalize().resolve(FILE_NAME);
    }

    private static void writeDescriptor(Path target, Descriptor descriptor)
            throws IOException {
        Files.createDirectories(target.getParent());
        Properties values = new Properties();
        values.setProperty("schema", "1");
        values.setProperty("publicHost", descriptor.publicHost());
        values.setProperty("publicPort", Integer.toString(descriptor.publicPort()));
        values.setProperty("controlPort", Integer.toString(descriptor.controlPort()));
        values.setProperty("token", descriptor.token());
        Path temporary = Files.createTempFile(
                target.getParent(), ".public-http-control-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "DreamingFish local HTTP service control");
            }
            restrictPermissions(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Descriptor readDescriptor(Path path) {
        if (!Files.isRegularFile(path)) return null;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
            if (!values.getProperty("schema", "").equals("1")) return null;
            String host = values.getProperty("publicHost", "");
            int publicPort = Integer.parseInt(values.getProperty("publicPort"));
            int controlPort = Integer.parseInt(values.getProperty("controlPort"));
            String token = values.getProperty("token", "");
            if (host.isBlank() || token.isBlank()
                    || publicPort < 1 || publicPort > 65535
                    || controlPort < 1 || controlPort > 65535) return null;
            return new Descriptor(host, publicPort, controlPort, token);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs and the management data directory remain the boundary.
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteDescriptorIfTokenMatches(Path path, String token) {
        Descriptor current = readDescriptor(path);
        if (current == null || !MessageDigest.isEqual(
                current.token().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A stale descriptor is harmless and is ignored after the probe fails.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        executor.shutdownNow();
        deleteDescriptorIfTokenMatches(descriptorPath, token);
    }

    private record Descriptor(
            String publicHost, int publicPort, int controlPort, String token) {
    }
}
