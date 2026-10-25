package cn.dreamingfish.updater.protocol;

/** Data rendered by the player's built-in showcase components. */
public record PlayerEvent(
        String id, String title, String startsAt, String endsAt, PlayerLinkTarget action
) {
}

