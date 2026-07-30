package cn.dreamingfish.updater.player;

import java.net.URL;
import java.time.LocalDate;
import java.util.Objects;

record NewsArticle(
        String id,
        String title,
        String summary,
        LocalDate publishedOn,
        URL cover,
        String markdown
) {
    NewsArticle {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(publishedOn, "publishedOn");
        Objects.requireNonNull(cover, "cover");
        Objects.requireNonNull(markdown, "markdown");
    }
}
