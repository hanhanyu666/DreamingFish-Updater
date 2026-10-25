package cn.dreamingfish.updater.protocol;

/** Data rendered by the player's built-in showcase components. */
public record PlayerGalleryItem(
        String id, String title, String author, String category, String caption,
        String location, String imageFile, String imageUrl, Boolean featured
) {
    public PlayerGalleryItem {
        author = author == null ? "" : author;
        category = category == null ? "" : category;
    }

    public PlayerGalleryItem(String id, String title, String author, String category, String caption,
                             String location, String imageFile, String imageUrl) {
        this(id, title, author, category, caption, location, imageFile, imageUrl, null);
    }
}

