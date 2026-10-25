package cn.dreamingfish.updater.protocol;

/** Data rendered by the player's built-in showcase components. */
public record PlayerCommunityLink(
        String id, String kind, String label, String url, String copyText
) {
    public PlayerCommunityLink { label = label == null ? "" : label; }
}

