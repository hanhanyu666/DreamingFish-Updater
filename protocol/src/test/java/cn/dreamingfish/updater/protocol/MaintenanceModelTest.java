package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceModelTest {
    private static final Set<String> ALL = Set.of(
            ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC,
            ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
            ProtocolConstants.CAPABILITY_RELEASED_PATHS,
            ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY);

    @Test
    void mapsHistoricalReleasesOntoTheSameVocabulary() {
        ReleaseManifest legacy = new ReleaseManifest(1, "demo", "r1", 1, Instant.now(), "1.0",
                "0.1.0", "", Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC,
                ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC),
                List.of("mods"), List.of("config/core.toml"), List.of(), Branding.empty(),
                List.of(file("config/core.toml", FilePolicy.ENFORCED, null),
                        file("config/plain.toml", FilePolicy.ENFORCED, null),
                        file("mods/a.jar", FilePolicy.ENFORCED, null),
                        file("options.txt", FilePolicy.LEGACY_MISSING_ONLY, null)));
        assertDoesNotThrow(() -> ManifestValidator.validateRelease(legacy, ALL));

        MaintenanceModel model = MaintenanceModel.of(legacy);
        assertFalse(model.policyV2());
        assertEquals(MaintenanceModel.Behavior.REQUIRED, model.behavior("config/core.toml").orElseThrow());
        assertEquals(MaintenanceModel.Behavior.SYNC, model.behavior("CONFIG/plain.toml").orElseThrow());
        assertEquals(MaintenanceModel.Behavior.REQUIRED, model.behavior("mods/a.jar").orElseThrow());
        assertEquals(MaintenanceModel.Behavior.LEGACY_MISSING_ONLY, model.behavior("options.txt").orElseThrow());
        assertEquals(List.of("mods"), model.cleanupDirectories());
        assertTrue(model.insideCleanupDirectory("mods/extra.jar"));
        assertFalse(model.insideCleanupDirectory("modsx/extra.jar"));
    }

    @Test
    void readsPresetsGroupsDirectivesAndMatchesWithdrawnCopies() {
        ReleaseManifest release = policyRelease(
                List.of(file("config/client.toml", MaintenancePreset.DEFAULT_CONFIG, null),
                        mod("mods/iris-1.jar", MaintenancePreset.SYNC, "visuals", "iris", "1.7"),
                        file("mods/core.jar", MaintenancePreset.REQUIRED, null),
                        file("options.txt", MaintenancePreset.INITIAL, null)),
                List.of(new Withdrawal("broken-jei", "崩服", Instant.now(), List.of(
                        new WithdrawalItem("e".repeat(64), 3, "mods/jei-15.2.jar", "jei", "15.2")))),
                List.of(new Correction("fix-client", "config/client.toml", CorrectionMode.ONCE,
                        List.of(), "修复渲染距离", Instant.now())));
        assertDoesNotThrow(() -> ManifestValidator.validateRelease(release, ALL));

        MaintenanceModel model = MaintenanceModel.of(release);
        assertTrue(model.policyV2());
        assertTrue(model.locked("mods/core.jar"));
        assertFalse(model.locked("mods/iris-1.jar"));
        assertEquals("visuals", model.groupOf("mods/iris-1.jar").orElseThrow().id());
        assertTrue(model.correction("CONFIG/client.toml").isPresent());
        assertTrue(model.publishesComponent("IRIS"));

        assertTrue(model.withdrawalFor("mods/renamed-jei.jar", "e".repeat(64), null, null).isPresent());
        assertTrue(model.withdrawalFor("mods/other-name.jar", "f".repeat(64), "JEI", "15.2").isPresent());
        assertFalse(model.withdrawalFor("mods/jei-15.3.jar", "f".repeat(64), "jei", "15.3").isPresent());
        assertFalse(model.withdrawalFor("config/jei.jar", "e".repeat(64), null, null).isPresent());
        assertEquals(Set.of("mods"), model.withdrawalDirectories());
    }

    @Test
    void rejectsInconsistentMaintenancePolicies() {
        // Policy fields without the capability.
        ReleaseManifest withoutCapability = new ReleaseManifest(1, "demo", "r1", 1, Instant.now(), "1.0",
                "0.1.0", "", Set.of(), List.of(), List.of(), List.of(), Branding.empty(),
                List.of(file("a.txt", MaintenancePreset.SYNC, null)));
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateRelease(withoutCapability, ALL));

        // Missing preset, required file in a group, unknown group, empty group.
        assertRejected(List.of(new ManifestFile("a.txt", "a".repeat(64), 1, FilePolicy.ENFORCED, false)),
                List.of(), List.of(), List.of());
        assertRejected(List.of(file("a.txt", MaintenancePreset.REQUIRED, "visuals")),
                List.of(group("visuals")), List.of(), List.of());
        assertRejected(List.of(file("a.txt", MaintenancePreset.SYNC, "missing")),
                List.of(), List.of(), List.of());
        assertRejected(List.of(file("a.txt", MaintenancePreset.SYNC, null)),
                List.of(group("visuals")), List.of(), List.of());

        // Withdrawn content still published; corrections for unknown or inconsistent targets.
        assertRejected(List.of(file("config/a.toml", MaintenancePreset.SYNC, null)),
                List.of(), List.of(new Withdrawal("w", "", Instant.now(), List.of(
                        new WithdrawalItem("a".repeat(64), 1, "config/a.toml", null, null)))), List.of());
        assertRejected(List.of(file("config/a.toml", MaintenancePreset.SYNC, null)),
                List.of(), List.of(), List.of(new Correction("c", "config/b.toml",
                        CorrectionMode.ONCE, List.of(), "", Instant.now())));
        assertRejected(List.of(file("config/a.toml", MaintenancePreset.SYNC, null)),
                List.of(), List.of(), List.of(new Correction("c", "config/a.toml",
                        CorrectionMode.KNOWN_BAD, List.of("a".repeat(64)), "", Instant.now())));
        assertRejected(List.of(file("config/a.toml", MaintenancePreset.SYNC, null)),
                List.of(), List.of(), List.of(new Correction("c", "config/a.toml",
                        CorrectionMode.KNOWN_BAD, List.of(), "", Instant.now())));
    }

    @Test
    void rejectsReleasedOrRetainedPathsInsideCleanupDirectories() {
        List<ManifestFile> files = List.of(file("mods/a.jar", MaintenancePreset.SYNC, null));
        Set<String> capabilities = Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY,
                ProtocolConstants.CAPABILITY_RELEASED_PATHS);
        ReleaseManifest released = new ReleaseManifest(1, "demo", "r1", 1, Instant.now(), "1.0",
                "0.1.0", "", capabilities, List.of(), List.of(), List.of("mods/old.jar"),
                Branding.empty(), files, List.of("mods"), List.of(), List.of(), List.of(), List.of());
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateRelease(released, ALL));
        ReleaseManifest retained = new ReleaseManifest(1, "demo", "r1", 1, Instant.now(), "1.0",
                "0.1.0", "", capabilities, List.of(), List.of(), List.of(),
                Branding.empty(), files, List.of("mods"), List.of(), List.of("mods/old.jar"),
                List.of(), List.of());
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateRelease(retained, ALL));
    }

    private static void assertRejected(List<ManifestFile> files, List<OptionalGroup> groups,
                                       List<Withdrawal> withdrawals, List<Correction> corrections) {
        ReleaseManifest manifest = new ReleaseManifest(1, "demo", "r1", 1, Instant.now(), "1.0",
                "0.1.0", "", Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY), List.of(),
                List.of(), List.of(), Branding.empty(), files, List.of(), groups, List.of(),
                withdrawals, corrections);
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateRelease(manifest, ALL));
    }

    private static ReleaseManifest policyRelease(List<ManifestFile> files,
                                                 List<Withdrawal> withdrawals,
                                                 List<Correction> corrections) {
        List<ManifestFile> sorted = new ArrayList<>(files);
        sorted.sort(java.util.Comparator.comparing(ManifestFile::path));
        return new ReleaseManifest(1, "demo", "r1", 1, Instant.now(), "1.0", "0.1.0", "",
                Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY), List.of(), List.of(),
                List.of(), Branding.empty(), sorted, List.of("mods"), List.of(group("visuals")),
                List.of(), withdrawals, corrections);
    }

    private static OptionalGroup group(String id) {
        return new OptionalGroup(id, "美化包", "低配电脑可以关闭", true);
    }

    private static ManifestFile file(String path, FilePolicy policy, String group) {
        return new ManifestFile(path,
                CryptoSupport.sha256(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                1, policy, false, null, null, null, group, null);
    }

    private static ManifestFile file(String path, MaintenancePreset preset, String group) {
        return new ManifestFile(path, "a".repeat(64), 1, FilePolicy.ENFORCED, false,
                null, null, preset, group, null);
    }

    private static ManifestFile mod(String path, MaintenancePreset preset, String group,
                                    String componentId, String version) {
        return new ManifestFile(path, "b".repeat(64), 1, FilePolicy.ENFORCED, false,
                componentId, componentId, preset, group, version);
    }
}
