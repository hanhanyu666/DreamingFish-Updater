package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.FilePolicy;
import com.fasterxml.jackson.annotation.JsonProperty;

public enum RuleAction {
    ENFORCED,
    /** Legacy storage token. ProjectRules removes it while old databases are loaded. */
    @JsonProperty("DEFAULT")
    @Deprecated(forRemoval = false)
    LEGACY_DEFAULT,
    EXCLUDE;

    public FilePolicy toFilePolicy() {
        return switch (this) {
            case ENFORCED -> FilePolicy.ENFORCED;
            case LEGACY_DEFAULT -> throw new IllegalStateException(
                    "Legacy DEFAULT rules cannot create new release files");
            case EXCLUDE -> throw new IllegalStateException("Excluded files have no manifest policy");
        };
    }
}
