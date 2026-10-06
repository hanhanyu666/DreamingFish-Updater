package cn.dreamingfish.updater.protocol;

/**
 * How a published file is maintained on player instances. The owner picks one
 * preset per file or directory; older manifests without presets are mapped by
 * {@link MaintenanceModel}.
 */
public enum MaintenancePreset {
    /** 必需同步: always equal to the release; players cannot opt out. */
    REQUIRED,
    /** 普通同步: follows the release; players may disable or self-manage it. */
    SYNC,
    /** 首次提供: installed once per instance, then owned by the player. */
    INITIAL,
    /** 默认配置: updated only while the local copy still equals the last shipped default. */
    DEFAULT_CONFIG
}
