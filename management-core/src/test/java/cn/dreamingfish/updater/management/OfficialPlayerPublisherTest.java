package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficialPlayerPublisherTest {
    @TempDir Path temporary;

    @Test void aTestOrMirrorCatalogueAddressMustBeHttps() {
        assertEquals(OfficialPlayerService.CATALOG, OfficialPlayerService.catalog(""));
        assertEquals(URI.create("https://github.com/o/r/releases/download/v1-test/player-catalog.signed"),
                OfficialPlayerService.catalog(" https://github.com/o/r/releases/download/v1-test/player-catalog.signed "));
        assertThrows(ManagementException.class, () -> OfficialPlayerService.catalog("http://example.com/player-catalog.signed"));
        assertThrows(ManagementException.class, () -> OfficialPlayerService.catalog("https://user:pass@example.com/c"));
    }

    @Test void publishesInOneStepAndAutomaticPublishingRespectsPause() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("management")); fixture.createProject();
        var keys = CryptoSupport.generateEd25519KeyPair();
        byte[] program = "official-program".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = temporary.resolve("player.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/app/0.3.3/DreamingFishUpdater.exe")); zip.write(program); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/state/active-player.properties")); zip.write("schema=1\nversion=0.3.3\n".getBytes()); zip.closeEntry();
        }
        byte[] zipBytes = Files.readAllBytes(archive);
        var downloads = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[][] servedCatalog = new byte[1][];
        server.createContext("/", exchange -> {
            boolean catalog = exchange.getRequestURI().getPath().equals("/catalog");
            if (!catalog) downloads.incrementAndGet();
            byte[] body = catalog ? servedCatalog[0] : zipBytes;
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var release = new OfficialPlayerCatalog.Release("0.3.3", "windows-x64", base + "/player.zip", CryptoSupport.sha256(zipBytes),
                    zipBytes.length, "DreamingFishUpdater.exe", "0.1.2", "0.3.1", "test fix", Set.of(),
                    List.of(new PlayerProgramFile("DreamingFishUpdater.exe", CryptoSupport.sha256(program), program.length, false)));
            byte[] payload = fixture.json.write(new OfficialPlayerCatalog(1, Instant.now(), List.of(release)));
            servedCatalog[0] = new SignedDocument(payload, Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, keys.getPrivate()))).encode();
            var publisher = new OfficialPlayerPublisher(fixture.paths, fixture.database, fixture.json,
                    () -> new OfficialPlayerService(fixture.paths, fixture.database, fixture.json, URI.create(base + "/catalog"), keys.getPublic()));
            var policies = new PlayerUpdatePolicyService(fixture.paths, fixture.database, fixture.json);
            var programs = new PlayerProgramService(fixture.paths, fixture.database, fixture.json);

            assertThrows(ManagementException.class, () -> publisher.publish("demo", "windows-x64", "0.3.3", release.archiveSha256(), 5, "0.3.2"));
            assertEquals(0, downloads.get(), "a stale window must not download before the owner refreshes");
            var published = publisher.publish("demo", "windows-x64", "0.3.3", release.archiveSha256(), 0, "0.3.2");
            assertTrue(published.enabled());
            assertEquals("0.3.3", published.targets().get("windows-x64").version());
            assertEquals("0.3.3", programs.availableUpdate("demo", "windows-x64").orElseThrow().version());

            assertFalse(publisher.auto("demo").enabled());
            assertFalse(publisher.runAutomatic("demo", "0.3.2").enabled());
            assertTrue(publisher.setAuto("demo", true).enabled());
            var upToDate = publisher.runAutomatic("demo", "0.3.2");
            assertNull(upToDate.problem());
            assertNotNull(upToDate.checkedAt());

            var held = new java.util.concurrent.CountDownLatch(1); var release2 = new java.util.concurrent.CountDownLatch(1);
            var holder = Thread.ofPlatform().start(() -> {
                try (var lock = ProjectLock.acquire(fixture.paths.locks().resolve("official-catalog.lock"))) {
                    held.countDown(); release2.await();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            held.await();
            try {
                var busy = publisher.runAutomatic("demo", "0.3.2");
                assertNull(busy.problem(), "a check that only met another check is retried, not shown as a failure");
                assertEquals(upToDate.checkedAt(), busy.checkedAt());
            } finally { release2.countDown(); holder.join(); }
            assertEquals(published.revision(), policies.read("demo").revision(), "an approved version is not published twice");

            policies.setEnabled("demo", false, published.revision());
            var paused = publisher.runAutomatic("demo", "0.3.2");
            assertTrue(paused.problem().contains("暂停"));
            assertFalse(policies.read("demo").enabled(), "automatic publishing never resumes a paused project");
            assertFalse(publisher.setAuto("demo", false).enabled());
        } finally { server.stop(0); }
    }
}
