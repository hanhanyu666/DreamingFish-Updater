package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.UpdateErrorCode;
import cn.dreamingfish.updater.engine.UpdateException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOfflineLaunchPolicyTest {
    @Test
    void allowsOnlyDirectNetworkUnavailability() {
        assertTrue(PlayerApplication.allowsUnverifiedOfflineLaunch(
                new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE, "offline")));

        assertFalse(PlayerApplication.allowsUnverifiedOfflineLaunch(
                new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, "damaged")));
        assertFalse(PlayerApplication.allowsUnverifiedOfflineLaunch(
                new UpdateException(UpdateErrorCode.INVALID_SIGNATURE, "invalid")));
        assertFalse(PlayerApplication.allowsUnverifiedOfflineLaunch(
                new RuntimeException(new UpdateException(
                        UpdateErrorCode.NETWORK_UNAVAILABLE, "wrapped"))));
    }
}
