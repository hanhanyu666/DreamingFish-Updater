package cn.dreamingfish.updater.protocol;

import java.util.List;

/** Data rendered by the player's built-in showcase components. */
public record PlayerServerCard(
        Boolean enabled, Boolean showStatus, String versionLabel, String motd,
        String logoFile, List<PlayerCommunityLink> links
) {
    public PlayerServerCard {
        if (links != null) links = List.copyOf(links);
    }
}

