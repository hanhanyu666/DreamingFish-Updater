package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CorrectionMode;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.dreamingfish.updater.protocol.MaintenancePreset.DEFAULT_CONFIG;
import static cn.dreamingfish.updater.protocol.MaintenancePreset.INITIAL;
import static cn.dreamingfish.updater.protocol.MaintenancePreset.REQUIRED;
import static cn.dreamingfish.updater.protocol.MaintenancePreset.SYNC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engine behavior of releases declaring {@code maintenance-policy-v2}. */
class MaintenancePolicyEngineTest {
    private static final Set<String> CAPABILITIES = Set.of(
            ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC,
            ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
            ProtocolConstants.CAPABILITY_RELEASED_PATHS,
            ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY);

    @TempDir
    Path temporary;

    /** 场景 1: v1 ABC, v2 +D, the player self-manages B, v3 deletes B. */
    @Test
    void ownerDeletionBacksUpASelfManagedCopyUnlessRetained() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            var b = server.jarMod("mods/b.jar", "b", "1", "B");
            var c = server.jarMod("mods/c.jar", "c", "1", "C");
            var d = server.jarMod("mods/d.jar", "d", "1", "D");
            LocalFileOverrides selfManagesB = selfManaged("b");

            Path instance = instance(server, server.policy(1, "r1")
                    .file(a, SYNC).file(b, SYNC).file(c, SYNC).build(), "deleted");
            serve(server, server.policy(2, "r2").file(a, SYNC).file(b, SYNC).file(c, SYNC)
                    .file(d, SYNC).build());
            update(instance, server, selfManagesB);
            serve(server, server.policy(3, "r3").file(a, SYNC).file(c, SYNC).file(d, SYNC).build());
            UpdateResult deleted = update(instance, server, selfManagesB);

            assertEquals(Set.of("a.jar", "c.jar", "d.jar"), mods(instance));
            ArchivedFile backup = only(deleted.archived());
            assertEquals("mods/b.jar", backup.originalPath());
            assertEquals(ArchiveReason.REMOVED_SELF_MANAGED, backup.reason());
            assertArrayEquals(b.bytes(), Files.readAllBytes(
                    deleted.archiveDirectory().resolve("mods/b.jar")));

            Path keeps = instance(server, server.policy(1, "r1")
                    .file(a, SYNC).file(b, SYNC).file(c, SYNC).build(), "retained");
            serve(server, server.policy(3, "r3").file(a, SYNC).file(c, SYNC).file(d, SYNC)
                    .retained("mods/b.jar").build());
            UpdateResult retained = update(keeps, server, selfManagesB);
            assertEquals(Set.of("a.jar", "b.jar", "c.jar", "d.jar"), mods(keeps));
            assertTrue(retained.archived().isEmpty());
        }
    }

    /** 场景 2: the player self-manages B and deletes it; v3 updates B under a new file name. */
    @Test
    void selfManagedModStaysAbsentAcrossRenamedUpdates() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            var b1 = server.jarMod("mods/b-1.jar", "b", "1", "B1");
            var b2 = server.jarMod("mods/b-2.jar", "b", "2", "B2");
            var c = server.jarMod("mods/c.jar", "c", "1", "C");
            var d = server.jarMod("mods/d.jar", "d", "1", "D");
            Path instance = instance(server, server.policy(1, "r1")
                    .file(a, SYNC).file(b1, SYNC).file(c, SYNC).build(), "renamed");
            Files.delete(instance.resolve("mods/b-1.jar"));

            serve(server, server.policy(3, "r3").file(a, SYNC).file(b2, SYNC).file(c, SYNC)
                    .file(d, SYNC).build());
            UpdateResult result = update(instance, server, selfManaged("b"));

            assertEquals(Set.of("a.jar", "c.jar", "d.jar"), mods(instance));
            assertEquals(List.of(Path.of("mods/b-2.jar")), result.skippedSelfManagedPaths());
            assertTrue(result.archived().isEmpty());
        }
    }

    /** 场景 3: the player installed D; the owner starts publishing D. */
    @Test
    void takeoverKeepsOneCopyOfAModAndBacksUpThePlayersVersion() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            var d = server.jarMod("mods/d-1.jar", "d", "1", "official");
            var serverConfig = server.file("config/takeover.toml", "server=true", FilePolicy.ENFORCED);
            Path instance = instance(server, server.policy(1, "r1").file(a, SYNC).build(), "takeover");
            byte[] playerJar = TestUpdateServer.jarBytes("d", "0.9", "player build");
            Files.write(instance.resolve("mods/d-player.jar"), playerJar);
            Files.createDirectories(instance.resolve("config"));
            Files.writeString(instance.resolve("config/takeover.toml"), "player=true");

            serve(server, server.policy(2, "r2").file(a, SYNC).file(d, SYNC)
                    .file(serverConfig, SYNC).build());
            UpdateResult result = update(instance, server, LocalFileOverrides.NONE);

            assertEquals(Set.of("a.jar", "d-1.jar"), mods(instance));
            assertEquals("server=true", Files.readString(instance.resolve("config/takeover.toml")));
            Map<String, ArchivedFile> archived = byPath(result.archived());
            assertEquals(ArchiveReason.DUPLICATE, archived.get("mods/d-player.jar").reason());
            assertEquals("mods/d-1.jar", archived.get("mods/d-player.jar").detail());
            assertEquals("d", archived.get("mods/d-player.jar").componentId());
            assertEquals(ArchiveReason.TAKEOVER, archived.get("config/takeover.toml").reason());
            assertEquals("player=true", Files.readString(
                    result.archiveDirectory().resolve("config/takeover.toml")));
            assertTrue(result.unmanagedMods().isEmpty());
        }
    }

    @Test
    void initialFilesAreProvidedOnceAndThenBelongToThePlayer() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            var options = server.file("options.txt", "guiScale:2", FilePolicy.ENFORCED);
            var servers = server.file("servers.dat", "official server", FilePolicy.ENFORCED);
            Path instance = instance(server, server.policy(1, "r1").file(a, SYNC).build(), "initial");
            Files.writeString(instance.resolve("servers.dat"), "player servers");

            serve(server, server.policy(2, "r2").file(a, SYNC).file(options, INITIAL)
                    .file(servers, INITIAL).build());
            update(instance, server, LocalFileOverrides.NONE);
            assertEquals("guiScale:2", Files.readString(instance.resolve("options.txt")));
            assertEquals("player servers", Files.readString(instance.resolve("servers.dat")));

            Files.delete(instance.resolve("options.txt"));
            var newerOptions = server.file("options.txt", "guiScale:3", FilePolicy.ENFORCED);
            serve(server, server.policy(3, "r3").file(a, SYNC).file(newerOptions, INITIAL)
                    .file(servers, INITIAL).build());
            UpdateResult again = update(instance, server, LocalFileOverrides.NONE);
            assertFalse(Files.exists(instance.resolve("options.txt")));
            assertEquals("player servers", Files.readString(instance.resolve("servers.dat")));
            assertTrue(again.installedPaths().isEmpty());

            // The removed file stays with the player when the owner stops publishing it.
            serve(server, server.policy(4, "r4").file(a, SYNC).build());
            update(instance, server, LocalFileOverrides.NONE);
            assertEquals("player servers", Files.readString(instance.resolve("servers.dat")));
        }
    }

    @Test
    void defaultConfigurationsFollowTheOwnerOnlyWhileUnmodified() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a1 = server.file("config/a.toml", "a=1", FilePolicy.ENFORCED);
            var b1 = server.file("config/b.toml", "b=1", FilePolicy.ENFORCED);
            var c1 = server.file("config/c.toml", "c=1", FilePolicy.ENFORCED);
            Path instance = instance(server, server.policy(1, "r1").file(a1, DEFAULT_CONFIG)
                    .file(b1, DEFAULT_CONFIG).file(c1, DEFAULT_CONFIG).build(), "defaults");
            Files.writeString(instance.resolve("config/b.toml"), "b=player");
            Files.delete(instance.resolve("config/c.toml"));

            var a2 = server.file("config/a.toml", "a=2", FilePolicy.ENFORCED);
            var b2 = server.file("config/b.toml", "b=2", FilePolicy.ENFORCED);
            var c2 = server.file("config/c.toml", "c=2", FilePolicy.ENFORCED);
            serve(server, server.policy(2, "r2").file(a2, DEFAULT_CONFIG)
                    .file(b2, DEFAULT_CONFIG).file(c2, DEFAULT_CONFIG).build());
            UpdateResult result = update(instance, server, LocalFileOverrides.NONE);
            assertEquals("a=2", Files.readString(instance.resolve("config/a.toml")));
            assertEquals("b=player", Files.readString(instance.resolve("config/b.toml")));
            assertEquals("c=2", Files.readString(instance.resolve("config/c.toml")));
            assertEquals(List.of(Path.of("config/b.toml")), result.keptModifiedPaths());
            assertTrue(result.archived().isEmpty());

            UpdateResult reset = update(instance, server,
                    LocalFileOverrides.NONE.withResetRequests(List.of("config/b.toml")));
            assertEquals("b=2", Files.readString(instance.resolve("config/b.toml")));
            assertEquals(ArchiveReason.RESET_DEFAULT, only(reset.archived()).reason());
            assertEquals(List.of(Path.of("config/b.toml")), reset.resetPaths());
            assertEquals("b=player", Files.readString(
                    reset.archiveDirectory().resolve("config/b.toml")));

            // Offline use accepts a modified default configuration.
            Files.writeString(instance.resolve("config/a.toml"), "a=player");
            server.unavailable = true;
            assertEquals(UpdateOutcome.OFFLINE_ALLOWED,
                    update(instance, server, LocalFileOverrides.NONE).outcome());
        }
    }

    @Test
    void optionalGroupsFollowTheirDefaultUntilThePlayerChooses() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            var iris = server.jarMod("mods/iris.jar", "iris", "1.7", "shaders");
            var pack = server.file("shaderpacks/pack.zip", "pack", FilePolicy.ENFORCED);
            Path instance = instance(server, server.policy(1, "r1").file(a, SYNC).build(), "optional");
            ReleaseManifest withGroup = server.policy(2, "r2").file(a, SYNC)
                    .file(iris, SYNC, "visuals").file(pack, SYNC, "visuals")
                    .group("visuals", "美化包", false).build();
            serve(server, withGroup);

            update(instance, server, LocalFileOverrides.NONE);
            assertFalse(Files.exists(instance.resolve("mods/iris.jar")));
            assertFalse(Files.exists(instance.resolve("shaderpacks/pack.zip")));

            LocalFileOverrides enabled = LocalFileOverrides.NONE.withGroupChoices(Map.of("visuals", true));
            update(instance, server, enabled);
            assertTrue(Files.isRegularFile(instance.resolve("mods/iris.jar")));
            assertTrue(Files.isRegularFile(instance.resolve("shaderpacks/pack.zip")));

            // Switching the group off leaves removal of mods to the local mod manager.
            LocalFileOverrides disabled = LocalFileOverrides.NONE.withGroupChoices(Map.of("visuals", false));
            Files.delete(instance.resolve("mods/iris.jar"));
            assertEquals(UpdateOutcome.UP_TO_DATE, update(instance, server, disabled).outcome());
            server.unavailable = true;
            assertEquals(UpdateOutcome.OFFLINE_ALLOWED, update(instance, server, disabled).outcome());
        }
    }

    @Test
    void cleanupDirectoriesAreIndependentOfPresetsAndDeletePristineOfficialCopies() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            var a2 = server.jarMod("mods/a.jar", "a", "2", "A2");
            var b = server.jarMod("mods/b.jar", "b", "1", "B");
            var c = server.jarMod("mods/c.jar", "c", "1", "C");
            var core = server.jarMod("mods/core.jar", "core", "1", "core");
            Path instance = instance(server, server.policy(1, "r1").file(a, SYNC).file(b, SYNC)
                    .file(core, REQUIRED).cleanup("mods").build(), "cleanup");
            byte[] personalA = TestUpdateServer.jarBytes("a", "custom", "player");
            Files.write(instance.resolve("mods/a.jar"), personalA);
            Files.writeString(instance.resolve("mods/extra.jar"), "extra");

            serve(server, server.policy(2, "r2").file(a2, SYNC).file(c, SYNC).file(core, REQUIRED)
                    .cleanup("mods").build());
            LocalFileOverrides choices = new LocalFileOverrides(Set.of(), Set.of(), Set.of(),
                    Set.of("a", "core"), Map.of(), Set.of());
            UpdateResult result = update(instance, server, choices);

            assertArrayEquals(personalA, Files.readAllBytes(instance.resolve("mods/a.jar")));
            assertFalse(Files.exists(instance.resolve("mods/b.jar")));
            assertTrue(Files.isRegularFile(instance.resolve("mods/c.jar")));
            assertTrue(Files.isRegularFile(instance.resolve("mods/core.jar")));
            assertEquals(List.of(Path.of("mods/b.jar")), result.deletedPaths());
            ArchivedFile extra = only(result.archived());
            assertEquals("mods/extra.jar", extra.originalPath());
            assertEquals(ArchiveReason.CLEANUP, extra.reason());
        }
    }

    @Test
    void withdrawalsFindRenamedCopiesByContentOrModVersionAndPersist() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var a = server.jarMod("mods/a.jar", "a", "1", "A");
            var bad = server.jarMod("mods/jei-15.2.jar", "jei", "15.2", "broken build");
            Path instance = instance(server, server.policy(1, "r1").file(a, SYNC).file(bad, SYNC)
                    .build(), "withdrawal");
            serve(server, server.policy(2, "r2").file(a, SYNC).released("mods/jei-15.2.jar").build());
            update(instance, server, LocalFileOverrides.NONE);
            Files.move(instance.resolve("mods/jei-15.2.jar"), instance.resolve("mods/my-jei.jar"));
            Files.write(instance.resolve("mods/jei-copy.jar"),
                    TestUpdateServer.jarBytes("jei", "15.2", "repackaged elsewhere"));
            byte[] fixed = TestUpdateServer.jarBytes("jei", "15.3", "fixed");
            Files.write(instance.resolve("mods/jei-15.3.jar"), fixed);

            serve(server, server.policy(3, "r3").file(a, SYNC).released("mods/jei-15.2.jar")
                    .withdraw("jei-15-2", "这个版本会导致服务器崩溃", bad).build());
            UpdateResult result = update(instance, server, LocalFileOverrides.NONE);
            assertEquals(Set.of("a.jar", "jei-15.3.jar"), mods(instance));
            Map<String, ArchivedFile> archived = byPath(result.archived());
            assertEquals(Set.of("mods/my-jei.jar", "mods/jei-copy.jar"), archived.keySet());
            assertTrue(archived.values().stream().allMatch(file ->
                    file.reason() == ArchiveReason.WITHDRAWN
                            && file.detail().equals("这个版本会导致服务器崩溃")));

            Files.write(instance.resolve("mods/again.jar"), bad.bytes());
            UpdateResult again = update(instance, server, LocalFileOverrides.NONE);
            assertEquals("mods/again.jar", only(again.archived()).originalPath());
            assertFalse(Files.exists(instance.resolve("mods/again.jar")));
        }
    }

    @Test
    void correctionsReplaceKnownBadVersionsOrOverwriteOnce() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var x1 = server.file("config/x.toml", "x=1", FilePolicy.ENFORCED);
            var y1 = server.file("config/y.toml", "y=1", FilePolicy.ENFORCED);
            var z1 = server.file("config/z.toml", "z=1", FilePolicy.ENFORCED);
            var yBad = server.file("config/y.toml", "y=broken", FilePolicy.ENFORCED);
            Path instance = instance(server, server.policy(1, "r1").file(x1, DEFAULT_CONFIG)
                    .file(y1, DEFAULT_CONFIG).file(z1, DEFAULT_CONFIG).build(), "corrections");
            Files.writeString(instance.resolve("config/x.toml"), "x=player");
            Files.writeString(instance.resolve("config/y.toml"), "y=broken");
            Files.writeString(instance.resolve("config/z.toml"), "z=player");

            var x2 = server.file("config/x.toml", "x=2", FilePolicy.ENFORCED);
            var y2 = server.file("config/y.toml", "y=2", FilePolicy.ENFORCED);
            var z2 = server.file("config/z.toml", "z=2", FilePolicy.ENFORCED);
            serve(server, server.policy(2, "r2").file(x2, DEFAULT_CONFIG)
                    .file(y2, DEFAULT_CONFIG).file(z2, DEFAULT_CONFIG)
                    .correct("fix-x", "config/x.toml", CorrectionMode.ONCE, "修复渲染距离")
                    .correct("fix-y", "config/y.toml", CorrectionMode.KNOWN_BAD, "修复崩溃", yBad)
                    .correct("fix-z", "config/z.toml", CorrectionMode.KNOWN_BAD, "修复崩溃", yBad)
                    .build());
            UpdateResult result = update(instance, server, LocalFileOverrides.NONE);
            assertEquals("x=2", Files.readString(instance.resolve("config/x.toml")));
            assertEquals("y=2", Files.readString(instance.resolve("config/y.toml")));
            assertEquals("z=player", Files.readString(instance.resolve("config/z.toml")));
            Map<String, ArchivedFile> archived = byPath(result.archived());
            assertEquals(Set.of("config/x.toml", "config/y.toml"), archived.keySet());
            assertEquals("修复渲染距离", archived.get("config/x.toml").detail());

            Files.writeString(instance.resolve("config/x.toml"), "x=player-again");
            update(instance, server, LocalFileOverrides.NONE);
            assertEquals("x=player-again", Files.readString(instance.resolve("config/x.toml")));
            MaintenanceState state = new JsonCodec().read(instance.resolve(
                    "DreamingFishUpdater/state/maintenance-state.json"), MaintenanceState.class);
            assertEquals(List.of("fix-x"), state.appliedCorrections());
        }
    }

    @Test
    void removedModifiedFilesAreBackedUpWhilePristineCopiesAreDeleted() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var modified = server.file("config/old.toml", "old=1", FilePolicy.ENFORCED);
            var pristine = server.file("config/pristine.toml", "p=1", FilePolicy.ENFORCED);
            var kept = server.file("config/kept.toml", "k=1", FilePolicy.ENFORCED);
            Path instance = instance(server, server.policy(1, "r1").file(modified, SYNC)
                    .file(pristine, SYNC).file(kept, SYNC).build(), "removed");
            Files.writeString(instance.resolve("config/old.toml"), "old=player");

            serve(server, server.policy(2, "r2").file(kept, SYNC).build());
            UpdateResult result = update(instance, server, LocalFileOverrides.NONE);
            assertFalse(Files.exists(instance.resolve("config/old.toml")));
            assertFalse(Files.exists(instance.resolve("config/pristine.toml")));
            assertEquals(List.of(Path.of("config/pristine.toml")), result.deletedPaths());
            ArchivedFile backup = only(result.archived());
            assertEquals(ArchiveReason.REMOVED_MODIFIED, backup.reason());
            assertEquals("old=player", Files.readString(
                    result.archiveDirectory().resolve("config/old.toml")));
        }
    }

    @Test
    void synchronizedFilesBackUpPlayerChangesBeforeRestoringThem() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            var config = server.file("config/sync.toml", "sync=1", FilePolicy.ENFORCED);
            ReleaseManifest release = server.policy(1, "r1").file(config, SYNC).build();
            Path instance = instance(server, release, "sync");
            Files.writeString(instance.resolve("config/sync.toml"), "sync=player");
            serve(server, release);
            UpdateResult result = update(instance, server, LocalFileOverrides.NONE);
            assertEquals("sync=1", Files.readString(instance.resolve("config/sync.toml")));
            assertEquals(ArchiveReason.REPLACED_MODIFIED, only(result.archived()).reason());
        }
    }

    private Path instance(TestUpdateServer server, ReleaseManifest baseline, String name)
            throws Exception {
        Path instance = Files.createDirectories(temporary.resolve(name));
        server.bundle(instance, baseline, true);
        return instance;
    }

    private static void serve(TestUpdateServer server, ReleaseManifest manifest) {
        server.serve(manifest);
    }

    private static UpdateResult update(Path instance, TestUpdateServer server,
                                       LocalFileOverrides choices) {
        return new UpdateEngine().update(new UpdateRequest(instance,
                instance.resolve("DreamingFishUpdater"), server.binding(), "0.1.0", CAPABILITIES,
                null, null, null, CancellationToken.NEVER, choices), null);
    }

    private static LocalFileOverrides selfManaged(String... componentIds) {
        return new LocalFileOverrides(Set.of(), Set.of(), Set.of(), Set.of(componentIds),
                Map.of(), Set.of());
    }

    private static Set<String> mods(Path instance) throws Exception {
        try (var files = Files.list(instance.resolve("mods"))) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static ArchivedFile only(List<ArchivedFile> files) {
        assertEquals(1, files.size(), "archived: " + files);
        return files.getFirst();
    }

    private static Map<String, ArchivedFile> byPath(List<ArchivedFile> files) {
        Map<String, ArchivedFile> result = new java.util.HashMap<>();
        files.forEach(file -> result.put(file.originalPath(), file));
        return result;
    }
}
