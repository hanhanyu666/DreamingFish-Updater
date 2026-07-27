package cn.dreamingfish.updater.bootstrap;

import java.nio.file.Path;

final class BootstrapBinding {
    private final Path playerHome;
    private final String projectId;
    private final String publicKey;

    BootstrapBinding(Path playerHome, String projectId, String publicKey) {
        this.playerHome = playerHome;
        this.projectId = projectId;
        this.publicKey = publicKey;
    }

    Path playerHome() {
        return playerHome;
    }

    String projectId() {
        return projectId;
    }

    String publicKey() {
        return publicKey;
    }
}
