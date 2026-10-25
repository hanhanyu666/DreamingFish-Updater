package cn.dreamingfish.updater.protocol;

/** Data rendered by the player's built-in showcase components. */
public record PlayerPresentationImage(
        String fileName, String sha256, long size
) {
}

