package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.engine.UpdateEngine;
import cn.dreamingfish.updater.engine.UpdateOutcome;
import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticDistributionServiceTest {
    @TempDir
    Path temporary;

    @Test
    void exportsIncrementallyRepairsChangedObjectsAndProtectsOtherDirectories()
            throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        Files.createDirectories(fixture.source.resolve("mods"));
        Files.writeString(fixture.source.resolve("mods/example.jar"), "managed-mod");
        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.0", "首次静态发布");

        Path playerSource = Files.createDirectories(temporary.resolve("player-source"));
        Files.writeString(playerSource.resolve("player.exe"), "player-program");
        new PlayerProgramService(fixture.paths, fixture.database, fixture.json).publish(
                "demo", "windows-x64", "0.2.0", playerSource,
                "player.exe", "0.1.0");

        Path output = temporary.resolve("static-output");
        StaticDistributionService service = new StaticDistributionService(
                fixture.paths, fixture.database, fixture.json);
        StaticDistributionExportResult first = service.exportProject("demo", output);

        assertEquals(1, first.releaseCount());
        assertEquals(1, first.playerProgramCount());
        assertEquals(2, first.objectCount());
        assertEquals(2, first.copiedObjectCount());
        assertEquals(0, first.reusedObjectCount());
        assertTrue(Files.isRegularFile(output.resolve("v1/projects/demo/latest")));
        assertTrue(Files.isRegularFile(output.resolve("v1/projects/demo/latest.sig")));
        assertTrue(Files.isRegularFile(output.resolve("v1/projects/demo/latest.signed")));
        assertTrue(Files.isRegularFile(output.resolve("v1/projects/demo/presentation.signed")));
        assertTrue(Files.isRegularFile(output.resolve(
                "v1/projects/demo/releases/" + release.releaseId() + "/manifest")));
        assertTrue(Files.isRegularFile(output.resolve(
                "v1/projects/demo/player/windows-x64/latest.sig")));
        assertTrue(Files.isRegularFile(output.resolve(
                "v1/projects/demo/player/windows-x64/latest.signed")));
        assertTrue(Files.isRegularFile(output.resolve("healthz")));
        assertFalse(Files.exists(output.resolve("keys")));

        StaticDistributionExportResult second = service.exportProject("demo", output);
        assertEquals(0, second.copiedObjectCount());
        assertEquals(2, second.reusedObjectCount());

        String releaseObject = fixture.database.readManifest(release)
                .files().getFirst().sha256();
        Path exportedObject = output.resolve("v1/objects/sha256/" + releaseObject);
        Files.writeString(exportedObject, "changed----", StandardCharsets.UTF_8);
        StaticDistributionExportResult repaired = service.exportProject("demo", output);
        assertEquals(1, repaired.copiedObjectCount());
        assertEquals(releaseObject, CryptoSupport.sha256(exportedObject));

        Path occupied = Files.createDirectories(temporary.resolve("occupied"));
        Files.writeString(occupied.resolve("mine.txt"), "do not replace");
        assertThrows(ManagementException.class,
                () -> service.exportProject("demo", occupied));
        assertEquals("do not replace", Files.readString(occupied.resolve("mine.txt")));
        assertThrows(ManagementException.class,
                () -> service.exportProject("demo", fixture.paths.root()));
        assertThrows(ManagementException.class,
                () -> service.exportProject("demo", fixture.source));
    }

    @Test
    void exportedDirectoryWorksAsAHeaderlessStaticUpdateServer() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("e2e-management"));
        ProjectRecord project = fixture.createProject();
        Path managed = fixture.source.resolve("mods/static.jar");
        Files.createDirectories(managed.getParent());
        Files.writeString(managed, "static-content");
        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.0", "静态端到端");

        Path output = temporary.resolve("e2e-static");
        new StaticDistributionService(fixture.paths, fixture.database, fixture.json)
                .exportProject("demo", output);
        Path instance = Files.createDirectories(temporary.resolve("e2e-instance"));
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                .prepare("demo", release.releaseId(), instance, playerHome);
        Files.deleteIfExists(instance.resolve("mods/static.jar"));

        try (StaticTestServer server = new StaticTestServer(output)) {
            ProjectBinding binding = new ProjectBinding(
                    ProtocolConstants.BINDING_SCHEMA_VERSION,
                    project.id(), server.baseUrl(), project.publicKey(),
                    "DreamingFishUpdater", null, project.branding());
            var result = new UpdateEngine().update(UpdateRequest.defaults(
                    instance, playerHome, binding, "0.2.0", ManagementFixture.PLAYER_CAPABILITIES), null);

            assertEquals(UpdateOutcome.UPDATED, result.outcome());
            assertEquals("static-content",
                    Files.readString(instance.resolve("mods/static.jar")));
            Files.delete(output.resolve("v1/projects/demo/latest.signed"));
            assertEquals(UpdateOutcome.UP_TO_DATE, new UpdateEngine().update(UpdateRequest.defaults(
                    instance, playerHome, binding, "0.2.0", ManagementFixture.PLAYER_CAPABILITIES), null).outcome());
        }
    }

    @Test
    void atomicStaticPayloadSurvivesMixedLegacyPointersAndRejectsTampering() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("atomic-management"));
        ProjectRecord project = fixture.createProject();
        Path managed = fixture.source.resolve("mods/static.jar");
        Files.createDirectories(managed.getParent());
        Files.writeString(managed, "old content");
        fixture.scanner.createPreview("demo");
        StoredRelease first = fixture.publisher.publish("demo", "1.0.0", "0.1.0", "first");
        byte[] oldPayload = Files.readAllBytes(first.manifestPath());
        Path instance = Files.createDirectories(temporary.resolve("atomic-instance"));
        Path home = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                .prepare("demo", first.releaseId(), instance, home);
        Files.writeString(managed, "new content");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "2.0.0", "0.1.0", "second");
        Path output = temporary.resolve("atomic-static");
        new StaticDistributionService(fixture.paths, fixture.database, fixture.json).exportProject("demo", output);
        // Mimic an old cached latest payload and a new latest.sig.
        Files.write(output.resolve("v1/projects/demo/latest"), oldPayload);
        try (StaticTestServer server = new StaticTestServer(output)) {
            ProjectBinding binding = new ProjectBinding(ProtocolConstants.BINDING_SCHEMA_VERSION,
                    "demo", server.baseUrl(), project.publicKey(), "DreamingFishUpdater", null, project.branding());
            UpdateRequest request = UpdateRequest.defaults(instance, home, binding, "0.2.0", ManagementFixture.PLAYER_CAPABILITIES);
            var result = new UpdateEngine().update(request, null);
            assertEquals(2, result.release().sequence());
            assertEquals("new content", Files.readString(instance.resolve("mods/static.jar")));
            Path signed = output.resolve("v1/projects/demo/latest.signed");
            byte[] corrupted = Files.readAllBytes(signed);
            corrupted[corrupted.length - 2] ^= 1;
            Files.write(signed, corrupted);
            var error = assertThrows(cn.dreamingfish.updater.engine.UpdateException.class,
                    () -> new UpdateEngine().update(request, null));
            assertEquals(cn.dreamingfish.updater.engine.UpdateErrorCode.INVALID_SIGNATURE, error.code());
        }
    }

    private static final class StaticTestServer implements AutoCloseable {
        private final Path root;
        private final HttpServer server;

        private StaticTestServer(Path root) throws IOException {
            this.root = root.toAbsolutePath().normalize();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::serve);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        private void serve(HttpExchange exchange) throws IOException {
            try {
                String relative = URI.create(exchange.getRequestURI().getRawPath())
                        .getPath().replaceFirst("^/+", "");
                Path file = root.resolve(relative.replace('/', java.io.File.separatorChar))
                        .normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
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
