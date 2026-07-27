package cn.dreamingfish.updater.bootstrap;

final class PermitDecision {
    private final boolean allowed;
    private final String reason;

    private PermitDecision(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    static PermitDecision allow() {
        return new PermitDecision(true, "");
    }

    static PermitDecision deny(String reason) {
        return new PermitDecision(false, reason);
    }

    boolean allowed() {
        return allowed;
    }

    String reason() {
        return reason;
    }
}
