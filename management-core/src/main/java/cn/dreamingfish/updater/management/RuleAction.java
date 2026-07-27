package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.FilePolicy;

public enum RuleAction {
    ENFORCED,
    DEFAULT,
    EXCLUDE;

    public FilePolicy toFilePolicy() {
        return switch (this) {
            case ENFORCED -> FilePolicy.ENFORCED;
            case DEFAULT -> FilePolicy.DEFAULT;
            case EXCLUDE -> throw new IllegalStateException("Excluded files have no manifest policy");
        };
    }
}
