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
        List<PlayerContentPage> contentPages,
        List<PlayerMusicTrack> musicTracks,
        String welcomeText,
        String topBarColor,
        String cardColor
) {
    public static final String DEFAULT_BRAND_NAME = "梦鱼服";
    public static final String DEFAULT_BRAND_ENGLISH_NAME = "DreamingFish";
    public static final String DEFAULT_WELCOME_TEXT = "欢迎来到";
    public static final String DEFAULT_TOP_BAR_COLOR = "#030708";
    public static final String DEFAULT_CARD_COLOR = "#030708";

    public Branding {
        brandName = defaultText(brandName, DEFAULT_BRAND_NAME);
        brandEnglishName = defaultText(
                brandEnglishName, DEFAULT_BRAND_ENGLISH_NAME);
        welcomeText = defaultText(welcomeText, DEFAULT_WELCOME_TEXT);
        topBarColor = defaultText(topBarColor, DEFAULT_TOP_BAR_COLOR);
        cardColor = defaultText(cardColor, DEFAULT_CARD_COLOR);
        if (newsArticles != null) newsArticles = List.copyOf(newsArticles);
        if (contentPages != null) contentPages = List.copyOf(contentPages);
        if (musicTracks != null) musicTracks = List.copyOf(musicTracks);
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
                newsArticles, customPage, null, null,
                null, null, null);
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
                List.of(), PlayerCustomPage.disabled(), List.of(), List.of(),
                null, null, null);
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
                brandEnglishName, newsArticles, customPage, contentPages, musicTracks,
                welcomeText, topBarColor, cardColor);
    }

    public Branding withMusicTracks(List<PlayerMusicTrack> value) {
        return new Branding(productName, subtitle, serverAddress, coverObject,
                accentColor, secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, contentPages, value,
                welcomeText, topBarColor, cardColor);
    }

    /** Compatibility constructor for callers that already provide content pages. */
    public Branding(
            String productName, String subtitle, String serverAddress,
            String coverObject, String accentColor, String secondaryAccentColor,
            String brandName, String brandEnglishName,
            List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage,
            List<PlayerContentPage> contentPages
    ) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, contentPages, null,
                null, null, null);
    }

    /** Backward-compatible constructor used before surface theme options. */
    public Branding(
            String productName, String subtitle, String serverAddress,
            String coverObject, String accentColor, String secondaryAccentColor,
            String brandName, String brandEnglishName,
            List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage,
            List<PlayerContentPage> contentPages,
            List<PlayerMusicTrack> musicTracks
    ) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, contentPages, musicTracks,
                null, null, null);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
