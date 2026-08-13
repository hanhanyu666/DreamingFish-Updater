package cn.dreamingfish.updater.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum FilePolicy {
    ENFORCED,
    /**
     * Read-only compatibility for signed releases created by older management
     * versions. New releases must not generate this policy.
     */
    @JsonProperty("DEFAULT")
    @Deprecated(forRemoval = false)
    LEGACY_MISSING_ONLY
}
