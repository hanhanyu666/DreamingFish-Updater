package cn.dreamingfish.updater.protocol;

import java.util.List;

/** A server-owner-defined page in the player application's top navigation. */
public record PlayerContentPage(
        String id,
        String navigationLabel,
        boolean announcementPage,
        String eyebrow,
        String title,
        String lead,
        String markdown,
        List<PlayerNewsArticle> articles,
        String layout,
        List<PlayerGalleryItem> gallery,
        List<PlayerBlock> blocks,
        String imageFile,
        String imageUrl,
        List<String> tags
) {
    public PlayerContentPage {
        eyebrow = eyebrow == null ? "" : eyebrow;
        lead = lead == null ? "" : lead;
        markdown = markdown == null ? "" : markdown;
        if (articles != null) articles = List.copyOf(articles);
        if (gallery != null) gallery = List.copyOf(gallery);
        if (blocks != null) blocks = List.copyOf(blocks);
        // Retain null entries until validation so errors can identify the exact tag.
        if (tags != null) tags = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tags));
    }

    public PlayerContentPage(String id, String navigationLabel, boolean announcementPage,
                             String eyebrow, String title, String lead, String markdown,
                             List<PlayerNewsArticle> articles, String layout,
                             List<PlayerGalleryItem> gallery, List<PlayerBlock> blocks) {
        this(id, navigationLabel, announcementPage, eyebrow, title, lead, markdown,
                articles, layout, gallery, blocks, null, null, null);
    }

    public PlayerContentPage(String id, String navigationLabel, boolean announcementPage,
                             String eyebrow, String title, String lead, String markdown,
                             List<PlayerNewsArticle> articles) {
        this(id, navigationLabel, announcementPage, eyebrow, title, lead, markdown,
                articles, null, null, null);
    }
}
