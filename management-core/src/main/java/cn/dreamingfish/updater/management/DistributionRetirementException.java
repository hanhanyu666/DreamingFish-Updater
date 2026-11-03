package cn.dreamingfish.updater.management;

/** Fixed public text contains no credentials or remote response bodies. */
public final class DistributionRetirementException extends RuntimeException {
    public static final String MESSAGE="旧玩家程序入口尚未确认移除，暂停尚未完整同步到外部托管；请授予该项目 latest 入口的删除和检查权限，并重新上传。其他文件不会被删除。";
    public DistributionRetirementException() { super(MESSAGE); }
}
