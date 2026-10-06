package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.engine.UpdateEngine;
import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.CorrectionMode;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenancePolicyServiceTest {
    @TempDir
    Path temporary;

    @Test
    void migratesLegacyForcedSettingsWhenReadingStoredRules() {
        String stored = """
                {"rules":[{"glob":"logs/**","action":"EXCLUDE"}],
                 "forcedSyncDirectories":["mods"],
                 "forcedSyncFiles":["config/core.toml"]}
                """;
        ProjectRules rules = new cn.dreamingfish.updater.protocol.JsonCodec()
                .read(stored.getBytes(StandardCharsets.UTF_8), ProjectRules.class);
        assertEquals(List.of("mods"), rules.cleanupDirectories());
        assertEquals(MaintenancePreset.REQUIRED, rules.presetFor("mods/a.jar"));
        assertEquals(MaintenancePreset.REQUIRED, rules.presetFor("config/core.toml"));
        assertEquals(MaintenancePreset.SYNC, rules.presetFor("config/other.toml"));
        assertTrue(rules.legacyForcedSyncDirectories().isEmpty());
        assertEquals(List.of("mods"), rules.lockedCleanupDirectories());
        assertEquals(List.of("config/core.toml"), rules.requiredFiles());

        ProjectRules roundTrip = new cn.dreamingfish.updater.protocol.JsonCodec()
                .read(new cn.dreamingfish.updater.protocol.JsonCodec().write(rules), ProjectRules.class);
        assertEquals(rules, roundTrip);

        ProjectRules relaxed = rules.withLegacyForcedSyncDirectories(List.of());
        assertTrue(relaxed.cleanupDirectories().isEmpty());
        assertEquals(MaintenancePreset.SYNC, relaxed.presetFor("mods/a.jar"));
    }

    @Test
    void theMostSpecificPresetWinsAndModsJoinGroupsByModId() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        write(fixture.source.resolve("config/a.toml"), "a");
        write(fixture.source.resolve("config/client/b.toml"), "b");
        write(fixture.source.resolve("config/client/keep.toml"), "keep");
        write(fixture.source.resolve("shaderpacks/pack.zip"), "pack");
        writeJar(fixture.source.resolve("mods/iris-1.7.jar"), "iris", "1.7");
        policies.setPreset("demo", "config", true, MaintenancePreset.SYNC);
        policies.setPreset("demo", "config/a.toml", false, MaintenancePreset.REQUIRED);
        assertThrows(ManagementException.class, () -> policies.setPreset("demo", "config/client", true, MaintenancePreset.DEFAULT_CONFIG));
        policies.setPreset("demo", "config/client", true, MaintenancePreset.INITIAL);
        policies.setPreset("demo", "config/client/keep.toml", false, MaintenancePreset.INITIAL);
        policies.saveOptionalGroup("demo", new OptionalGroupRule("visuals", "美化包",
                "低配电脑可以关闭", false, List.of("iris"), List.of(), List.of("shaderpacks")));

        Map<String, ScannedFile> files = byPath(fixture.scanner.createPreview("demo").files());
        assertEquals(MaintenancePreset.REQUIRED, files.get("config/a.toml").preset());
        assertEquals(MaintenancePreset.INITIAL, files.get("config/client/b.toml").preset());
        assertEquals(MaintenancePreset.INITIAL, files.get("config/client/keep.toml").preset());
        assertEquals("visuals", files.get("mods/iris-1.7.jar").optionalGroup());
        assertEquals("1.7", files.get("mods/iris-1.7.jar").version());
        assertEquals("visuals", files.get("shaderpacks/pack.zip").optionalGroup());

        // A renamed mod update stays in its group.
        Files.delete(fixture.source.resolve("mods/iris-1.7.jar"));
        writeJar(fixture.source.resolve("mods/iris-1.8.jar"), "iris", "1.8");
        assertEquals("visuals", byPath(fixture.scanner.createPreview("demo").files())
                .get("mods/iris-1.8.jar").optionalGroup());

        // Required content cannot be optional, and a file cannot belong to two groups.
        assertThrows(ManagementException.class, () -> policies.setPreset("demo", "shaderpacks", true, MaintenancePreset.REQUIRED));
        policies.setPreset("demo", "shaderpacks", true, null);
        policies.saveOptionalGroup("demo", new OptionalGroupRule("packs", "光影包", "", true,
                List.of(), List.of("shaderpacks/pack.zip"), List.of()));
        policies.saveOptionalGroup("demo", new OptionalGroupRule("visuals", "美化包", "", false,
                List.of("iris"), List.of("shaderpacks/pack.zip"), List.of()));
        assertThrows(ManagementException.class, () -> fixture.scanner.createPreview("demo"));
    }

    @Test
    void forcedFoldersRefuseOptionalExceptionsAndOrdinaryFoldersAllowStrongFiles() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        writeJar(fixture.source.resolve("mods/core.jar"), "core", "1");
        writeJar(fixture.source.resolve("mods/iris.jar"), "iris", "1.7");
        policies.setPreset("demo", "mods", true, MaintenancePreset.REQUIRED);
        OptionalGroupRule group = policies.defineOptionalGroup("demo", null, "光影", "", false);
        assertThrows(ManagementException.class, () -> policies.setGroupMembers("demo", group.id(),
                List.of(new ProjectPolicyService.GroupMember("mods/iris.jar", false)), true));
        assertThrows(ManagementException.class, () -> policies.setPreset("demo", "mods/iris.jar", false, MaintenancePreset.INITIAL));
        policies.setPreset("demo", "mods", true, MaintenancePreset.SYNC);
        policies.setPreset("demo", "mods/core.jar", false, MaintenancePreset.REQUIRED);
        policies.setGroupMembers("demo", group.id(), List.of(new ProjectPolicyService.GroupMember("mods/iris.jar", false)), true);
        Map<String, ScannedFile> files = byPath(fixture.scanner.createPreview("demo").files());
        assertEquals(MaintenancePreset.REQUIRED, files.get("mods/core.jar").preset());
        assertEquals(MaintenancePreset.SYNC, files.get("mods/iris.jar").preset());
        assertEquals(group.id(), files.get("mods/iris.jar").optionalGroup());
        assertTrue(fixture.database.requireProject("demo").rules().cleanupDirectories().isEmpty());
    }


    @Test
    void publishesThreePresetsAndCarriesReleasedOwnership() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        writeJar(fixture.source.resolve("mods/core.jar"), "core", "1");
        writeJar(fixture.source.resolve("mods/map.jar"), "map", "1");
        writeJar(fixture.source.resolve("mods/iris.jar"), "iris", "1.7");
        write(fixture.source.resolve("options.txt"), "guiScale:2");
        policies.setPreset("demo", "mods/core.jar", false, MaintenancePreset.REQUIRED);
        policies.setPreset("demo", "options.txt", false, MaintenancePreset.INITIAL);
        policies.saveOptionalGroup("demo", new OptionalGroupRule("visuals", "美化包", "", false,
                List.of("iris"), List.of(), List.of()));
        PublishPreview first = fixture.scanner.createPreview("demo");
        assertTrue(first.requiresPlayerProgramAcknowledgement());
        ReleaseManifest r1 = fixture.database.readManifest(fixture.publisher.publish("demo", "1.0.0", "0.1.0", "first"));
        assertEquals(Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY,
                ProtocolConstants.CAPABILITY_SIMPLIFIED_MAINTENANCE), r1.requiredCapabilities());
        assertEquals(ScanService.POLICY_PLAYER_VERSION, r1.minimumPlayerVersion());
        assertTrue(r1.cleanupDirectories().isEmpty());
        assertEquals(MaintenancePreset.INITIAL, byManifestPath(r1.files()).get("options.txt").preset());
        Files.delete(fixture.source.resolve("mods/map.jar"));
        fixture.scanner.createPreview("demo");
        fixture.scanner.decideRemovals("demo", List.of(new RemovalDecision("mods/map.jar", RemovalAction.RELEASE)));
        ReleaseManifest r2 = fixture.database.readManifest(fixture.publisher.publish("demo", "2.0.0", "0.3.0", "release"));
        assertEquals(List.of("mods/map.jar"), r2.releasedPaths());
        assertTrue(r2.retainedSelfManagedPaths().isEmpty());
        fixture.scanner.createPreview("demo");
        assertEquals(r2.releasedPaths(), fixture.database.readManifest(fixture.publisher.publish("demo", "3.0.0", "0.3.0", "carry")).releasedPaths());
        Files.delete(fixture.source.resolve("mods/iris.jar"));
        fixture.scanner.createPreview("demo");
        fixture.scanner.decideRemovals("demo", List.of(new RemovalDecision("mods/iris.jar", RemovalAction.DELETE_KEEP_SELF_MANAGED)));
        assertThrows(ManagementException.class, () -> fixture.publisher.publish("demo", "4.0.0", "0.3.0", "obsolete choice"));
    }


    @Test
    void withdrawsHistoricalVersionsAndRefusesConflictingRollbacks() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        writeJar(fixture.source.resolve("mods/jei-15.2.jar"), "jei", "15.2");
        fixture.scanner.createPreview("demo");
        StoredRelease broken = fixture.publisher.publish("demo", "1.0.0", "0.3.0", "jei 15.2");
        String badHash = fixture.database.readManifest(broken).files().getFirst().sha256();

        assertThrows(ManagementException.class, () -> policies.withdraw("demo", "崩服",
                List.of(new ProjectPolicyService.VersionReference("mods/jei-15.2.jar", "0".repeat(64)))));

        Files.delete(fixture.source.resolve("mods/jei-15.2.jar"));
        writeJar(fixture.source.resolve("mods/jei-15.3.jar"), "jei", "15.3");
        fixture.scanner.createPreview("demo");
        fixture.scanner.decideRemovals("demo", List.of(
                new RemovalDecision("mods/jei-15.2.jar", RemovalAction.RELEASE)));
        fixture.publisher.publish("demo", "2.0.0", "0.3.0", "jei 15.3");

        var withdrawal = policies.withdraw("demo", "这个版本会导致服务器崩溃",
                List.of(new ProjectPolicyService.VersionReference("mods/jei-15.2.jar", badHash)));
        assertEquals("jei", withdrawal.items().getFirst().componentId());
        assertEquals("15.2", withdrawal.items().getFirst().version());
        var history = policies.history("demo").stream()
                .filter(entry -> entry.path().equals("mods/jei-15.2.jar")).findFirst().orElseThrow();
        assertEquals(withdrawal.id(), history.versions().getFirst().withdrawnBy());

        PublishPreview preview = fixture.scanner.createPreview("demo");
        assertTrue(preview.policyChanges().contains(
                new PolicyChange("WITHDRAWAL", withdrawal.id(), null, "ON")));
        assertTrue(preview.warnings().stream().anyMatch(warning ->
                warning.code().equals(PreviewWarning.CONTENT_MOD_REMOVED)));
        ReleaseManifest withdrawn = fixture.database.readManifest(
                fixture.publisher.publish("demo", "3.0.0", "0.3.0", "withdraw"));
        assertEquals(List.of(withdrawal.id()), withdrawn.withdrawals().stream()
                .map(cn.dreamingfish.updater.protocol.Withdrawal::id).toList());

        ManagementException rollback = assertThrows(ManagementException.class, () ->
                fixture.publisher.rollback("demo", broken.releaseId(), "4.0.0", "rollback"));
        assertTrue(rollback.getMessage().contains("撤回"));

        policies.revokeWithdrawal("demo", withdrawal.id());
        fixture.scanner.createPreview("demo");
        assertTrue(fixture.database.readManifest(fixture.publisher.publish(
                "demo", "4.0.0", "0.3.0", "revoked")).withdrawals().isEmpty());
    }

    @Test
    void correctionsTargetPublishedFilesAndAreDroppedWithThem() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        write(fixture.source.resolve("config/client.toml"), "render=8");
        policies.setPreset("demo", "config/client.toml", false, MaintenancePreset.INITIAL);
        fixture.scanner.createPreview("demo");
        String bad = fixture.database.readManifest(fixture.publisher.publish("demo", "1.0.0", "0.3.0", "bad"))
                .files().getFirst().sha256();
        write(fixture.source.resolve("config/client.toml"), "render=12");

        var once = policies.correct("demo", "config/client.toml", CorrectionMode.ONCE, "修复渲染距离", List.of());
        assertThrows(ManagementException.class, () -> policies.correct("demo", "config/client.toml",
                CorrectionMode.KNOWN_BAD, "", List.of(bad)));
        var knownBad = policies.correct("demo", "config/client.toml", CorrectionMode.KNOWN_BAD,
                "只替换坏版本", List.of(bad));
        assertEquals(List.of(knownBad.id()), fixture.database.requireProject("demo").rules()
                .corrections().stream().map(cn.dreamingfish.updater.protocol.Correction::id).toList());
        assertFalse(knownBad.id().equals(once.id()));

        fixture.scanner.createPreview("demo");
        ReleaseManifest corrected = fixture.database.readManifest(
                fixture.publisher.publish("demo", "2.0.0", "0.3.0", "fix"));
        assertEquals(List.of(bad), corrected.corrections().getFirst().badSha256());

        Files.delete(fixture.source.resolve("config/client.toml"));
        PublishPreview preview = fixture.scanner.createPreview("demo");
        assertTrue(preview.policyChanges().stream().anyMatch(change ->
                change.kind().equals("CORRECTION_DROPPED")));
        fixture.scanner.decideRemovals("demo", List.of(
                new RemovalDecision("config/client.toml", RemovalAction.DELETE)));
        assertTrue(fixture.database.readManifest(fixture.publisher.publish(
                "demo", "3.0.0", "0.3.0", "removed")).corrections().isEmpty());
    }

    @Test
    void makeOptionalRestoresARemovedModIntoAGroup() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        writeJar(fixture.source.resolve("mods/particles.jar"), "particles", "2.0");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0.0", "0.3.0", "particles");
        Files.delete(fixture.source.resolve("mods/particles.jar"));
        policies.saveOptionalGroup("demo", new OptionalGroupRule("visuals", "美化包", "", true,
                List.of(), List.of(), List.of()));

        policies.makeOptional("demo", "mods/particles.jar", "visuals");
        assertTrue(Files.isRegularFile(fixture.source.resolve("mods/particles.jar")));
        assertEquals(List.of("particles"), fixture.database.requireProject("demo").rules()
                .optionalGroup("visuals").orElseThrow().modIds());
        PublishPreview preview = fixture.scanner.createPreview("demo");
        assertTrue(preview.changes().stream().noneMatch(change -> change.kind() == ChangeKind.REMOVED));
        assertEquals("visuals", byPath(preview.files()).get("mods/particles.jar").optionalGroup());
    }

    @Test
    void refusesCleanupDirectoriesThatWouldRemoveExcludedContent() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        write(fixture.source.resolve("saves/world/level.dat"), "world");
        write(fixture.source.resolve("config/a.toml"), "a");
        policies.setCleanup("demo", "saves", true);
        assertThrows(ManagementException.class, () -> fixture.scanner.createPreview("demo"));
        policies.setCleanup("demo", "saves", false);

        fixture.projects.configure("demo", null, null, null, fixture.database.requireProject("demo")
                .rules().withRules(List.of(new FileRule("config/private/**", RuleAction.EXCLUDE))));
        write(fixture.source.resolve("config/private/secret.toml"), "secret");
        policies.setCleanup("demo", "config", true);
        assertThrows(ManagementException.class, () -> fixture.scanner.createPreview("demo"));
    }

    @Test
    void publishedPoliciesReachThePlayerEngine() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        ProjectRecord project = fixture.createProject();
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        writeJar(fixture.source.resolve("mods/core.jar"), "core", "1");
        writeJar(fixture.source.resolve("mods/iris.jar"), "iris", "1.7");
        write(fixture.source.resolve("options.txt"), "guiScale:2");
        write(fixture.source.resolve("config/client.toml"), "render=8");
        policies.setPreset("demo", "options.txt", false, MaintenancePreset.INITIAL);
        policies.setPreset("demo", "config/client.toml", false, MaintenancePreset.INITIAL);
        policies.saveOptionalGroup("demo", new OptionalGroupRule("visuals", "美化包", "", false,
                List.of("iris"), List.of(), List.of()));
        fixture.scanner.createPreview("demo");
        StoredRelease first = fixture.publisher.publish("demo", "1.0.0", "0.3.0", "first");

        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path home = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                .prepareBaseline("demo", first.releaseId(), instance, home);
        try (PublicFileServer server = new PublicFileServer(fixture.database, fixture.objects,
                new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            ProjectBinding binding = new ProjectBinding(ProtocolConstants.BINDING_SCHEMA_VERSION,
                    project.id(), "http://127.0.0.1:" + server.address().getPort(), project.publicKey(),
                    "DreamingFishUpdater", null, project.branding());
            update(instance, binding, LocalFileOverrides.NONE);
            assertTrue(Files.isRegularFile(instance.resolve("mods/core.jar")));
            assertFalse(Files.exists(instance.resolve("mods/iris.jar")));
            assertEquals("guiScale:2", Files.readString(instance.resolve("options.txt")));

            Files.writeString(instance.resolve("config/client.toml"), "render=16");
            Files.delete(instance.resolve("options.txt"));
            write(fixture.source.resolve("config/client.toml"), "render=10");
            fixture.scanner.createPreview("demo");
            fixture.publisher.publish("demo", "2.0.0", "0.3.0", "second");
            UpdateResult second = update(instance, binding,
                    LocalFileOverrides.NONE.withGroupChoices(Map.of("visuals", true)));
            assertEquals("render=16", Files.readString(instance.resolve("config/client.toml")));
            assertTrue(second.keptModifiedPaths().isEmpty(), "initial files are player-owned, not checked as default configurations");
            assertFalse(Files.exists(instance.resolve("options.txt")));
            assertTrue(Files.isRegularFile(instance.resolve("mods/iris.jar")));
        }
    }

    private static UpdateResult update(Path instance, ProjectBinding binding, LocalFileOverrides choices) {
        return new UpdateEngine().update(new UpdateRequest(instance, instance.resolve("DreamingFishUpdater"),
                binding, "0.3.0", ManagementFixture.PLAYER_CAPABILITIES, null, null, null, null, choices), null);
    }

    @Test
    void historyRecordsExactReleaseMembershipAcrossRemovalAndReintroduction() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        Path mod = fixture.source.resolve("mods/b.jar");
        writeJar(mod, "b", "1.0");
        byte[] content = Files.readAllBytes(mod);
        fixture.scanner.createPreview("demo");
        var first = fixture.publisher.publish("demo", "1.0", "0.2.0", "B provided");
        Files.delete(mod);
        fixture.scanner.createPreview("demo");
        var removed = fixture.publisher.publish("demo", "2.0", "0.2.0", "B removed");
        Files.write(mod, content);
        fixture.scanner.createPreview("demo");
        var returned = fixture.publisher.publish("demo", "3.0", "0.2.0", "B reintroduced");
        var version = new ProjectPolicyService(fixture.paths, fixture.database).history("demo")
                .stream().filter(file -> file.path().equals("mods/b.jar"))
                .findFirst().orElseThrow().versions().getFirst();
        assertEquals(List.of(first.releaseId(), returned.releaseId()), version.releaseIds());
        assertFalse(version.releaseIds().contains(removed.releaseId()));
        assertEquals(2, version.releaseCount());
        assertTrue(version.currentlyPublished());
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static void writeJar(Path path, String id, String version) throws Exception {
        Files.createDirectories(path.getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + id
                    + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.write(path, bytes.toByteArray());
    }

    private static Map<String, ScannedFile> byPath(List<ScannedFile> files) {
        Map<String, ScannedFile> result = new java.util.HashMap<>();
        files.forEach(file -> result.put(file.path(), file));
        return result;
    }

    private static Map<String, ManifestFile> byManifestPath(List<ManifestFile> files) {
        Map<String, ManifestFile> result = new java.util.HashMap<>();
        files.forEach(file -> result.put(file.path(), file));
        return result;
    }
}
