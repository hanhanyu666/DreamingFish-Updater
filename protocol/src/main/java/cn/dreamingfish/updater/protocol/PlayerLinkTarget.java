package cn.dreamingfish.updater.protocol;

/** Data rendered by the player's built-in showcase components. */
public record PlayerLinkTarget(
        String label, String kind, String pageId, String articleId, String url
) {
    public PlayerLinkTarget { label = label == null ? "" : label; }
}

