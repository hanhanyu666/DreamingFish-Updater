package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticDistributionUploaderTest {
    @TempDir
    Path temporary;

    @Test
    void uploadsWebDavAndUsesTheRemoteHashIndexOnTheNextRun() throws Exception {
        ExportFixture exported = exportFixture("webdav");
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                "admin:secret".getBytes(StandardCharsets.UTF_8));
        try (UploadServer remote = new UploadServer("/dav/", expectedAuth, false)) {
            StaticDistributionUploader uploader = new StaticDistributionUploader(
                    exported.fixture().json);
            WebDavUploadConfiguration configuration = new WebDavUploadConfiguration(
                    remote.uri("/dav/"), "admin", "secret");

            DistributionUploadResult first = uploader.uploadWebDav(
                    exported.output(), configuration);
            assertEquals(first.fileCount(), first.uploadedFileCount());
            assertEquals(0, first.skippedFileCount());
            assertTrue(remote.files.containsKey(
                    "/dav/v1/projects/demo/latest"));
            assertTrue(remote.files.containsKey(
                    "/dav/v1/projects/demo/latest.sig"));
            assertTrue(remote.files.containsKey(
                    "/dav/.dreamingfish-upload-index.json"));

            DistributionUploadResult second = uploader.uploadWebDav(
                    exported.output(), configuration);
            assertEquals(0, second.uploadedFileCount());
            assertEquals(second.fileCount(), second.skippedFileCount());
            assertTrue(remote.authorizedRequests.get() > first.fileCount());
        }
    }

    @Test
    void uploadsToS3WithV4HeadersAndSkipsImmutableObjectsByMetadata()
            throws Exception {
        ExportFixture exported = exportFixture("s3");
        try (UploadServer remote = new UploadServer("/bucket/updates/", "", true)) {
            StaticDistributionUploader uploader = new StaticDistributionUploader(
                    exported.fixture().json);
            S3UploadConfiguration configuration = new S3UploadConfiguration(
                    remote.uri("/"), "auto", "bucket", "updates",
                    "test-access", "test-secret", "",
                    S3AddressingStyle.PATH);

            DistributionUploadResult first = uploader.uploadS3(
                    exported.output(), configuration);
            assertEquals(first.fileCount(), first.uploadedFileCount());
            assertTrue(remote.files.containsKey(
                    "/bucket/updates/v1/projects/demo/latest"));
            assertTrue(remote.signedRequests.get() >= first.fileCount());

            DistributionUploadResult second = uploader.uploadS3(
                    exported.output(), configuration);
            assertTrue(second.skippedFileCount() > 0);
            assertTrue(second.uploadedFileCount() > 0,
                    "latest and presentation routes must be committed last again");
            assertEquals(second.fileCount(),
                    second.skippedFileCount() + second.uploadedFileCount());
        }
    }

    @Test
    void rejectsSecretsOverPlainPublicHttp() throws Exception {
        ExportFixture exported = exportFixture("insecure");
        StaticDistributionUploader uploader = new StaticDistributionUploader(
                exported.fixture().json);
        assertThrows(ManagementException.class, () -> uploader.uploadWebDav(
                exported.output(), new WebDavUploadConfiguration(
                        URI.create("http://example.com/dav/"), "admin", "secret")));
        assertThrows(ManagementException.class, () -> uploader.uploadS3(
                exported.output(), new S3UploadConfiguration(
                        URI.create("http://example.com/"), "auto", "bucket",
                        "", "access", "secret", "", S3AddressingStyle.PATH)));
    }

    private ExportFixture exportFixture(String name) throws Exception {
        ManagementFixture fixture = new ManagementFixture(
                temporary.resolve(name + "-management"));
        fixture.createProject();
        Files.createDirectories(fixture.source.resolve("mods"));
        Files.writeString(fixture.source.resolve("mods/example.jar"),
                "upload-content");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0.0", "0.1.0", "Upload");
        Path output = temporary.resolve(name + "-export");
        new StaticDistributionService(fixture.paths, fixture.database, fixture.json)
                .exportProject("demo", output);
        return new ExportFixture(fixture, output);
    }

    private record ExportFixture(ManagementFixture fixture, Path output) {
    }

    private static final class UploadServer implements AutoCloseable {
        private final HttpServer server;
        private final String requiredPrefix;
        private final String expectedAuthorization;
        private final boolean s3;
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final Map<String, String> hashes = new ConcurrentHashMap<>();
        private final AtomicInteger authorizedRequests = new AtomicInteger();
        private final AtomicInteger signedRequests = new AtomicInteger();

        private UploadServer(String requiredPrefix, String expectedAuthorization,
                             boolean s3) throws IOException {
            this.requiredPrefix = requiredPrefix;
            this.expectedAuthorization = expectedAuthorization;
            this.s3 = s3;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(java.util.concurrent.Executors
                    .newVirtualThreadPerTaskExecutor());
            server.start();
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + path);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!path.startsWith(requiredPrefix)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                String authorization = exchange.getRequestHeaders()
                        .getFirst("Authorization");
                if (s3) {
                    if (authorization == null
                            || !authorization.startsWith("AWS4-HMAC-SHA256 ")
                            || exchange.getRequestHeaders().getFirst("x-amz-date") == null
                            || exchange.getRequestHeaders().getFirst(
                            "x-amz-content-sha256") == null) {
                        exchange.sendResponseHeaders(403, -1);
                        return;
                    }
                    signedRequests.incrementAndGet();
                } else if (!expectedAuthorization.equals(authorization)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                } else {
                    authorizedRequests.incrementAndGet();
                }

                switch (exchange.getRequestMethod()) {
                    case "MKCOL" -> exchange.sendResponseHeaders(201, -1);
                    case "PUT" -> {
                        byte[] bytes = exchange.getRequestBody().readAllBytes();
                        String suppliedHash = s3
                                ? exchange.getRequestHeaders().getFirst(
                                "x-amz-content-sha256")
                                : exchange.getRequestHeaders().getFirst("X-DFS-SHA256");
                        if (suppliedHash == null
                                || !CryptoSupport.sha256(bytes).equals(suppliedHash)) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        files.put(path, bytes);
                        hashes.put(path, suppliedHash);
                        exchange.sendResponseHeaders(201, -1);
                    }
                    case "GET" -> {
                        byte[] bytes = files.get(path);
                        if (bytes == null) {
                            exchange.sendResponseHeaders(404, -1);
                        } else {
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        }
                    }
                    case "HEAD" -> {
                        byte[] bytes = files.get(path);
                        if (bytes == null) {
                            exchange.sendResponseHeaders(404, -1);
                        } else {
                            exchange.getResponseHeaders().set(
                                    "Content-Length", Long.toString(bytes.length));
                            if (s3) exchange.getResponseHeaders().set(
                                    "x-amz-meta-dfs-sha256", hashes.get(path));
                            exchange.sendResponseHeaders(200, -1);
                        }
                    }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
