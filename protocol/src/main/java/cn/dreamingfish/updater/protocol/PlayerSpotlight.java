package cn.dreamingfish.updater.protocol;

/** Data rendered by the player's built-in showcase components. */
public record PlayerSpotlight(
        String id, String tag, String eyebrow, String title, String lead,
        String imageFile, String imageUrl, PlayerLinkTarget action, String label
) {
    public PlayerSpotlight {
        tag = tag == null ? "" : tag;
        eyebrow = eyebrow == null ? "" : eyebrow;
        lead = lead == null ? "" : lead;
    }

    public PlayerSpotlight(String id, String tag, String eyebrow, String title, String lead,
                           String imageFile, String imageUrl, PlayerLinkTarget action) {
        this(id, tag, eyebrow, title, lead, imageFile, imageUrl, action, null);
    }
}

