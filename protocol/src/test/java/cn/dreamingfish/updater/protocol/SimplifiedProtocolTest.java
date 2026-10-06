package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SimplifiedProtocolTest {
    private static Withdrawal removal() {
        return new Withdrawal("remove-d", "移除D", Instant.now(), List.of(new WithdrawalItem(
                "a".repeat(64), 1, "mods/d.jar", "d", "4")), Withdrawal.Kind.REMOVAL);
    }
    private static ReleaseManifest manifest(Set<String> capabilities) {
        return new ReleaseManifest(1, "demo", "r5", 5, Instant.now(), "5.0", "0.2.0", "", capabilities,
                List.of(), List.of(), List.of(), Branding.empty(), List.of(), List.of(), List.of(),
                List.of(), List.of(removal()), List.of());
    }
    @Test void resourceRemovalRequiresTheNewCapabilityAndOlderClientsCannotIgnoreIt() {
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateRelease(
                manifest(Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY)), ProtocolConstants.RELEASE_CAPABILITIES));
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateRelease(manifest(ProtocolConstants.RELEASE_CAPABILITIES),
                Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY)));
        assertDoesNotThrow(() -> ManifestValidator.validateRelease(manifest(ProtocolConstants.RELEASE_CAPABILITIES),
                ProtocolConstants.RELEASE_CAPABILITIES));
    }
    @Test void wholeResourceMatchingCoversNewVersionsButNotOtherModsOrOtherDirectories() {
        MaintenanceModel model = MaintenanceModel.of(manifest(ProtocolConstants.RELEASE_CAPABILITIES));
        assertTrue(model.withdrawalFor("mods/renamed.jar", "b".repeat(64), "d", "9").isPresent());
        assertTrue(model.withdrawalFor("mods/d.jar", "b".repeat(64), null, null).isPresent());
        assertTrue(model.withdrawalFor("resourcepacks/d.jar", "a".repeat(64), "d", "4").isEmpty());
        assertTrue(model.withdrawalFor("mods/d.jar", "b".repeat(64), "personal", "4").isEmpty());
    }
    @Test void oldWithdrawalPayloadsKeepVersionMatchingRatherThanWholeResourceMatching() throws Exception {
        String old = "{\"id\":\"bad-d\",\"reason\":\"bad\",\"createdAt\":\"2026-10-02T00:00:00Z\","
                + "\"items\":[{\"sha256\":\"" + "a".repeat(64) + "\",\"size\":1,\"path\":\"mods/d.jar\","
                + "\"componentId\":\"d\",\"version\":\"4\"}]}";
        Withdrawal version = new JsonCodec().read(old.getBytes(java.nio.charset.StandardCharsets.UTF_8), Withdrawal.class);
        assertEquals(Withdrawal.Kind.VERSION, version.kind());
        assertFalse(version.removal());
    }
}
