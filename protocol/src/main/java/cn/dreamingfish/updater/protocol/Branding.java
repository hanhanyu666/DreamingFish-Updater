package cn.dreamingfish.updater.protocol;

import java.util.List;

public record Branding(
        String productName,
        String subtitle,
        String serverAddress,
        String coverObject,
        String accentColor,
        String secondaryAccentColor,
        String brandName,
        String brandEnglishName,
        List<PlayerNewsArticle> newsArticles,
        PlayerCustomPage customPage,
        List<PlayerContentPage> contentPages
) {
    public static final String DEFAULT_BRAND_NAME = "梦鱼服";
    public static final String DEFAULT_BRAND_ENGLISH_NAME = "DreamingFish";

    public Branding {
        brandName = defaultText(brandName, DEFAULT_BRAND_NAME);
        brandEnglishName = defaultText(
                brandEnglishName, DEFAULT_BRAND_ENGLISH_NAME);
        if (newsArticles != null) newsArticles = List.copyOf(newsArticles);
        if (contentPages != null) contentPages = List.copyOf(contentPages);
    }

    /** Backward-compatible constructor used by pre-page-management callers. */
    public Branding(
            String productName, String subtitle, String serverAddress,
            String coverObject, String accentColor, String secondaryAccentColor,
            String brandName, String brandEnglishName,
            List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage
    ) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, null);
    }

    public Branding(
            String productName,
            String subtitle,
            String serverAddress,
            String coverObject,
            String accentColor,
            String secondaryAccentColor,
            String brandName,
            String brandEnglishName
    ) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName,
                List.of(), PlayerCustomPage.disabled(), List.of());
    }

    public Branding(
            String productName,
            String subtitle,
            String serverAddress,
            String coverObject,
            String accentColor,
            String secondaryAccentColor
    ) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, DEFAULT_BRAND_NAME,
                DEFAULT_BRAND_ENGLISH_NAME);
    }

    public static Branding empty() {
        return new Branding("梦屿", "灾变之后，仍有人在这里守望。", "", null,
                "#2ee8df", "#b06cff");
    }

    public Branding withCoverObject(String value) {
        return new Branding(productName, subtitle, serverAddress, value,
                accentColor, secondaryAccentColor, brandName,
                brandEnglishName, newsArticles, customPage, contentPages);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
