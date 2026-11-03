package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackRootEngineTest {
    @TempDir Path temporary;

    private ProjectBinding bind(TestUpdateServer server, Path instance, PackRootSpec spec) throws Exception {
        var old = server.binding();
        var binding = new ProjectBinding(old.schemaVersion(), old.projectId(), old.baseUrl(), old.publicKey(),
                old.playerHome(), null, old.fallbackBranding(), spec);
        Files.createDirectories(instance.resolve(".dreamingfish-bootstrap"));
        Files.write(instance.resolve(".dreamingfish-bootstrap/project-binding.json"), new JsonCodec().write(binding));
        server.bundle(instance, server.release(1, "baseline"), false);
        return binding;
    }
    private UpdateRequest request(Path instance, ProjectBinding binding) {
        return UpdateRequest.defaults(instance, instance.resolve("DreamingFishUpdater"), binding, "0.3.3", ProtocolConstants.RELEASE_CAPABILITIES);
    }
    private ReleaseManifest release(TestUpdateServer server, PackRootSpec root) {
        return release(server, root, 2, null);
    }
    /** A release that carries the outer-directory grant itself, as management now publishes it. */
    private ReleaseManifest release(TestUpdateServer server, PackRootSpec root, long sequence, PackRootSpec grant) {
        var game = server.file("mods/demo.jar", "new-game-" + sequence, FilePolicy.ENFORCED);
        var outer = server.file(root.prefix() + "guide.txt", "new-guide-" + sequence, FilePolicy.ENFORCED);
        var base = server.release(sequence, "release-" + sequence, game, outer);
        return new ReleaseManifest(1, "demo", base.releaseId(), base.sequence(), base.createdAt(), base.displayVersion(),
                "0.3.3", "two roots", Set.of(ProtocolConstants.CAPABILITY_PACK_ROOT), List.of(), List.of(), List.of(),
                Branding.empty(), base.files(), List.of(), List.of(), List.of(), List.of(), List.of(), grant);
    }

    @Test void rollsBackBothRootsAndCanRestoreAfterMovingTheWholePack() throws Exception {
        try (var server = new TestUpdateServer()) {
            Path pack = Files.createDirectories(temporary.resolve("整合包 带空格"));
            Path instance = Files.createDirectories(pack.resolve(".minecraft/versions/Demo"));
            var spec = new PackRootSpec(UUID.randomUUID().toString(), "../../..");
            var binding = bind(server, instance, spec);
            Files.createDirectories(instance.resolve("mods"));
            Files.writeString(instance.resolve("mods/demo.jar"), "old-game");
            Files.writeString(pack.resolve("guide.txt"), "old-guide");
            Files.writeString(pack.resolve("players-own.txt"), "keep");
            var target = release(server, spec); server.serve(target);
            var broken = new UpdateEngine(new TransactionFaultInjector() {
                @Override public void afterOperation(int index) { if (index == 1) throw new IllegalStateException("simulated interruption"); }
            });
            assertThrows(UpdateException.class, () -> broken.update(request(instance, binding), null));
            assertEquals("old-game", Files.readString(instance.resolve("mods/demo.jar")));
            assertEquals("old-guide", Files.readString(pack.resolve("guide.txt")));
            var result = new UpdateEngine().update(request(instance, binding), null);
            assertEquals(UpdateOutcome.UPDATED, result.outcome());
            assertEquals("new-game-2", Files.readString(instance.resolve("mods/demo.jar")));
            assertEquals("new-guide-2", Files.readString(pack.resolve("guide.txt")));
            assertEquals("keep", Files.readString(pack.resolve("players-own.txt")));
            assertFalse(Files.exists(instance.resolve("@pack")));
            Path moved = temporary.resolve("搬动后的整合包"); Files.move(pack, moved);
            Path movedInstance = moved.resolve(".minecraft/versions/Demo");
            assertEquals(UpdateOutcome.UP_TO_DATE, new UpdateEngine().update(request(movedInstance, binding), null).outcome());
            var catalog = new ArchiveCatalog(movedInstance, movedInstance.resolve("DreamingFishUpdater"));
            var archive = catalog.list().stream().filter(a -> a.files().stream().anyMatch(f -> f.originalPath().equals(spec.prefix() + "guide.txt"))).findFirst().orElseThrow();
            Files.delete(moved.resolve("guide.txt"));
            catalog.restore(archive.id(), spec.prefix() + "guide.txt", null, null);
            assertEquals("old-guide", Files.readString(moved.resolve("guide.txt")));
        }
    }

    @Test void setsOuterFilesAsideWithoutAGrantOrWithAReplacedPackIdentity() throws Exception {
        try (var server = new TestUpdateServer()) {
            Path pack = Files.createDirectories(temporary.resolve("pack"));
            Path instance = Files.createDirectories(pack.resolve(".minecraft/versions/Demo"));
            var spec = new PackRootSpec(UUID.randomUUID().toString(), "../../..");
            server.serve(release(server, spec));
            var noGrant = server.binding();
            bind(server, instance, null);
            var ungranted = new UpdateEngine().update(request(instance, noGrant), null);
            assertEquals(UpdateOutcome.UPDATED, ungranted.outcome());
            assertEquals("new-game-2", Files.readString(instance.resolve("mods/demo.jar")));
            assertNotNull(ungranted.packNotice());
            assertFalse(Files.exists(pack.resolve("guide.txt")));
            var binding = bind(server, instance, spec);
            ContentRoots.initialize(instance, binding);
            Files.writeString(pack.resolve(ContentRoots.MARKER), "{\"schemaVersion\":1,\"id\":\"other\",\"projectId\":\"demo\"}");
            var replaced = new UpdateEngine().update(request(instance, binding), null);
            assertNotNull(replaced.packNotice());
            assertFalse(Files.exists(pack.resolve("guide.txt")));
            assertThrows(ProtocolException.class, () -> ContentRoots.validatePackPath(".minecraft/versions/Other/config/a.json"));
            assertThrows(ProtocolException.class, () -> ContentRoots.validatePackPath("accounts.json"));
        }
    }

    @Test void acceptsTheSignedGrantWithoutRedistributingTheDeployment() throws Exception {
        try (var server = new TestUpdateServer()) {
            Path pack = Files.createDirectories(temporary.resolve("已有玩家的整合包"));
            Path instance = Files.createDirectories(pack.resolve(".minecraft/versions/Demo"));
            var grant = new PackRootSpec(UUID.randomUUID().toString(), "../../..").withDefaultLayout();
            var binding = bind(server, instance, null);
            server.serve(release(server, grant, 2, grant));
            var result = new UpdateEngine().update(request(instance, binding), null);
            assertEquals(UpdateOutcome.UPDATED, result.outcome());
            assertNull(result.packNotice());
            assertEquals("new-guide-2", Files.readString(pack.resolve("guide.txt")));
            assertTrue(Files.isRegularFile(instance.resolve(ContentRoots.GRANT)));
            assertTrue(Files.isRegularFile(pack.resolve(ContentRoots.MARKER)));
            assertEquals(UpdateOutcome.UP_TO_DATE, new UpdateEngine().update(request(instance, binding), null).outcome());
            server.serve(release(server, grant, 3, grant));
            assertEquals(UpdateOutcome.UPDATED, new UpdateEngine().update(request(instance, binding), null).outcome());
            assertEquals("new-guide-3", Files.readString(pack.resolve("guide.txt")));
        }
    }

    @Test void differentFolderLayoutOnlySkipsOuterFilesUntilItMatches() throws Exception {
        try (var server = new TestUpdateServer()) {
            Path other = Files.createDirectories(temporary.resolve("某个游戏目录"));
            Path instance = Files.createDirectories(other.resolve("minecraft-files/versions/Demo"));
            var grant = new PackRootSpec(UUID.randomUUID().toString(), "../../..").withDefaultLayout();
            var binding = bind(server, instance, null);
            server.serve(release(server, grant, 2, grant));
            var skipped = new UpdateEngine().update(request(instance, binding), null);
            assertEquals(UpdateOutcome.UPDATED, skipped.outcome());
            assertEquals("new-game-2", Files.readString(instance.resolve("mods/demo.jar")));
            assertTrue(skipped.packNotice().contains("minecraft-files/versions/Demo"), skipped.packNotice());
            assertFalse(Files.exists(other.resolve("guide.txt")));
            assertFalse(Files.exists(instance.resolve(ContentRoots.GRANT)));
            assertEquals(UpdateOutcome.UP_TO_DATE, new UpdateEngine().update(request(instance, binding), null).outcome());
            Path pack = temporary.resolve("改好的整合包");
            Files.createDirectories(pack.resolve(".minecraft/versions"));
            Path fixed = Files.move(instance, pack.resolve(".minecraft/versions/Demo"));
            var adopted = new UpdateEngine().update(request(fixed, binding), null);
            assertEquals(UpdateOutcome.UPDATED, adopted.outcome());
            assertNull(adopted.packNotice());
            assertEquals("new-guide-2", Files.readString(pack.resolve("guide.txt")));
        }
    }

    @Test void sharedPackWaitsForOtherRegisteredGames() throws Exception {
        try (var server = new TestUpdateServer()) {
            Path pack = Files.createDirectories(temporary.resolve("pack"));
            Path first = Files.createDirectories(pack.resolve(".minecraft/versions/First"));
            Path second = Files.createDirectories(pack.resolve(".minecraft/versions/Second"));
            var spec = new PackRootSpec(UUID.randomUUID().toString(), "../../..");
            var firstBinding = bind(server, first, spec); var secondBinding = bind(server, second, spec);
            try (var registered = PackUpdateLock.acquire(second, secondBinding)) { }
            try (var game = GameUpdateLock.tryAcquire(second.resolve(".dreamingfish-bootstrap/game.lock"))) {
                assertNotNull(game);
                try (var outer = PackUpdateLock.acquire(first, firstBinding)) {
                    assertEquals(UpdateErrorCode.GAME_RUNNING, assertThrows(UpdateException.class, outer::requireWritable).code());
                }
            }
            try (var available = PackUpdateLock.acquire(first, firstBinding)) { assertNotNull(available); }
        }
    }
}
