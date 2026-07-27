package cn.dreamingfish.updater.bootstrap;

final class PlayerUpdaterExitedException extends BootstrapException {
    PlayerUpdaterExitedException() {
        super("Player updater exited before granting launch permission");
    }
}
