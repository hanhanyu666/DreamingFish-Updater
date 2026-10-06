package cn.dreamingfish.updater.management;

/** What happens on player instances to a file the owner stopped publishing. */
public enum RemovalAction {
    /** 从玩家端删除: copies are removed; modified or self-managed copies go into the player backup. */
    DELETE,
    /** 删除，但保留玩家自行管理的副本. */
    DELETE_KEEP_SELF_MANAGED,
    /** 放弃管理并保留: every existing copy now belongs to its player. */
    RELEASE
}
