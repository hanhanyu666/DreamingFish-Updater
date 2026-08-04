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
        List<PlayerNewsArticle> articles
) {
    public PlayerContentPage {
        if (articles != null) articles = List.copyOf(articles);
    }
}
