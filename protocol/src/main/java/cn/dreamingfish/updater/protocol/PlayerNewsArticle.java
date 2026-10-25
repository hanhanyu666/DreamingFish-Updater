package cn.dreamingfish.updater.protocol;

public record PlayerNewsArticle(
        String id,
        String title,
        String summary,
        String publishedOn,
        String coverUrl,
        String markdown,
        String category,
        String author
) {
    public PlayerNewsArticle(String id, String title, String summary, String publishedOn,
                             String coverUrl, String markdown) {
        this(id, title, summary, publishedOn, coverUrl, markdown, null, null);
    }
}
