package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
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
import java.util.HashMap;
import java.util.Base64;
import java.util.List;
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
        Path cover = temporary.resolve("cover.png");
        Files.write(cover, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
                        + "AAAAC0lEQVR42mP8/x8AAusB9Y9Zl1EAAAAASUVORK5CYII="));

        try (AdminWebServer server = new AdminWebServer(
                root, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.address().getPort());

            HttpResponse<String> page = send(base, "/", "GET", null, null);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("梦鱼更新管理"));
            assertTrue(page.body().contains("管理文件"));
            assertTrue(page.body().contains("data-view=\"personalization\""));
            assertTrue(page.body().contains("玩家端预览"));
            assertTrue(page.body().contains("id=\"personalization-form\""));
            assertTrue(page.body().contains("id=\"player-preview-stage\""));
            assertTrue(page.body().contains("player-pages-toolbar"));
            assertTrue(page.body().contains("id=\"cover-upload-input\""));
            assertTrue(page.body().contains("从当前电脑上传背景图"));
            assertTrue(page.body().contains("从管理端所在电脑 / 服务器导入"));
            assertTrue(page.body().contains("name=\"welcomeText\""));
            assertTrue(page.body().contains("name=\"titleColor\""));
            assertTrue(page.body().contains("name=\"topBarColor\""));
            assertTrue(page.body().contains("name=\"topBarOpacity\""));
            assertTrue(page.body().contains("name=\"topBarOpacityNumber\""));
            assertTrue(page.body().contains("name=\"cardColor\""));
            assertTrue(page.body().contains("id=\"reset-theme-colors\""));
            assertTrue(page.body().contains("恢复默认配色"));
            assertTrue(page.body().contains("id=\"import-player-pages-server\""));
            assertTrue(page.body().contains("id=\"create-source-folder\""));
            assertTrue(page.body().contains("id=\"service-restart\""));
            assertTrue(page.body().contains("管理强制同步目录"));
            assertTrue(page.body().contains("单文件强制同步"));
            assertTrue(page.body().contains("id=\"error-dialog\""));
            assertTrue(page.body().contains("操作失败"));
            assertTrue(page.body().contains("id=\"path-browser-dialog\""));
            assertTrue(page.body().contains("管理端所在服务器中的文件"));
            assertTrue(page.body().contains("data-path-kind=\"directory\""));
            assertTrue(page.body().contains("id=\"source-target-tree\""));
            assertTrue(page.body().contains("选择文件保存位置"));
            assertTrue(page.body().contains("或者从管理端所在的服务器本身导入"));
            assertTrue(page.body().contains("name=\"brandName\""));
            assertTrue(page.body().contains("name=\"brandEnglishName\""));
            assertTrue(page.body().contains("name=\"productName\""));
            assertTrue(page.body().contains("name=\"subtitle\""));
            assertTrue(page.body().contains("创建必备设置"));
            assertTrue(page.body().contains("玩家端个性化"));
            assertTrue(page.body().contains("data-view=\"distribution\""));
            assertTrue(page.body().contains("id=\"distribution-form\""));
            assertTrue(page.body().contains("id=\"webdav-upload-form\""));
            assertTrue(page.body().contains("id=\"s3-upload-form\""));
            assertTrue(page.body().contains("Secret Access Key"));
            assertTrue(page.body().contains("普通 HTTP、对象存储（OSS）和 CDN"));
            assertTrue(page.body().contains("显示在玩家端首页左侧的大号标题区域"));
            assertTrue(page.body().contains("显示在玩家端首页主标题下方"));
            assertTrue(!page.body().contains("name=\"targetDirectory\""));
            assertTrue(!page.body().contains("data-view=\"files\""));
            assertTrue(page.body().contains("整合包文件"));
            assertTrue(page.body().contains("全选当前列表"));
            assertTrue(page.body().indexOf("整合包文件")
                    < page.body().indexOf("管理强制同步目录"));
            assertTrue(page.body().indexOf("管理强制同步目录")
                    < page.body().indexOf("单文件强制同步"));
            assertTrue(page.body().indexOf("单文件强制同步")
                    < page.body().indexOf("发布预览"));
            assertEquals("text/html; charset=utf-8", page.headers()
                    .firstValue("Content-Type").orElseThrow());
            assertEquals("DENY", page.headers()
                    .firstValue("X-Frame-Options").orElseThrow());
            assertTrue(page.headers().firstValue("Content-Security-Policy")
                    .orElseThrow().contains("frame-ancestors 'none'"));

            HttpResponse<String> playerPreview = send(
                    base, "/player-preview/index.html?adminPreview=1",
                    "GET", null, null);
            assertEquals(200, playerPreview.statusCode(), playerPreview.body());
            assertEquals("SAMEORIGIN", playerPreview.headers()
                    .firstValue("X-Frame-Options").orElseThrow());
            assertTrue(playerPreview.body().contains("type=\"module\""));

            HttpResponse<String> denied = send(
                    base, "/api/projects", "POST", "{}", null);
            assertEquals(403, denied.statusCode());
            assertTrue(denied.body().contains("invalid_session"));

            Map<?, ?> session = json.read(send(
                    base, "/api/session", "GET", null, null)
                    .body().getBytes(StandardCharsets.UTF_8), Map.class);
            String token = session.get("token").toString();

            HttpResponse<String> browsed = send(
                    base, "/api/system/browse-path", "POST",
                    json.writeString(Map.of(
                            "kind", "directory",
                            "path", temporary.toString())), token);
            assertEquals(200, browsed.statusCode(), browsed.body());
            assertTrue(browsed.body().contains("\"currentPath\""));
            assertTrue(browsed.body().contains("\"name\":\"pack\""));
            assertTrue(browsed.body().contains("\"directory\":true"));

            String createBody = json.writeString(Map.of(
                    "id", "web-demo",
                    "displayName", "Web Demo",
                    "sourceDirectory", source.getParent().toString(),
                    "publicBaseUrl", "http://127.0.0.1:8080",
                    "forcedSyncDirectories", new String[]{"mods"},
                    "productName", "星河主标题",
                    "subtitle", "星河副标题",
                    "brandName", "星河服",
                    "brandEnglishName", "StarRiver",
                    "coverPath", cover.toString()
            ));
            HttpResponse<String> created = send(
                    base, "/api/projects", "POST", createBody, token);
            assertEquals(201, created.statusCode(), created.body());
            assertTrue(created.body().contains("\"id\":\"web-demo\""));
            assertTrue(created.body().contains("\"brandName\":\"星河服\""));
            assertTrue(created.body().contains(
                    "\"brandEnglishName\":\"StarRiver\""));
            assertTrue(created.body().contains(
                    "\"productName\":\"星河主标题\""));
            assertTrue(created.body().contains(
                    "\"subtitle\":\"星河副标题\""));

            HttpResponse<String> createdDirectory = send(
                    base, "/api/projects/web-demo/files/directory", "POST",
                    json.writeString(Map.of("path", "resourcepacks/seasonal")), token);
            assertEquals(201, createdDirectory.statusCode(), createdDirectory.body());
            assertTrue(createdDirectory.body().contains("resourcepacks/seasonal"));
            assertTrue(Files.isDirectory(
                    source.getParent().resolve("resourcepacks/seasonal")));

            HttpResponse<String> coverPreview = send(
                    base, "/api/projects/web-demo/cover", "GET", null, null);
            assertEquals(200, coverPreview.statusCode(), coverPreview.body());
            assertEquals("image/png", coverPreview.headers()
                    .firstValue("Content-Type").orElseThrow());

            HttpResponse<String> invalidCoverUpload = sendBytes(
                    base, "/api/projects/web-demo/cover",
                    "not-an-image".getBytes(StandardCharsets.UTF_8), token);
            assertEquals(415, invalidCoverUpload.statusCode(),
                    invalidCoverUpload.body());
            Path uploadTemp = Path.of(root.settings().dataDirectory()).resolve("tmp");
            try (var temporaryFiles = Files.list(uploadTemp)) {
                assertEquals(0, temporaryFiles.count(),
                        "失败的背景上传不应留下临时文件");
            }

            HttpResponse<String> uploadedCover = sendBytes(
                    base, "/api/projects/web-demo/cover",
                    Files.readAllBytes(cover), token);
            assertEquals(200, uploadedCover.statusCode(), uploadedCover.body());
            assertTrue(uploadedCover.body().contains("\"coverObject\""));

            HttpResponse<String> importedCover = send(
                    base, "/api/projects/web-demo/cover/import", "POST",
                    json.writeString(Map.of("sourcePath", cover.toString())), token);
            assertEquals(200, importedCover.statusCode(), importedCover.body());
            assertTrue(importedCover.body().contains("\"coverObject\""));

            Path pageConfig = temporary.resolve("player-pages.json");
            Files.writeString(pageConfig, json.writeString(Map.of(
                    "schemaVersion", 1,
                    "description", "test",
                    "pages", List.of(Map.of(
                            "id", "rules",
                            "navigationLabel", "规则",
                            "announcementPage", false,
                            "eyebrow", "RULES",
                            "title", "服务器规则",
                            "lead", "请先阅读",
                            "markdown", "普通正文草稿",
                            "articles", List.of(Map.of(
                                    "id", "retained-news",
                                    "title", "保留的公告草稿",
                                    "summary", "",
                                    "publishedOn", "2026-08-14",
                                    "coverUrl", "",
                                    "markdown", "公告正文草稿")))))));
            HttpResponse<String> importedPages = send(
                    base, "/api/system/import-player-pages", "POST",
                    json.writeString(Map.of("sourcePath", pageConfig.toString())), token);
            assertEquals(200, importedPages.statusCode(), importedPages.body());
            assertTrue(importedPages.body().contains("普通正文草稿"));
            assertTrue(importedPages.body().contains("保留的公告草稿"));

            HttpResponse<String> personalized = send(
                    base, "/api/projects/web-demo", "PUT",
                    json.writeString(Map.ofEntries(
                            Map.entry("productName", "星河新主页"),
                            Map.entry("subtitle", "新的玩家端副标题"),
                            Map.entry("brandName", "新星河服"),
                            Map.entry("brandEnglishName", "NewStarRiver"),
                            Map.entry("serverAddress", "play.example.com:25565"),
                            Map.entry("accentColor", "#112233"),
                            Map.entry("secondaryAccentColor", "#445566"),
                            Map.entry("titleColor", "#f0e1c2"),
                            Map.entry("welcomeText", "欢迎进入星河"),
                            Map.entry("topBarColor", "#102030"),
                            Map.entry("topBarOpacity", 0.35d),
                            Map.entry("cardColor", "#203040"),
                            Map.entry("newsArticles", List.of(Map.of(
                                    "id", "welcome",
                                    "title", "欢迎来到星河服",
                                    "summary", "第一条玩家端新闻",
                                    "publishedOn", "2026-08-04",
                                    "coverUrl", "https://example.com/cover.jpg",
                                    "markdown", "# 欢迎\n正文"))),
                            Map.entry("customPage", Map.of(
                                    "enabled", true,
                                    "navigationLabel", "玩法介绍",
                                    "eyebrow", "GUIDE",
                                    "title", "从这里开始",
                                    "lead", "先看看这几件事",
                                    "markdown", "- 安装整合包"))
                    )), token);
            assertEquals(200, personalized.statusCode(), personalized.body());
            assertTrue(personalized.body().contains("\"productName\":\"星河新主页\""));
            assertTrue(personalized.body().contains("\"brandName\":\"新星河服\""));
            assertTrue(personalized.body().contains("\"welcomeText\":\"欢迎进入星河\""));
            assertTrue(personalized.body().contains("\"titleColor\":\"#f0e1c2\""));
            assertTrue(personalized.body().contains("\"topBarColor\":\"#102030\""));
            assertTrue(personalized.body().contains("\"topBarOpacity\":0.35"));
            assertTrue(personalized.body().contains("\"cardColor\":\"#203040\""));
            assertTrue(personalized.body().contains("\"title\":\"欢迎来到星河服\""));
            assertTrue(personalized.body().contains("\"navigationLabel\":\"玩法介绍\""));
            assertTrue(personalized.body().contains(source.getParent().toString()
                    .replace("\\", "\\\\")));

            HttpResponse<String> scanned = send(
                    base, "/api/projects/web-demo/scan", "POST", "{}", token);
            assertEquals(200, scanned.statusCode(), scanned.body());
            assertTrue(scanned.body().contains("\"path\":\"mods/example.jar\""));
            assertTrue(scanned.body().contains("\"files\""));

            HttpResponse<String> forcedDirectory = send(
                    base, "/api/projects/web-demo/forced-directories", "POST",
                    json.writeString(Map.of(
                            "directories", new String[]{"mods"})), token);
            assertEquals(200, forcedDirectory.statusCode(), forcedDirectory.body());
            assertTrue(forcedDirectory.body().contains(
                    "\"forcedSyncDirectories\":[\"mods\"]"));

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

            Path staticOutput = temporary.resolve("web-static-output");
            HttpResponse<String> exported = send(
                    base, "/api/projects/web-demo/distribution-export", "POST",
                    json.writeString(Map.of(
                            "outputDirectory", staticOutput.toString())), token);
            assertEquals(201, exported.statusCode(), exported.body());
            assertTrue(exported.body().contains("\"releaseCount\":1"));
            assertTrue(exported.body().contains("\"copiedObjectCount\""));
            assertTrue(Files.isRegularFile(
                    staticOutput.resolve("v1/projects/web-demo/latest")));
            assertTrue(Files.isRegularFile(
                    staticOutput.resolve("v1/projects/web-demo/latest.sig")));

            HttpResponse<String> insecureWebDav = send(
                    base, "/api/projects/web-demo/distribution-webdav", "POST",
                    json.writeString(Map.of(
                            "outputDirectory", staticOutput.toString(),
                            "baseUrl", "http://example.com/dav/",
                            "username", "admin",
                            "password", "do-not-leak-this-password",
                            "exportFirst", false)), token);
            assertEquals(400, insecureWebDav.statusCode(), insecureWebDav.body());
            assertTrue(insecureWebDav.body().contains("HTTPS"));
            assertTrue(!insecureWebDav.body().contains("do-not-leak-this-password"));

            HttpResponse<String> insecureS3 = send(
                    base, "/api/projects/web-demo/distribution-s3", "POST",
                    json.writeString(Map.ofEntries(
                            Map.entry("outputDirectory", staticOutput.toString()),
                            Map.entry("endpoint", "http://example.com/"),
                            Map.entry("region", "auto"),
                            Map.entry("bucket", "demo-bucket"),
                            Map.entry("prefix", "updater"),
                            Map.entry("accessKeyId", "access"),
                            Map.entry("secretAccessKey", "do-not-leak-this-secret"),
                            Map.entry("addressingStyle", "PATH"),
                            Map.entry("exportFirst", false))), token);
            assertEquals(400, insecureS3.statusCode(), insecureS3.body());
            assertTrue(insecureS3.body().contains("HTTPS"));
            assertTrue(!insecureS3.body().contains("do-not-leak-this-secret"));

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
            assertEquals("1.1.0", json.read(
                    latest.body().getBytes(StandardCharsets.UTF_8),
                    ReleaseManifest.class).displayVersion());

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
    void registersLocallyAndProtectsRemoteAccessWithHttpsSessions()
            throws Exception {
        Path admin = temporary.resolve("secure-admin");
        ManagementCli root = new ManagementCli(
                admin.resolve("management-settings.json"),
                new StringReader(""));

        try (AdminWebServer server = new AdminWebServer(
                root, new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0))) {
            server.start();
            URI base = URI.create(
                    "http://127.0.0.1:" + server.address().getPort());
            String credentials = json.writeString(Map.of(
                    "username", "server_admin",
                    "password", "correct horse battery",
                    "confirmPassword", "correct horse battery",
                    "allowLocalBypass", false));

            HttpResponse<String> remoteRegistration = send(
                    base, "/api/auth/register", "POST", credentials, null,
                    Map.of(
                            "X-Forwarded-For", "203.0.113.4",
                            "X-Forwarded-Proto", "https"));
            assertEquals(403, remoteRegistration.statusCode());
            assertTrue(remoteRegistration.body().contains("local_only"));

            HttpResponse<String> registration = send(
                    base, "/api/auth/register", "POST", credentials, null);
            assertEquals(201, registration.statusCode(), registration.body());
            String localCookie = sessionCookie(registration);
            String localSetCookie = registration.headers()
                    .firstValue("Set-Cookie").orElseThrow();
            assertTrue(localSetCookie.contains("HttpOnly"));
            assertTrue(localSetCookie.contains("SameSite=Strict"));
            assertTrue(!localSetCookie.contains("Secure"));
            String persisted = Files.readString(
                    admin.resolve("management-web-auth.json"));
            assertTrue(!persisted.contains("correct horse battery"));
            assertTrue(persisted.contains("600000"));

            HttpResponse<String> localWithoutSession = send(
                    base, "/api/session", "GET", null, null);
            assertEquals(401, localWithoutSession.statusCode());
            HttpResponse<String> localSession = send(
                    base, "/api/session", "GET", null, null,
                    Map.of("Cookie", localCookie));
            assertEquals(200, localSession.statusCode(), localSession.body());

            String wrongCredentials = json.writeString(Map.of(
                    "username", "server_admin",
                    "password", "definitely incorrect"));
            Map<String, String> limitedRemote = Map.of(
                    "X-Forwarded-For", "203.0.113.8",
                    "X-Forwarded-Proto", "https");
            for (int attempt = 0; attempt < 5; attempt++) {
                assertEquals(401, send(base, "/api/auth/login", "POST",
                        wrongCredentials, null, limitedRemote).statusCode());
            }
            assertEquals(429, send(base, "/api/auth/login", "POST",
                    wrongCredentials, null, limitedRemote).statusCode());

            String loginBody = json.writeString(Map.of(
                    "username", "server_admin",
                    "password", "correct horse battery"));
            HttpResponse<String> remoteHttp = send(
                    base, "/api/auth/login", "POST", loginBody, null,
                    Map.of("X-Forwarded-For", "203.0.113.9"));
            assertEquals(400, remoteHttp.statusCode());
            assertTrue(remoteHttp.body().contains("https_required"));

            HttpResponse<String> spoofedLocal = send(
                    base, "/api/auth/login", "POST", loginBody, null,
                    Map.of("X-Forwarded-For",
                            "127.0.0.1, 203.0.113.10"));
            assertEquals(400, spoofedLocal.statusCode());
            assertTrue(spoofedLocal.body().contains("https_required"));

            Map<String, String> secureRemote = Map.of(
                    "X-Forwarded-For", "203.0.113.11",
                    "X-Forwarded-Proto", "https");
            HttpResponse<String> login = send(
                    base, "/api/auth/login", "POST", loginBody, null,
                    secureRemote);
            assertEquals(200, login.statusCode(), login.body());
            assertTrue(login.headers().firstValue("Set-Cookie")
                    .orElseThrow().contains("Secure"));
            String remoteCookie = sessionCookie(login);

            HttpResponse<String> replayedOverHttp = send(
                    base, "/api/state", "GET", null, null,
                    Map.of(
                            "X-Forwarded-For", "203.0.113.11",
                            "Cookie", remoteCookie));
            assertEquals(401, replayedOverHttp.statusCode());

            Map<String, String> secureSession = new HashMap<>(secureRemote);
            secureSession.put("Cookie", remoteCookie);
            HttpResponse<String> remoteSession = send(
                    base, "/api/session", "GET", null, null,
                    secureSession);
            assertEquals(200, remoteSession.statusCode(), remoteSession.body());
            String token = json.read(remoteSession.body()
                    .getBytes(StandardCharsets.UTF_8), Map.class)
                    .get("token").toString();

            String accountUpdate = json.writeString(Map.of(
                    "username", "server_admin",
                    "password", "correct horse battery",
                    "newPassword", "new correct horse battery",
                    "confirmPassword", "new correct horse battery",
                    "allowLocalBypass", true));
            assertEquals(403, send(base, "/api/auth/account", "PUT",
                    accountUpdate, null, secureSession).statusCode());
            HttpResponse<String> updated = send(
                    base, "/api/auth/account", "PUT", accountUpdate,
                    token, secureSession);
            assertEquals(200, updated.statusCode(), updated.body());
            assertTrue(updated.headers().firstValue("Set-Cookie")
                    .orElseThrow().contains("Secure"));

            assertEquals(200, send(base, "/api/session", "GET",
                    null, null).statusCode());

            String updatedCookie = sessionCookie(updated);
            HttpResponse<String> logout = send(
                    base, "/api/auth/logout", "POST", "{}", null,
                    Map.of("Cookie", updatedCookie));
            assertEquals(200, logout.statusCode(), logout.body());
            List<String> logoutCookies = logout.headers().allValues("Set-Cookie");
            assertTrue(logoutCookies.stream().anyMatch(value ->
                    value.startsWith("DFS_ADMIN_SESSION=")
                            && value.contains("Max-Age=0")));
            String loggedOutCookie = logoutCookies.stream()
                    .filter(value -> value.startsWith("DFS_ADMIN_LOGGED_OUT=1"))
                    .findFirst().orElseThrow().split(";", 2)[0];
            assertEquals(401, send(base, "/api/session", "GET",
                    null, null, Map.of("Cookie", loggedOutCookie)).statusCode());
            HttpResponse<String> loggedOutStatus = send(
                    base, "/api/auth/status", "GET", null, null,
                    Map.of("Cookie", loggedOutCookie));
            assertTrue(loggedOutStatus.body().contains("\"authenticated\":false"),
                    loggedOutStatus.body());

            // The marker is browser-session scoped: clearing browser cookies
            // restores the configured local bypass without changing the account.
            assertEquals(200, send(base, "/api/session", "GET",
                    null, null).statusCode());

            String newLoginBody = json.writeString(Map.of(
                    "username", "server_admin",
                    "password", "new correct horse battery"));
            HttpResponse<String> relogin = send(
                    base, "/api/auth/login", "POST", newLoginBody, null,
                    Map.of("Cookie", loggedOutCookie));
            assertEquals(200, relogin.statusCode(), relogin.body());
            assertTrue(relogin.headers().allValues("Set-Cookie").stream()
                    .anyMatch(value -> value.startsWith("DFS_ADMIN_LOGGED_OUT=")
                            && value.contains("Max-Age=0")));

            WebAuthStore reloaded = new WebAuthStore(
                    admin.resolve("management-web-auth.json"));
            assertTrue(reloaded.verify("server_admin",
                    "new correct horse battery".toCharArray()));
        }
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
            assertTrue(script.body().contains("uploadTargetDirectory"));
            assertTrue(script.body().contains("beforeunload"));
            assertTrue(script.body().contains("capturePublishPosition"));
            assertTrue(script.body().contains("remove-batch"));
            assertTrue(script.body().contains("本次没有修改"));
            assertTrue(script.body().contains("expandedPlayerPages"));
            assertTrue(script.body().contains("player-editor-card-actions"));

            HttpResponse<String> stylesheet = send(
                    base, "/app.css", "GET", null, null);
            assertTrue(stylesheet.body().contains(".player-page-card.collapsed"));
            assertTrue(stylesheet.body().contains(".player-editor-card-heading"));
        }
    }

    @Test
    void uploadsListsAndRemovesOptionalMusicThroughTheWebApi() throws Exception {
        ManagementCli root = new ManagementCli(
                temporary.resolve("music-admin/management-settings.json"),
                new StringReader(""));
        Path source = Files.createDirectories(temporary.resolve("music-pack"));
        Files.writeString(source.resolve("options.txt"), "base");

        try (AdminWebServer server = new AdminWebServer(
                root, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.address().getPort());
            String token = json.read(send(base, "/api/session", "GET", null, null)
                    .body().getBytes(StandardCharsets.UTF_8), Map.class)
                    .get("token").toString();
            assertEquals(201, send(base, "/api/projects", "POST", json.writeString(Map.of(
                    "id", "music-demo", "displayName", "Music Demo",
                    "sourceDirectory", source.toString(),
                    "publicBaseUrl", "http://127.0.0.1:8080")), token).statusCode());

            HttpResponse<String> uploaded = sendBytes(base,
                    "/api/projects/music-demo/music/upload?fileName=theme.mp3"
                            + "&id=theme&title=Theme", new byte[]{'I', 'D', '3', 1, 2, 3}, token);
            assertEquals(201, uploaded.statusCode(), uploaded.body());
            assertTrue(uploaded.body().contains("\"musicTracks\""));
            try (var files = Files.list(Path.of(root.settings().dataDirectory()).resolve("tmp"))) {
                assertEquals(0, files.count());
            }

            HttpResponse<String> list = send(base,
                    "/api/projects/music-demo/music", "GET", null, null);
            assertEquals(200, list.statusCode(), list.body());
            assertTrue(list.body().contains("theme.mp3"));

            Path importedMp3 = temporary.resolve("server-theme.mp3");
            Files.write(importedMp3, new byte[]{'I', 'D', '3', 4, 5, 6});
            HttpResponse<String> imported = send(base,
                    "/api/projects/music-demo/music/import", "POST",
                    json.writeString(Map.of(
                            "sourcePath", importedMp3.toString(),
                            "title", "Server Theme",
                            "overwrite", false)), token);
            assertEquals(201, imported.statusCode(), imported.body());
            assertTrue(imported.body().contains("server-theme.mp3"));

            HttpResponse<String> cleared = send(base,
                    "/api/projects/music-demo/music/clear", "POST", "{}", token);
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertTrue(cleared.body().contains("\"musicTracks\":[]"));
        }
    }

    private HttpResponse<String> send(
            URI base, String path, String method, String body, String token)
            throws Exception {
        return send(base, path, method, body, token, Map.of());
    }

    private HttpResponse<String> send(
            URI base, String path, String method, String body, String token,
            Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (token != null) builder.header("X-DFS-Token", token);
        extraHeaders.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String sessionCookie(HttpResponse<?> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";", 2)[0];
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
