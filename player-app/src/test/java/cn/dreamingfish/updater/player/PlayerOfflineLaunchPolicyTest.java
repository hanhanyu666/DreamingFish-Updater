package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.UpdateErrorCode;
import cn.dreamingfish.updater.engine.UpdateException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOfflineLaunchPolicyTest {
    @Test
    void allowsOnlyDirectNetworkUnavailability() {
        assertTrue(PlayerRuntime.allowsUnverifiedOfflineLaunch(
                new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE, "offline")));

        assertFalse(PlayerRuntime.allowsUnverifiedOfflineLaunch(
                new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, "damaged")));
        assertFalse(PlayerRuntime.allowsUnverifiedOfflineLaunch(
                new UpdateException(UpdateErrorCode.INVALID_SIGNATURE, "invalid")));
        assertFalse(PlayerRuntime.allowsUnverifiedOfflineLaunch(
                new RuntimeException(new UpdateException(
                        UpdateErrorCode.NETWORK_UNAVAILABLE, "wrapped"))));
    }

    @Test
    void allowsManualOverrideOnlyForChangedManagedContent() {
        assertTrue(PlayerRuntime.allowsLocalContentOverride(
                new UpdateException(UpdateErrorCode.LOCAL_CONTENT_CHANGED, "changed")));
        assertFalse(PlayerRuntime.allowsLocalContentOverride(
                new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, "metadata")));
        assertFalse(PlayerRuntime.allowsLocalContentOverride(
                new UpdateException(UpdateErrorCode.INVALID_SIGNATURE, "invalid")));
    }
}
