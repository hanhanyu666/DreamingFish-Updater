package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimplifiedEngineTest {
    @TempDir Path temporary;

    @Test void aKnownRemovalIsAlsoEnforcedOfflineUsingLocalBackups() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var d = server.jarMod("mods/d.jar", "d", "1", "D");
            var e = server.jarMod("mods/e.jar", "e", "1", "E");
            var one = modern(server.policy(1, "r1").file(d, MaintenancePreset.SYNC).file(e, MaintenancePreset.SYNC).build(), List.of());
            Path instance = Files.createDirectories(temporary.resolve("offline"));
            server.bundle(instance, one, true);
            server.serve(one);
            update(instance, server, LocalFileOverrides.NONE);
            var removal = new Withdrawal("remove-d", "移除D", Instant.now(), List.of(
                    new WithdrawalItem(d.sha256(), d.bytes().length, d.path(), "d", "1")), Withdrawal.Kind.REMOVAL);
            server.serve(modern(server.policy(2, "r2").file(e, MaintenancePreset.SYNC).build(), List.of(removal)));
            update(instance, server, LocalFileOverrides.NONE);
            Files.write(instance.resolve("mods/d.jar"), d.bytes());
            server.unavailable = true;
            var offline = update(instance, server, LocalFileOverrides.NONE);
            assertEquals(UpdateOutcome.OFFLINE_ALLOWED, offline.outcome());
            assertFalse(Files.exists(instance.resolve("mods/d.jar")));
            assertEquals(ArchiveReason.OWNER_REMOVED, offline.archived().getFirst().reason());
        }
    }

    @Test void firstProvisionDoesNotReinstallAfterRenameAndHonorsExplicitRestore() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var d1 = server.jarMod("mods/d-1.jar", "con", "1", "old");
            var d2 = server.jarMod("mods/d-2.jar", "con", "2", "new");
            var one = modern(server.policy(1, "r1").file(d1, MaintenancePreset.INITIAL).build(), List.of());
            Path instance = Files.createDirectories(temporary.resolve("initial"));
            server.bundle(instance, one, true);
            server.serve(one);
            update(instance, server, LocalFileOverrides.NONE);
            var two = modern(server.policy(2, "r2").file(d2, MaintenancePreset.INITIAL).build(), List.of());
            server.serve(two);
            update(instance, server, LocalFileOverrides.NONE);
            assertTrue(Files.exists(instance.resolve("mods/d-1.jar")));
            assertFalse(Files.exists(instance.resolve("mods/d-2.jar")));
            var switched = update(instance, server, LocalFileOverrides.NONE.withResetRequests(List.of("mods/d-2.jar")));
            assertFalse(Files.exists(instance.resolve("mods/d-1.jar")));
            assertTrue(Files.exists(instance.resolve("mods/d-2.jar")));
            assertEquals(ArchiveReason.RESET_DEFAULT, switched.archived().getFirst().reason());
            Files.delete(instance.resolve("mods/d-2.jar"));
            update(instance, server, LocalFileOverrides.NONE);
            assertFalse(Files.exists(instance.resolve("mods/d-2.jar")));
            var restored = update(instance, server, LocalFileOverrides.NONE.withResetRequests(List.of("mods/d-2.jar")));
            assertTrue(Files.exists(instance.resolve("mods/d-2.jar")));
            assertEquals(List.of(Path.of("mods/d-2.jar")), restored.resetPaths());
        }
    }

    @Test void anAcceptedSimplifiedProjectCannotSilentlyDowngradeItsRules() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var d = server.jarMod("mods/d.jar", "d", "1", "D");
            var one = modern(server.policy(1, "r1").file(d, MaintenancePreset.SYNC).build(), List.of());
            Path instance = Files.createDirectories(temporary.resolve("downgrade"));
            server.bundle(instance, one, true);
            server.serve(one);
            update(instance, server, LocalFileOverrides.NONE);
            server.serve(server.policy(2, "r2").file(d, MaintenancePreset.SYNC).build());
            assertEquals(UpdateErrorCode.INVALID_MANIFEST,
                    assertThrows(UpdateException.class, () -> update(instance, server, LocalFileOverrides.NONE)).code());
        }
    }

    @Test void aKnownBadPausedCopyCausesTheCorrectFileToBeDownloadedDespiteTheDisableSwitch() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var bad = server.jarMod("mods/d-1.jar", "d", "1", "bad");
            var correct = server.jarMod("mods/d-2.jar", "d", "2", "good");
            var one = modern(server.policy(1, "r1").file(bad, MaintenancePreset.INITIAL).build(), List.of());
            Path instance = Files.createDirectories(temporary.resolve("paused-repair"));
            server.bundle(instance, one, true);
            server.serve(one);
            update(instance, server, LocalFileOverrides.NONE);
            Files.delete(instance.resolve("mods/d-1.jar"));
            var two = modern(server.policy(2, "r2").file(correct, MaintenancePreset.INITIAL)
                    .correct("fix-d", "mods/d-2.jar", CorrectionMode.KNOWN_BAD, "修复", bad).build(), List.of());
            server.serve(two);
            LocalFileOverrides paused = new LocalFileOverrides(Set.of("d"), Set.of())
                    .withStoredModHashes(Map.of("d", Set.of(bad.sha256())));
            update(instance, server, paused);
            assertArrayEquals(correct.bytes(), Files.readAllBytes(instance.resolve("mods/d-2.jar")));
        }
    }

    private static ReleaseManifest modern(ReleaseManifest release, List<Withdrawal> withdrawals) {
        return new ReleaseManifest(release.schemaVersion(), release.projectId(), release.releaseId(), release.sequence(),
                release.createdAt(), release.displayVersion(), "0.2.0", release.changelog(), ProtocolConstants.RELEASE_CAPABILITIES,
                List.of(), List.of(), release.releasedPaths(), release.branding(), release.files(), release.cleanupDirectories(),
                release.optionalGroups(), List.of(), withdrawals, release.corrections());
    }
    private static UpdateResult update(Path instance, TestUpdateServer server, LocalFileOverrides choices) {
        return new UpdateEngine().update(new UpdateRequest(instance, instance.resolve("DreamingFishUpdater"), server.binding(),
                "0.2.0", ProtocolConstants.RELEASE_CAPABILITIES, null, null, null, null, choices), null);
    }
}
