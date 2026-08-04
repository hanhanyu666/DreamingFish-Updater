package cn.dreamingfish.updater.protocol;

public record PlayerCustomPage(
        boolean enabled,
        String navigationLabel,
        String eyebrow,
        String title,
        String lead,
        String markdown
) {
    public static PlayerCustomPage disabled() {
        return new PlayerCustomPage(false, "服务器介绍", "WELCOME",
                "欢迎来到服务器", "", "");
    }
}
