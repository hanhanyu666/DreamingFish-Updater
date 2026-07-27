package cn.dreamingfish.updater.engine;

public enum UpdateStage {
    RECOVERING,
    CHECKING,
    SCANNING,
    DOWNLOADING,
    PREPARING,
    INSTALLING,
    VERIFYING,
    COMPLETE,
    OFFLINE
}
