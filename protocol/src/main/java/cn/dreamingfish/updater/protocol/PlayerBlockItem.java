package cn.dreamingfish.updater.protocol;

/** Data rendered by the player's built-in showcase components. */
public record PlayerBlockItem(
        String id, String title, String text, String meta, String icon,
        String imageFile, String imageUrl, PlayerLinkTarget action
) {
}

