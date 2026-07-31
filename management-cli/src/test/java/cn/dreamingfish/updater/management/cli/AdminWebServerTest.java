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
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertTrue(page.body().contains("单文件强制同步"));
            assertTrue(page.body().contains("data-path-kind=\"directory\""));
            assertTrue(!page.body().contains("data-view=\"files\""));
            assertTrue(page.body().contains("整合包文件"));
            assertTrue(page.body().contains("全选当前列表"));
            assertTrue(page.body().indexOf("整合包文件")
                    < page.body().indexOf("单文件强制同步"));
            assertTrue(page.body().indexOf("单文件强制同步")
                    < page.body().indexOf("发布预览"));
            assertEquals("text/html; charset=utf-8", page.headers()
                    .firstValue("Content-Type").orElseThrow());
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
            assertTrue(scanned.body().contains("\"files\""));

            HttpResponse<String> forcedFile = send(
                    base, "/api/projects/web-demo/forced-files", "POST",
                    json.writeString(Map.of(
                            "files", new String[]{"mods/example.jar"})), token);
            assertEquals(200, forcedFile.statusCode(), forcedFile.body());
            assertTrue(forcedFile.body().contains(
                    "\"forcedSyncFiles\":[\"mods/example.jar\"]"));

            String publishBody = json.writeString(Map.of(
                    "displayVersion", "1.0.0",
                    "minimumPlayerVersion", "0.1.13",
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

            Files.writeString(source.resolve("example.jar"), "web-content-v2");
            HttpResponse<String> rescanned = send(
                    base, "/api/projects/web-demo/scan", "POST", "{}", token);
            assertEquals(200, rescanned.statusCode(), rescanned.body());
            HttpResponse<String> republished = send(
                    base, "/api/projects/web-demo/publish", "POST",
                    json.writeString(Map.of(
                            "displayVersion", "1.1.0",
                            "minimumPlayerVersion", "0.1.14",
                            "changelog", "验证 HTTP 服务自动重启"
                    )), token);
            assertEquals(201, republished.statusCode(), republished.body());
            assertTrue(republished.body().contains(
                    "\"publicServiceRestarted\":true"), republished.body());
            assertTrue(republished.body().contains(
                    "\"running\":true"), republished.body());

            HttpResponse<String> refreshedHealth = send(
                    URI.create("http://127.0.0.1:" + publicPort),
                    "/healthz", "GET", null, null);
            assertEquals(200, refreshedHealth.statusCode(), refreshedHealth.body());
            HttpResponse<String> latest = send(
                    URI.create("http://127.0.0.1:" + publicPort),
                    "/v1/projects/web-demo/latest", "GET", null, null);
            assertEquals(200, latest.statusCode(), latest.body());
            assertTrue(latest.body().contains("\"displayVersion\":\"1.1.0\""),
                    latest.body());

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

    @Test
    void renamesProjectsAndManagesSourceFilesThroughTheWebApi() throws Exception {
        ManagementCli root = new ManagementCli(
                temporary.resolve("file-admin/management-settings.json"),
                new StringReader(""));
        Path source = Files.createDirectories(temporary.resolve("file-pack/mods"));
        Files.writeString(source.resolve("example.jar"), "initial");

        try (AdminWebServer server = new AdminWebServer(
                root, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.address().getPort());
            Map<?, ?> session = json.read(send(
                    base, "/api/session", "GET", null, null)
                    .body().getBytes(StandardCharsets.UTF_8), Map.class);
            String token = session.get("token").toString();

            String create = json.writeString(Map.of(
                    "id", "files-demo",
                    "displayName", "����",
                    "sourceDirectory", source.getParent().toString(),
                    "publicBaseUrl", "http://127.0.0.1:8080"
            ));
            assertEquals(201, send(base, "/api/projects", "POST", create, token)
                    .statusCode());

            String rename = json.writeString(Map.of(
                    "displayName", "修复后的项目名"));
            HttpResponse<String> renamed = send(
                    base, "/api/projects/files-demo", "PUT", rename, token);
            assertEquals(200, renamed.statusCode(), renamed.body());
            assertTrue(renamed.body().contains("修复后的项目名"));

            assertEquals(200, send(base,
                    "/api/projects/files-demo/scan", "POST", "{}", token)
                    .statusCode());
            HttpResponse<String> forced = send(base,
                    "/api/projects/files-demo/forced-files", "POST",
                    json.writeString(Map.of(
                            "files", new String[]{"mods/example.jar"})), token);
            assertEquals(200, forced.statusCode(), forced.body());
            assertTrue(forced.body().contains("mods/example.jar"));

            HttpResponse<String> fileList = send(
                    base, "/api/projects/files-demo/files", "GET", null, null);
            assertEquals(200, fileList.statusCode(), fileList.body());
            assertTrue(fileList.body().contains("\"forcedByFile\":true"));

            HttpResponse<String> uploaded = sendBytes(base,
                    "/api/projects/files-demo/files/upload?path=config%2Fnew.toml"
                            + "&refreshPreview=false",
                    "browser-upload".getBytes(StandardCharsets.UTF_8), token);
            assertEquals(201, uploaded.statusCode(), uploaded.body());
            Map<?, ?> uploadResult = json.read(
                    uploaded.body().getBytes(StandardCharsets.UTF_8), Map.class);
            assertNull(uploadResult.get("preview"));
            assertEquals("browser-upload", Files.readString(
                    source.getParent().resolve("config/new.toml")));

            HttpResponse<String> rescanned = send(base,
                    "/api/projects/files-demo/scan", "POST", "{}", token);
            assertEquals(200, rescanned.statusCode(), rescanned.body());
            assertTrue(rescanned.body().contains("config/new.toml"));

            HttpResponse<String> published = send(base,
                    "/api/projects/files-demo/publish", "POST",
                    json.writeString(Map.of(
                            "displayVersion", "1.0",
                            "minimumPlayerVersion", "0.1.13",
                            "changelog", "Web files")), token);
            assertEquals(201, published.statusCode(), published.body());

            HttpResponse<String> removed = send(base,
                    "/api/projects/files-demo/files/remove-batch", "POST",
                    json.writeString(Map.of(
                            "paths", new String[]{
                                    "mods/example.jar", "config/new.toml"},
                            "action", "RELEASE")), token);
            assertEquals(200, removed.statusCode(), removed.body());
            assertTrue(removed.body().contains("\"count\":2"));
            assertTrue(removed.body().contains("\"removalAction\":\"RELEASE\""));
            assertTrue(!Files.exists(source.resolve("example.jar")));
            assertTrue(!Files.exists(source.getParent().resolve("config/new.toml")));

            Path serverFile = temporary.resolve("server-added.jar");
            Files.writeString(serverFile, "server-import");
            HttpResponse<String> imported = send(base,
                    "/api/projects/files-demo/files/import", "POST",
                    json.writeString(Map.of(
                            "sourcePath", serverFile.toString(),
                            "targetDirectory", "mods",
                            "overwrite", false)), token);
            assertEquals(201, imported.statusCode(), imported.body());
            assertEquals("server-import", Files.readString(
                    source.resolve("server-added.jar")));

            HttpResponse<String> script = send(base, "/app.js", "GET", null, null);
            assertTrue(script.body().contains("单文件强制"));
            assertTrue(script.body().contains("bindSourceFiles"));
            assertTrue(script.body().contains("app.sourceFiles?.files"));
            assertTrue(script.body().contains("fileTreeRows"));
            assertTrue(script.body().contains("remove-batch"));
            assertTrue(script.body().contains("本次没有修改"));
        }
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

    private HttpResponse<String> sendBytes(
            URI base, String path, byte[] body, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .header("X-DFS-Token", token)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
