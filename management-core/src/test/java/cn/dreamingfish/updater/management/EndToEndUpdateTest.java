package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.engine.UpdateEngine;
import cn.dreamingfish.updater.engine.UpdateOutcome;
import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndUpdateTest {
    @TempDir
    Path temporary;

    @Test
    void publishesServesInstallsUpdatesAndAllowsVerifiedOfflineUse() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        ProjectRecord project = fixture.createProject();
        Path managedMod = fixture.source.resolve("mods/managed.jar");
        Files.createDirectories(managedMod.getParent());
        Files.writeString(managedMod, "release-one");
        fixture.scanner.createPreview("demo");
        StoredRelease firstRelease = fixture.publisher.publish("demo", "1.0.0", "0.1.0", "First");

        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                .prepare("demo", firstRelease.releaseId(), instance, playerHome);
        Path customMod = instance.resolve("mods/custom.jar");
        Files.createDirectories(customMod.getParent());
        Files.writeString(customMod, "player-choice");
        Files.delete(managedMod(instance));

        ProjectBinding binding;
        UpdateEngine engine = new UpdateEngine();
        try (PublicFileServer server = new PublicFileServer(
                fixture.database, fixture.objects, new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            binding = new ProjectBinding(
                    ProtocolConstants.BINDING_SCHEMA_VERSION,
                    project.id(),
                    "http://127.0.0.1:" + server.address().getPort(),
                    project.publicKey(),
                    "DreamingFishUpdater",
                    null,
                    project.branding()
            );
            UpdateResult first = engine.update(request(instance, playerHome, binding), null);
            assertEquals(UpdateOutcome.UPDATED, first.outcome());
            assertEquals("release-one", Files.readString(managedMod(instance)));
            assertEquals(Set.of(Path.of("mods/custom.jar")), Set.copyOf(first.unmanagedMods()));

            Files.writeString(managedMod, "release-two");
            Path newConfig = fixture.source.resolve("config/new.toml");
            Files.createDirectories(newConfig.getParent());
            Files.writeString(newConfig, "enabled=true");
            fixture.scanner.createPreview("demo");
            fixture.publisher.publish("demo", "2.0.0", "0.1.0", "Second");

            UpdateResult second = engine.update(request(instance, playerHome, binding), null);
            assertEquals(UpdateOutcome.UPDATED, second.outcome());
            assertEquals("release-two", Files.readString(managedMod(instance)));
            assertEquals("enabled=true", Files.readString(instance.resolve("config/new.toml")));
            assertTrue(Files.isRegularFile(customMod));
        }

        UpdateResult offline = engine.update(request(instance, playerHome, binding), null);
        assertEquals(UpdateOutcome.OFFLINE_ALLOWED, offline.outcome());
        assertFalse(offline.unmanagedMods().isEmpty());
    }

    private static Path managedMod(Path instance) {
        return instance.resolve("mods/managed.jar");
    }

    @Test
    void staleAndAlreadyReleasedClientsBothKeepTheirCopyAfterRollback() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("rollback-management"));
        ProjectRecord project = fixture.createProject();
        fixture.scanner.createPreview("demo");
        StoredRelease empty = fixture.publisher.publish("demo", "1.0.0", "0.1.0", "empty");
        Path source = fixture.source.resolve("mods/retired.jar");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "player-owned after release");
        fixture.scanner.createPreview("demo");
        StoredRelease original = fixture.publisher.publish("demo", "2.0.0", "0.1.0", "managed");
        Path stale = Files.createDirectories(temporary.resolve("stale-client"));
        Path released = Files.createDirectories(temporary.resolve("released-client"));
        for (Path instance : java.util.List.of(stale, released)) {
            Path home = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
            new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json).prepare("demo", original.releaseId(), instance, home);
        }
        Files.delete(source);
        fixture.scanner.createPreview("demo");
        fixture.scanner.decideRemovals("demo", java.util.List.of(new RemovalDecision("mods/retired.jar", RemovalAction.RELEASE)));
        fixture.publisher.publish("demo", "3.0.0", "0.1.0", "released");
        try (PublicFileServer server = new PublicFileServer(fixture.database, fixture.objects, new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            ProjectBinding binding = new ProjectBinding(1, "demo", "http://127.0.0.1:" + server.address().getPort(),
                    project.publicKey(), "DreamingFishUpdater", null, project.branding());
            java.util.function.Function<Path, UpdateRequest> requests = instance -> UpdateRequest.defaults(instance,
                    instance.resolve("DreamingFishUpdater"), binding, "0.2.0", ManagementFixture.PLAYER_CAPABILITIES);
            UpdateEngine engine = new UpdateEngine();
            engine.update(requests.apply(released), null);
            fixture.publisher.rollback("demo", empty.releaseId(), "4.0.0", "rollback");
            engine.update(requests.apply(stale), null);
            engine.update(requests.apply(released), null);
            for (Path instance : java.util.List.of(stale, released)) {
                assertEquals("player-owned after release", Files.readString(instance.resolve("mods/retired.jar")));
            }
        }
    }

    private static UpdateRequest request(Path instance, Path playerHome, ProjectBinding binding) {
        return UpdateRequest.defaults(instance, playerHome, binding, "0.2.0", ManagementFixture.PLAYER_CAPABILITIES);
    }
}
