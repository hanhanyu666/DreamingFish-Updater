package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficialPlayerServiceTest {
    @TempDir Path temporary;

    @Test void aBrokenDownloadResumesFromTheSavedBytesAndGivesUpOnlyWithoutProgress() throws Exception {
        var keys = CryptoSupport.generateEd25519KeyPair();
        byte[] program = "official-program".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = temporary.resolve("resume-player.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/app/0.3.3/DreamingFishUpdater.exe")); zip.write(program); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/state/active-player.properties")); zip.write("schema=1\nversion=0.3.3\n".getBytes()); zip.closeEntry();
        }
        byte[] zipBytes = Files.readAllBytes(archive);
        int half = zipBytes.length / 2;
        var ranges = Collections.synchronizedList(new ArrayList<String>());
        String[] mode = {""};
        byte[][] servedCatalog = new byte[1][];
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/catalog")) {
                exchange.sendResponseHeaders(200, servedCatalog[0].length); exchange.getResponseBody().write(servedCatalog[0]);
            } else if (path.equals("/api/repos/o/r/releases/tags/v1")) {
                byte[] body = ("{\"assets\":[{\"name\":\"player.zip\",\"url\":\"" + base + "/api/repos/o/r/releases/assets/2\"}]}").getBytes();
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } else if (path.endsWith("/assets/2")) {
                // GitHub answers with a short-lived storage link, so the range has to survive the redirect.
                exchange.getResponseHeaders().set("Location", base + "/storage/player.zip");
                exchange.sendResponseHeaders(302, -1);
            } else {
                String range = exchange.getRequestHeaders().getFirst("Range"); ranges.add(range);
                if (mode[0].equals("stuck") || ranges.size() == 1) {
                    // The body ends part-way (at once when stuck), as when a long transfer is cut off.
                    exchange.sendResponseHeaders(200, 0);
                    if (!mode[0].equals("stuck")) { exchange.getResponseBody().write(zipBytes, 0, half); exchange.getResponseBody().flush(); }
                } else if (mode[0].equals("ignore") || range == null) {
                    exchange.sendResponseHeaders(200, zipBytes.length); exchange.getResponseBody().write(zipBytes);
                } else {
                    int from = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (zipBytes.length - 1) + "/" + zipBytes.length);
                    exchange.sendResponseHeaders(206, zipBytes.length - from); exchange.getResponseBody().write(zipBytes, from, zipBytes.length - from);
                }
            }
            exchange.close();
        }); server.start();
        try (var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()) {
            var release = new OfficialPlayerCatalog.Release("0.3.3", "windows-x64", base + "/o/r/releases/download/v1/player.zip",
                    CryptoSupport.sha256(zipBytes), zipBytes.length, "DreamingFishUpdater.exe", "0.1.2", "0.3.1", "resume", Set.of(),
                    List.of(new PlayerProgramFile("DreamingFishUpdater.exe", CryptoSupport.sha256(program), program.length, false)));
            for (String each : List.of("resume", "ignore", "stuck")) {
                mode[0] = each; ranges.clear();
                var fixture = new ManagementFixture(temporary.resolve("resume-" + each)); fixture.createProject();
                byte[] payload = fixture.json.write(new OfficialPlayerCatalog(1, Instant.now(), List.of(release)));
                servedCatalog[0] = new SignedDocument(payload, Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, keys.getPrivate()))).encode();
                var service = new OfficialPlayerService(fixture.paths, fixture.database, fixture.json, URI.create(base + "/catalog"),
                        keys.getPublic(), new GitHubReleaseAssets(client, fixture.json, URI.create(base + "/api/"), "127.0.0.1"));
                service.retryPause = Duration.ofMillis(10); service.patience = Duration.ofMillis(400);
                if (each.equals("stuck")) {
                    var error = assertThrows(ManagementException.class,
                            () -> service.prepare("demo", "windows-x64", "0.3.3", release.archiveSha256(), "0.3.1"));
                    assertTrue(error.getMessage().contains("没有任何进展"), error.getMessage());
                    assertTrue(ranges.size() >= 2, "a transfer that never moves is retried until the patience runs out, not for six hours");
                    assertTrue(new PlayerProgramService(fixture.paths, fixture.database, fixture.json).list("demo", "windows-x64").isEmpty());
                } else {
                    assertEquals("0.3.3", service.prepare("demo", "windows-x64", "0.3.3", release.archiveSha256(), "0.3.1").version(), each);
                    assertEquals(Arrays.asList(null, "bytes=" + half + "-"), ranges,
                            each + ": the second request continues after the saved half");
                }
            }
        } finally { server.stop(0); }
    }

    @Test void aStalledBodyIsCutAfterTheIdleTimeWellBeforeTheTotalLimit() throws Exception {
        var hold = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 10); exchange.getResponseBody().write(new byte[3]); exchange.getResponseBody().flush();
            try { hold.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        var request = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).build();
        try (var client = java.net.http.HttpClient.newHttpClient();
             var response = TimedHttpResponse.send(client, request, Duration.ofMinutes(5), Duration.ofMillis(400))) {
            long started = System.nanoTime();
            assertThrows(java.io.IOException.class, () -> response.body().readAllBytes());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "a silent connection is cut once it stops sending");
        } finally { hold.countDown(); server.stop(0); }
    }

    @Test void aSlowDownloadLeavesTheProjectUsableAndReportsProgress() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("slow-management")); fixture.createProject();
        var keys = CryptoSupport.generateEd25519KeyPair();
        byte[] program = "official-program".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = temporary.resolve("slow-player.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/app/0.3.3/DreamingFishUpdater.exe")); zip.write(program); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/state/active-player.properties")); zip.write("schema=1\nversion=0.3.3\n".getBytes()); zip.closeEntry();
        }
        byte[] zipBytes = Files.readAllBytes(archive);
        var downloading = new java.util.concurrent.CountDownLatch(1); var finish = new java.util.concurrent.CountDownLatch(1);
        byte[][] servedCatalog = new byte[1][];
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            boolean catalog = exchange.getRequestURI().getPath().equals("/catalog");
            byte[] body = catalog ? servedCatalog[0] : zipBytes;
            exchange.sendResponseHeaders(200, body.length);
            if (catalog) exchange.getResponseBody().write(body);
            else {
                exchange.getResponseBody().write(body, 0, body.length / 2); exchange.getResponseBody().flush();
                downloading.countDown();
                try { finish.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                exchange.getResponseBody().write(body, body.length / 2, body.length - body.length / 2);
            }
            exchange.close();
        }); server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var release = new OfficialPlayerCatalog.Release("0.3.3", "windows-x64", base + "/player.zip", CryptoSupport.sha256(zipBytes),
                    zipBytes.length, "DreamingFishUpdater.exe", "0.1.2", "0.3.1", "slow", Set.of(),
                    List.of(new PlayerProgramFile("DreamingFishUpdater.exe", CryptoSupport.sha256(program), program.length, false)));
            byte[] payload = fixture.json.write(new OfficialPlayerCatalog(1, Instant.now(), List.of(release)));
            servedCatalog[0] = new SignedDocument(payload, Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, keys.getPrivate()))).encode();
            var service = new OfficialPlayerService(fixture.paths, fixture.database, fixture.json, URI.create(base + "/catalog"), keys.getPublic());
            service.check(true);
            try (var tasks = new ApplicationTasks(fixture.paths, fixture.json)) {
                String id = UUID.randomUUID().toString();
                tasks.submit(id, "demo", "official-player-publish", null, "slow", false,
                        () -> service.prepare("demo", "windows-x64", "0.3.3", release.archiveSha256(), "0.3.1"));
                assertTrue(downloading.await(10, java.util.concurrent.TimeUnit.SECONDS));
                try (var lock = ProjectLock.acquire(fixture.paths.locks().resolve("demo.lock"))) {
                    assertNotNull(lock, "other project work proceeds while the program downloads");
                }
                var snapshot = tasks.require(id);
                assertEquals("正在下载官方玩家端程序", snapshot.phase());
                assertEquals(zipBytes.length, snapshot.total());
                finish.countDown();
                tasks.await(id);
                assertEquals(ApplicationTasks.State.SUCCEEDED, tasks.require(id).state());
            }
            assertEquals("0.3.3", new PlayerProgramService(fixture.paths, fixture.database, fixture.json)
                    .list("demo", "windows-x64").getFirst().version());
        } finally { finish.countDown(); server.stop(0); }
    }

    @Test void theOwnerCanStopADownloadAndNothingIsStored() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("stop-management")); fixture.createProject();
        var keys = CryptoSupport.generateEd25519KeyPair();
        byte[] program = "official-program".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = temporary.resolve("stop-player.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/app/0.3.3/DreamingFishUpdater.exe")); zip.write(program); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/state/active-player.properties")); zip.write("schema=1\nversion=0.3.3\n".getBytes()); zip.closeEntry();
        }
        byte[] zipBytes = Files.readAllBytes(archive);
        var downloading = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        byte[][] servedCatalog = new byte[1][];
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            boolean catalog = exchange.getRequestURI().getPath().equals("/catalog");
            byte[] body = catalog ? servedCatalog[0] : zipBytes;
            exchange.sendResponseHeaders(200, body.length);
            if (catalog) exchange.getResponseBody().write(body);
            else {
                exchange.getResponseBody().write(body, 0, body.length / 2); exchange.getResponseBody().flush();
                downloading.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            exchange.close();
        }); server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var official = new OfficialPlayerCatalog.Release("0.3.3", "windows-x64", base + "/player.zip", CryptoSupport.sha256(zipBytes),
                    zipBytes.length, "DreamingFishUpdater.exe", "0.1.2", "0.3.1", "stop", Set.of(),
                    List.of(new PlayerProgramFile("DreamingFishUpdater.exe", CryptoSupport.sha256(program), program.length, false)));
            byte[] payload = fixture.json.write(new OfficialPlayerCatalog(1, Instant.now(), List.of(official)));
            servedCatalog[0] = new SignedDocument(payload, Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, keys.getPrivate()))).encode();
            var service = new OfficialPlayerService(fixture.paths, fixture.database, fixture.json, URI.create(base + "/catalog"), keys.getPublic());
            service.check(true);
            try (var tasks = new ApplicationTasks(fixture.paths, fixture.json)) {
                String id = UUID.randomUUID().toString();
                tasks.submit(id, "demo", "official-player-publish", null, "stop", false,
                        () -> service.prepare("demo", "windows-x64", "0.3.3", official.archiveSha256(), "0.3.1"));
                assertTrue(downloading.await(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(ApplicationTasks.State.CANCELLED, tasks.cancel(id).state(), "a running download can be stopped");
                tasks.await(id);
                var stopped = tasks.require(id);
                assertEquals(ApplicationTasks.State.CANCELLED, stopped.state());
                assertTrue(stopped.error().contains("没有发布任何内容"), stopped.error());
            }
            assertTrue(new PlayerProgramService(fixture.paths, fixture.database, fixture.json).list("demo", "windows-x64").isEmpty());
            try (var left = Files.list(fixture.paths.temporary())) {
                assertTrue(left.noneMatch(path -> path.getFileName().toString().startsWith("official-player-")), "the partial download is removed");
            }
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void releaseAssetsComeThroughTheGitHubApiAndFallBackToTheDirectAddress() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("api-management")); fixture.createProject();
        var keys = CryptoSupport.generateEd25519KeyPair();
        byte[] program = "official-program".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = temporary.resolve("api-player.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/app/0.3.3/DreamingFishUpdater.exe")); zip.write(program); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/state/active-player.properties")); zip.write("schema=1\nversion=0.3.3\n".getBytes()); zip.closeEntry();
        }
        byte[] zipBytes = Files.readAllBytes(archive);
        var direct = new java.util.concurrent.atomic.AtomicInteger();
        boolean[] apiLimited = {false}, catalogListed = {true};
        byte[][] servedCatalog = new byte[1][];
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200; byte[] body = new byte[0];
            if (path.startsWith("/api/")) {
                boolean binary = "application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Accept"));
                if (apiLimited[0]) status = 403;
                else if (path.equals("/api/repos/o/r/releases/tags/v1")) body = ("{\"assets\":[" + (catalogListed[0]
                        ? "{\"name\":\"player-catalog.signed\",\"url\":\"" + base + "/api/repos/o/r/releases/assets/1\"}," : "")
                        + "{\"name\":\"player.zip\",\"url\":\"" + base + "/api/repos/o/r/releases/assets/2\"}]}").getBytes();
                else if (path.endsWith("/assets/1") && binary) body = servedCatalog[0];
                else if (path.endsWith("/assets/2") && binary) body = zipBytes;
                else status = 404;
            } else {
                direct.incrementAndGet();
                body = path.endsWith("player-catalog.signed") ? servedCatalog[0] : zipBytes;
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        }); server.start();
        try (var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()) {
            var release = new OfficialPlayerCatalog.Release("0.3.3", "windows-x64", base + "/o/r/releases/download/v1/player.zip",
                    CryptoSupport.sha256(zipBytes), zipBytes.length, "DreamingFishUpdater.exe", "0.1.2", "0.3.1", "api route", Set.of(),
                    List.of(new PlayerProgramFile("DreamingFishUpdater.exe", CryptoSupport.sha256(program), program.length, false)));
            byte[] payload = fixture.json.write(new OfficialPlayerCatalog(1, Instant.now(), List.of(release)));
            servedCatalog[0] = new SignedDocument(payload, Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, keys.getPrivate()))).encode();
            var service = new OfficialPlayerService(fixture.paths, fixture.database, fixture.json,
                    URI.create(base + "/o/r/releases/download/v1/player-catalog.signed"), keys.getPublic(),
                    new GitHubReleaseAssets(client, fixture.json, URI.create(base + "/api/"), "127.0.0.1"));

            assertEquals("0.3.3", service.check(true).releases().getFirst().version());
            assertEquals("0.3.3", service.prepare("demo", "windows-x64", "0.3.3", release.archiveSha256(), "0.3.1").version());
            assertEquals(0, direct.get(), "a host that cannot open github.com still gets the catalogue and the program");

            apiLimited[0] = true;
            assertEquals("0.3.3", service.check(true).releases().getFirst().version());
            assertEquals(1, direct.get(), "a limited or unreachable API falls back to the direct address");

            apiLimited[0] = false; catalogListed[0] = false;
            var missing = assertThrows(ManagementException.class, () -> service.check(true));
            assertTrue(missing.getMessage().contains("尚未提供"), missing.getMessage());
            assertEquals(1, direct.get(), "an asset GitHub reports as missing is not retried through a slow direct route");
        } finally { server.stop(0); }
    }
    @Test void signedOfficialCandidateRequiresProjectApprovalAndRejectsTampering() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("management")); fixture.createProject();
        var keys = CryptoSupport.generateEd25519KeyPair();
        byte[] program = "official-program".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = temporary.resolve("player.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/app/0.3.3/DreamingFishUpdater.exe")); zip.write(program); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/state/active-player.properties")); zip.write("schema=1\nversion=0.3.3\n".getBytes()); zip.closeEntry();
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[][] servedCatalog = new byte[1][], servedZip = { Files.readAllBytes(archive) };
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().equals("/catalog") ? servedCatalog[0] : servedZip[0];
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var release = new OfficialPlayerCatalog.Release("0.3.3", "windows-x64", base + "/player.zip", CryptoSupport.sha256(servedZip[0]),
                    servedZip[0].length, "DreamingFishUpdater.exe", "0.1.2", "0.3.1", "test fix", Set.of(),
                    List.of(new PlayerProgramFile("DreamingFishUpdater.exe", CryptoSupport.sha256(program), program.length, false)));
            var catalog = new OfficialPlayerCatalog(1, Instant.now(), List.of(release));
            byte[] payload = fixture.json.write(catalog);
            servedCatalog[0] = new SignedDocument(payload, Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, keys.getPrivate()))).encode();
            var service = new OfficialPlayerService(fixture.paths, fixture.database, fixture.json, URI.create(base + "/catalog"), keys.getPublic());
            assertEquals("0.3.3", service.check(true).releases().getFirst().version());
            servedZip[0] = new byte[servedZip[0].length];
            assertThrows(ManagementException.class, () -> service.prepare("demo", "windows-x64", "0.3.3", release.archiveSha256(), "0.3.1"));
            servedZip[0] = Files.readAllBytes(archive);
            var candidate = service.prepare("demo", "windows-x64", "0.3.3", release.archiveSha256(), "0.3.1");
            var programs = new PlayerProgramService(fixture.paths, fixture.database, fixture.json);
            assertTrue(programs.latest("demo", "windows-x64").isEmpty());
            var policies = new PlayerUpdatePolicyService(fixture.paths, fixture.database, fixture.json);
            policies.approve("demo", "windows-x64", candidate.version(), candidate.manifestSha256(), 0);
            assertEquals("0.3.3", programs.availableUpdate("demo", "windows-x64").orElseThrow().version());
            servedCatalog[0][servedCatalog[0].length - 2] ^= 1;
            assertThrows(RuntimeException.class, () -> service.check(true));
            assertEquals("0.3.3", service.check(false).releases().getFirst().version());
        } finally { server.stop(0); }
    }
}
