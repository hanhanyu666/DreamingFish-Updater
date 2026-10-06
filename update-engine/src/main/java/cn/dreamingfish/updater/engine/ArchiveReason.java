package cn.dreamingfish.updater.engine;

/** Why a local file was moved into the player backup instead of being deleted or overwritten. */
public enum ArchiveReason {
    /** 清理多余文件: the directory is fully managed by the owner. */
    CLEANUP("目录由服主统一管理，已移出未发布的文件"),
    /** The owner removed a file that the player had modified. */
    REMOVED_MODIFIED("服主已删除这个文件，你修改过的副本已备份"),
    /** The owner removed a file that the player managed personally. */
    REMOVED_SELF_MANAGED("服主已删除这个文件，你自行管理的副本已备份"),
    /** The owner started managing a file the player already had. */
    TAKEOVER("服主开始统一管理这个文件，你原有的版本已备份"),
    /** A synchronized file that the player had changed was restored to the owner's version. */
    REPLACED_MODIFIED("这个文件由服主同步管理，你的修改已备份并恢复为服主版本"),
    /** Another jar declares the same mod ID as a managed mod. */
    DUPLICATE("与整合包中的同一模组重复（modId 相同），已移出以免游戏崩溃"),
    /** 撤回问题版本. */
    WITHDRAWN("服主撤回了这个版本"),
    OWNER_REMOVED("服主已移除这个文件，副本已备份"),
    /** 修正配置. */
    CORRECTED("服主修正了这个文件"),
    /** The player asked to restore the pack's default configuration. */
    RESET_DEFAULT("你选择恢复默认配置，原来的文件已备份"),
    /** Archives written by earlier player versions carry no reason. */
    LEGACY("强制同步目录中的多余文件");

    private final String description;

    ArchiveReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
