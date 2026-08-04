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
        PlayerCustomPage customPage
) {
    public static final String DEFAULT_BRAND_NAME = "梦鱼服";
    public static final String DEFAULT_BRAND_ENGLISH_NAME = "DreamingFish";

    public Branding {
        brandName = defaultText(brandName, DEFAULT_BRAND_NAME);
        brandEnglishName = defaultText(
                brandEnglishName, DEFAULT_BRAND_ENGLISH_NAME);
        if (newsArticles != null) newsArticles = List.copyOf(newsArticles);
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
                List.of(), PlayerCustomPage.disabled());
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
                brandEnglishName, newsArticles, customPage);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
