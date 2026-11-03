package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExternalHostingServiceTest {
    @TempDir Path temporary;

    @Test
    void remembersCredentialsOnlyAfterAWorkingUploadAndNeverShowsThem() throws Exception {
        var f = published();
        var hosting = hosting(f);
        try (var remote = new DavServer("admin", "dav-secret-value")) {
            var wrong = ExternalHostingService.Destination.webDav(output().toString(), remote.base(), "admin", "typo");
            assertThrows(ManagementException.class, () -> hosting.deliver("demo", wrong, true, true));
            assertTrue(hosting.saved("demo").isEmpty(), "a key that did not work must not be kept");

            var right = ExternalHostingService.Destination.webDav(output().toString(), remote.base(), "admin", "dav-secret-value");
            hosting.deliver("demo", right, true, true);
            var saved = hosting.saved("demo").orElseThrow();
            assertTrue(saved.autoUpload());
            assertEquals("dav-secret-value", saved.password());
            assertFalse(saved.toString().contains("dav-secret-value"));

            var view = hosting.view("demo");
            assertFalse(new String(f.json.write(view), StandardCharsets.UTF_8).contains("dav-secret-value"));
            assertEquals("admin", view.username());
            assertNotNull(view.autoStatus().uploadedAt());
            assertNull(view.autoStatus().problem());

            Path file = f.paths.keys().resolve("demo.hosting.json");
            assertTrue(Files.isRegularFile(file), "credentials sit beside the project signing key");
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix"))
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(file));
            assertFalse(Files.exists(output().resolve("demo.hosting.json")));
            try (var walk = Files.walk(output())) {
                assertTrue(walk.filter(Files::isRegularFile).noneMatch(path -> {
                    try { return Files.readString(path, StandardCharsets.ISO_8859_1).contains("dav-secret-value"); }
                    catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                }), "the exported directory never contains the saved secret");
            }

            hosting.forget("demo");
            assertTrue(hosting.saved("demo").isEmpty());
            assertNull(hosting.view("demo"));
            assertFalse(Files.exists(file));
        }
    }

    @Test
    void automaticUploadRunsOnlyAfterPublicContentChanged() throws Exception {
        var f = published();
        var hosting = hosting(f);
        try (var remote = new DavServer("admin", "secret")) {
            hosting.deliver("demo", ExternalHostingService.Destination.webDav(output().toString(), remote.base(), "admin", "secret"), true, true);
            int afterFirst = remote.puts.get();

            hosting.runAutomatic("demo");
            assertEquals(afterFirst, remote.puts.get(), "nothing changed, so nothing is uploaded");

            publish(f, "1.0.1", "second");
            hosting.runAutomatic("demo");
            assertTrue(remote.puts.get() > afterFirst);
            String latest = new String(remote.files.get("/dav/v1/projects/demo/latest"), StandardCharsets.UTF_8);
            assertTrue(latest.contains("1.0.1"), "the new release reached the saved destination");

            publishProgram(f);
            hosting.runAutomatic("demo");
            assertTrue(remote.files.containsKey("/dav/v1/projects/demo/player/windows-x64/latest"));
            var policies = new PlayerUpdatePolicyService(f.paths, f.database, f.json);
            int beforePause = remote.puts.get();
            policies.setEnabled("demo", false, policies.read("demo").revision());
            hosting.runAutomatic("demo");
            assertTrue(remote.puts.get() > beforePause, "pausing is public content too");

            hosting.setAuto("demo", false);
            int beforeOff = remote.puts.get();
            publish(f, "1.0.2", "third");
            hosting.runAutomatic("demo");
            assertEquals(beforeOff, remote.puts.get(), "turned off means only the owner uploads");
            hosting.upload("demo");
            assertTrue(remote.puts.get() > beforeOff);
        }
    }

    @Test
    void failedAutomaticUploadIsRecordedAndWaitsUnlessSomethingNewIsPublished() throws Exception {
        var f = published();
        var hosting = hosting(f);
        try (var remote = new DavServer("admin", "secret")) {
            hosting.deliver("demo", ExternalHostingService.Destination.webDav(output().toString(), remote.base(), "admin", "secret"), true, true);
            var firstUpload = hosting.status("demo").uploadedAt();

            remote.password = "rotated-by-the-host";
            publish(f, "1.0.1", "second");
            var failed = hosting.runAutomatic("demo");
            assertNotNull(failed.problem());
            assertEquals(firstUpload, failed.uploadedAt(), "a failure keeps the time of the last real upload");
            assertNotNull(hosting.view("demo").autoStatus().problem());

            int requests = remote.requests.get();
            hosting.runAutomatic("demo");
            assertEquals(requests, remote.requests.get(), "the same failure is not retried every few minutes");

            remote.password = "secret";
            publish(f, "1.0.2", "third");
            var recovered = hosting.runAutomatic("demo");
            assertNull(recovered.problem());
            assertTrue(new String(remote.files.get("/dav/v1/projects/demo/latest"), StandardCharsets.UTF_8).contains("1.0.2"));
        }
    }

    @Test
    void refusesToKeepTemporaryTokensOrCredentialsInsideAddresses() throws Exception {
        var f = published();
        var hosting = hosting(f);
        String out = output().toString();
        var token = ExternalHostingService.Destination.s3(out, "https://oss-cn-hangzhou.aliyuncs.com", "oss-cn-hangzhou",
                "bucket", "", "LTAI-test", "secret", "sts-token", "VIRTUAL_HOST");
        assertTrue(assertThrows(ManagementException.class, () -> hosting.deliver("demo", token, true, true))
                .getMessage().contains("临时会话令牌"));
        var inline = ExternalHostingService.Destination.webDav(out, "https://user:pass@dav.example.com/", "", "");
        assertThrows(ManagementException.class, () -> hosting.deliver("demo", inline, true, true));
        var relative = ExternalHostingService.Destination.webDav("relative/export", "https://dav.example.com/", "u", "p");
        assertThrows(ManagementException.class, () -> hosting.deliver("demo", relative, true, true));
        var noSecret = ExternalHostingService.Destination.s3(out, "https://oss-cn-hangzhou.aliyuncs.com", "oss-cn-hangzhou",
                "bucket", "", "LTAI-test", "", "", "VIRTUAL_HOST");
        assertThrows(ManagementException.class, () -> hosting.deliver("demo", noSecret, true, true));
        assertTrue(hosting.saved("demo").isEmpty());
        assertFalse(Files.exists(Path.of(out)), "nothing was exported for a destination that cannot be kept");
        assertThrows(ManagementException.class, () -> hosting.setAuto("demo", true));
        assertThrows(ManagementException.class, () -> hosting.upload("demo"));

        Files.createDirectories(f.paths.keys());
        Files.writeString(f.paths.keys().resolve("demo.hosting.json"), "{\"password\":\"leak-me\"");
        var damaged = hosting.view("demo");
        assertNotNull(damaged.problem());
        assertFalse(damaged.problem().contains("leak-me"));
        hosting.forget("demo");
        assertNull(hosting.view("demo"));
    }

    @Test
    void fingerprintFollowsWhatAnExportPublishes() throws Exception {
        var f = published();
        var exports = new StaticDistributionService(f.paths, f.database, f.json);
        String first = exports.fingerprint("demo");
        assertEquals(first, exports.fingerprint("demo"));
        publish(f, "1.0.1", "second");
        String second = exports.fingerprint("demo");
        assertNotEquals(first, second);
        publishProgram(f);
        String third = exports.fingerprint("demo");
        assertNotEquals(second, third);
        var policies = new PlayerUpdatePolicyService(f.paths, f.database, f.json);
        policies.setEnabled("demo", false, policies.read("demo").revision());
        assertNotEquals(third, exports.fingerprint("demo"));
    }

    private ManagementFixture published() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        publish(fixture, "1.0.0", "first");
        return fixture;
    }

    private static void publish(ManagementFixture fixture, String version, String content) throws Exception {
        Files.createDirectories(fixture.source.resolve("mods"));
        Files.writeString(fixture.source.resolve("mods/example.jar"), content);
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", version, "0.1.0", "Upload " + version);
    }

    private void publishProgram(ManagementFixture fixture) throws Exception {
        Path program = Files.createDirectories(temporary.resolve("program"));
        Files.writeString(program.resolve("player.exe"), "program");
        new PlayerProgramService(fixture.paths, fixture.database, fixture.json)
                .publish("demo", "windows-x64", "0.3.3", program, "player.exe", "0.1.2");
    }

    private Path output() { return temporary.resolve("export").toAbsolutePath(); }

    private static ExternalHostingService hosting(ManagementFixture f) {
        return new ExternalHostingService(f.paths, f.database, f.json,
                new StaticDistributionService(f.paths, f.database, f.json), new StaticDistributionUploader(f.json));
    }

    /** Minimal WebDAV host whose password can be rotated to simulate a revoked key. */
    private static final class DavServer implements AutoCloseable {
        private final HttpServer server;
        private final String username;
        private volatile String password;
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicInteger requests = new AtomicInteger();

        DavServer(String username, String password) throws IOException {
            this.username = username; this.password = password;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/"; }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                requests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                String expected = "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                if (!path.startsWith("/dav/")) { exchange.sendResponseHeaders(404, -1); return; }
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1); return;
                }
                switch (exchange.getRequestMethod()) {
                    case "DELETE" -> { files.remove(path); exchange.sendResponseHeaders(204, -1); }
                    case "MKCOL" -> exchange.sendResponseHeaders(201, -1);
                    case "PUT" -> {
                        byte[] bytes = exchange.getRequestBody().readAllBytes();
                        if (!CryptoSupport.sha256(bytes).equals(exchange.getRequestHeaders().getFirst("X-DFS-SHA256"))) {
                            exchange.sendResponseHeaders(400, -1); return;
                        }
                        files.put(path, bytes); puts.incrementAndGet();
                        exchange.sendResponseHeaders(201, -1);
                    }
                    case "GET", "HEAD" -> {
                        byte[] bytes = files.get(path);
                        if (bytes == null) { exchange.sendResponseHeaders(404, -1); return; }
                        if (exchange.getRequestMethod().equals("HEAD")) {
                            exchange.getResponseHeaders().set("Content-Length", Long.toString(bytes.length));
                            exchange.sendResponseHeaders(200, -1);
                        } else {
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        }
                    }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            } finally {
                exchange.close();
            }
        }

        @Override public void close() { server.stop(0); }
    }
}
