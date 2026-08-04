package cn.dreamingfish.updater.protocol;

public record PlayerNewsArticle(
        String id,
        String title,
        String summary,
        String publishedOn,
        String coverUrl,
        String markdown
) {
}
