package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.UpdateErrorCode;
import cn.dreamingfish.updater.engine.UpdateException;

/** Version and launch-policy metadata shared by the Java sidecar and tests. */
public final class PlayerRuntime {
    public static final String VERSION = "0.2.0";
    public static final String BOOTSTRAP_AGENT_VERSION = "0.1.2";

    private PlayerRuntime() {
    }

    static boolean allowsUnverifiedOfflineLaunch(Throwable failure) {
        return failure instanceof UpdateException update
                && update.code() == UpdateErrorCode.NETWORK_UNAVAILABLE;
    }

    static boolean allowsLocalContentOverride(Throwable failure) {
        return failure instanceof UpdateException update
                && update.code() == UpdateErrorCode.LOCAL_CONTENT_CHANGED;
    }
}
