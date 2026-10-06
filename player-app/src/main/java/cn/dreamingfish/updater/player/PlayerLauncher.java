package cn.dreamingfish.updater.player;

public final class PlayerLauncher {
    private PlayerLauncher() {
    }

    public static void main(String[] arguments) {
        try {
            PlayerSidecarMain.main(arguments);
        } catch (Exception error) {
            throw new IllegalStateException("玩家端更新引擎启动失败", error);
        }
    }
}
