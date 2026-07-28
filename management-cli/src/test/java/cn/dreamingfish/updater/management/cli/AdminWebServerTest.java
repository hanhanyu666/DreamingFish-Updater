package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.protocol.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminWebServerTest {
    @TempDir
    Path temporary;

    private final JsonCodec json = new JsonCodec();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void servesSecuredAssetsAndCompletesThePublishWorkflow() throws Exception {
        ManagementCli root = new ManagementCli(
                temporary.resolve("admin/management-settings.json"),
                new StringReader(""));
        int publicPort = availablePort();
        root.saveSettings(root.settings().withHttp("127.0.0.1", publicPort));
        Path source = Files.createDirectories(temporary.resolve("pack/mods"));
        Files.writeString(source.resolve("example.jar"), "web-content");

        try (AdminWebServer server = new AdminWebServer(
                root, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.address().getPort());

            HttpResponse<String> page = send(base, "/", "GET", null, null);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("梦鱼更新管理"));
            assertEquals("DENY", page.headers()
                    .firstValue("X-Frame-Options").orElseThrow());
            assertTrue(page.headers().firstValue("Content-Security-Policy")
                    .orElseThrow().contains("frame-ancestors 'none'"));

            HttpResponse<String> denied = send(
                    base, "/api/projects", "POST", "{}", null);
            assertEquals(403, denied.statusCode());
            assertTrue(denied.body().contains("invalid_session"));

            Map<?, ?> session = json.read(send(
                    base, "/api/session", "GET", null, null)
                    .body().getBytes(StandardCharsets.UTF_8), Map.class);
            String token = session.get("token").toString();

            String createBody = json.writeString(Map.of(
                    "id", "web-demo",
                    "displayName", "Web Demo",
                    "sourceDirectory", source.getParent().toString(),
                    "publicBaseUrl", "http://127.0.0.1:8080",
                    "forcedSyncDirectories", new String[]{"mods"}
            ));
            HttpResponse<String> created = send(
                    base, "/api/projects", "POST", createBody, token);
            assertEquals(201, created.statusCode(), created.body());
            assertTrue(created.body().contains("\"id\":\"web-demo\""));

            HttpResponse<String> scanned = send(
                    base, "/api/projects/web-demo/scan", "POST", "{}", token);
            assertEquals(200, scanned.statusCode(), scanned.body());
            assertTrue(scanned.body().contains("\"path\":\"mods/example.jar\""));

            String publishBody = json.writeString(Map.of(
                    "displayVersion", "1.0.0",
                    "minimumPlayerVersion", "0.1.12",
                    "changelog", "Web 管理端首次发布"
            ));
            HttpResponse<String> published = send(
                    base, "/api/projects/web-demo/publish",
                    "POST", publishBody, token);
            assertEquals(201, published.statusCode(), published.body());
            assertTrue(published.body().contains("\"displayVersion\":\"1.0.0\""));

            HttpResponse<String> details = send(
                    base, "/api/projects/web-demo", "GET", null, null);
            assertEquals(200, details.statusCode(), details.body());
            assertTrue(details.body().contains("Web 管理端首次发布"));

            HttpResponse<String> started = send(
                    base, "/api/public-service/start", "POST", "{}", token);
            assertEquals(200, started.statusCode(), started.body());
            assertTrue(started.body().contains("\"running\":true"));
            HttpResponse<String> health = send(
                    URI.create("http://127.0.0.1:" + publicPort),
                    "/healthz", "GET", null, null);
            assertEquals(200, health.statusCode(), health.body());

            HttpResponse<String> stopped = send(
                    base, "/api/public-service/stop", "POST", "{}", token);
            assertEquals(200, stopped.statusCode(), stopped.body());
            assertTrue(stopped.body().contains("\"running\":false"));
        }
    }

    @Test
    void refusesNonLoopbackManagementBinding() {
        ManagementCli root = new ManagementCli(
                temporary.resolve("admin/management-settings.json"),
                new StringReader(""));

        assertThrows(RuntimeException.class, () -> new AdminWebServer(
                root, new InetSocketAddress("0.0.0.0", 0)));
    }

    private HttpResponse<String> send(
            URI base, String path, String method, String body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (token != null) builder.header("X-DFS-Token", token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
