package cn.dreamingfish.updater.protocol;

import java.util.List;
import java.util.Set;

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
        String cardColor,
        Double topBarOpacity,
        String titleColor,
        List<PlayerSpotlight> spotlights,
        PlayerServerCard serverCard,
        List<PlayerEvent> events,
        PlayerTheme theme,
        List<PlayerPresentationImage> presentationImages,
        // CARD (default), HERO beside the spotlight button, or HIDDEN; the status is still queried.
        String addressPlacement,
        // An uploaded showcase image for the top-left mark; none shows the DreamingFish icon.
        String brandLogoFile
) {
    public static final String DEFAULT_BRAND_NAME = "梦鱼更新器";
    public static final String DEFAULT_BRAND_ENGLISH_NAME = "DreamingFish";
    public static final String DEFAULT_WELCOME_TEXT = "欢迎来到";
    public static final String DEFAULT_TOP_BAR_COLOR = "#030708";
    public static final String DEFAULT_CARD_COLOR = "#030708";
    public static final double DEFAULT_TOP_BAR_OPACITY = 0.22d;
    public static final String DEFAULT_TITLE_COLOR = "#fff8dc";
    public static final Set<String> ADDRESS_PLACEMENTS = Set.of("CARD", "HERO", "HIDDEN");

    public Branding {
        brandName = defaultText(brandName, DEFAULT_BRAND_NAME);
        brandEnglishName = defaultText(
                brandEnglishName, DEFAULT_BRAND_ENGLISH_NAME);
        welcomeText = defaultText(welcomeText, DEFAULT_WELCOME_TEXT);
        topBarColor = defaultText(topBarColor, DEFAULT_TOP_BAR_COLOR);
        cardColor = defaultText(cardColor, DEFAULT_CARD_COLOR);
        topBarOpacity = topBarOpacity == null
                ? DEFAULT_TOP_BAR_OPACITY : topBarOpacity;
        titleColor = defaultText(titleColor, DEFAULT_TITLE_COLOR);
        if (newsArticles != null) newsArticles = List.copyOf(newsArticles);
        if (contentPages != null) contentPages = List.copyOf(contentPages);
        if (musicTracks != null) musicTracks = List.copyOf(musicTracks);
        if (spotlights != null) spotlights = List.copyOf(spotlights);
        if (events != null) events = List.copyOf(events);
        if (presentationImages != null) presentationImages = List.copyOf(presentationImages);
        addressPlacement = addressPlacement == null || addressPlacement.isBlank() ? null : addressPlacement.trim();
        brandLogoFile = brandLogoFile == null || brandLogoFile.isBlank() ? null : brandLogoFile.trim();
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
                null, null, null, null, null);
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
                null, null, null, null, null);
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
        return new Branding("Minecraft 整合包", "准备好后，一起进入游戏。", "", null,
                "#2ee8df", "#b06cff");
    }

    public Branding withCoverObject(String value) {
        return new Branding(productName, subtitle, serverAddress, value,
                accentColor, secondaryAccentColor, brandName,
                brandEnglishName, newsArticles, customPage, contentPages, musicTracks,
                welcomeText, topBarColor, cardColor, topBarOpacity, titleColor,
                spotlights, serverCard, events, theme, presentationImages, addressPlacement, brandLogoFile);
    }

    public Branding withMusicTracks(List<PlayerMusicTrack> value) {
        return new Branding(productName, subtitle, serverAddress, coverObject,
                accentColor, secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, contentPages, value,
                welcomeText, topBarColor, cardColor, topBarOpacity, titleColor,
                spotlights, serverCard, events, theme, presentationImages, addressPlacement, brandLogoFile);
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
                null, null, null, null, null);
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
                null, null, null, null, null);
    }

    /** Backward-compatible constructor used before configurable top-bar opacity. */
    public Branding(
            String productName, String subtitle, String serverAddress,
            String coverObject, String accentColor, String secondaryAccentColor,
            String brandName, String brandEnglishName,
            List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage,
            List<PlayerContentPage> contentPages,
            List<PlayerMusicTrack> musicTracks,
            String welcomeText, String topBarColor, String cardColor
    ) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, contentPages, musicTracks,
                welcomeText, topBarColor, cardColor, null, null);
    }

    /** Backward-compatible constructor used before configurable title color. */
    public Branding(
            String productName, String subtitle, String serverAddress,
            String coverObject, String accentColor, String secondaryAccentColor,
            String brandName, String brandEnglishName,
            List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage,
            List<PlayerContentPage> contentPages,
            List<PlayerMusicTrack> musicTracks,
            String welcomeText, String topBarColor, String cardColor,
            Double topBarOpacity
    ) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, contentPages, musicTracks,
                welcomeText, topBarColor, cardColor, topBarOpacity, null);
    }

    /** Compatibility with the branding used before showcase pages. */
    public Branding(String productName, String subtitle, String serverAddress,
                    String coverObject, String accentColor, String secondaryAccentColor,
                    String brandName, String brandEnglishName,
                    List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage,
                    List<PlayerContentPage> contentPages, List<PlayerMusicTrack> musicTracks,
                    String welcomeText, String topBarColor, String cardColor,
                    Double topBarOpacity, String titleColor) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName, newsArticles, customPage,
                contentPages, musicTracks, welcomeText, topBarColor, cardColor,
                topBarOpacity, titleColor, null, null, null, null, null);
    }

    public Branding(String productName, String subtitle, String serverAddress,
                    String coverObject, String accentColor, String secondaryAccentColor,
                    String brandName, String brandEnglishName,
                    List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage,
                    List<PlayerContentPage> contentPages, List<PlayerMusicTrack> musicTracks,
                    String welcomeText, String topBarColor, String cardColor,
                    Double topBarOpacity, String titleColor,
                    List<PlayerSpotlight> spotlights, PlayerServerCard serverCard,
                    List<PlayerEvent> events, PlayerTheme theme) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName, newsArticles, customPage,
                contentPages, musicTracks, welcomeText, topBarColor, cardColor, topBarOpacity,
                titleColor, spotlights, serverCard, events, theme, null);
    }

    /** Compatibility with the branding used before the address placement and top-left logo options. */
    public Branding(String productName, String subtitle, String serverAddress,
                    String coverObject, String accentColor, String secondaryAccentColor,
                    String brandName, String brandEnglishName,
                    List<PlayerNewsArticle> newsArticles, PlayerCustomPage customPage,
                    List<PlayerContentPage> contentPages, List<PlayerMusicTrack> musicTracks,
                    String welcomeText, String topBarColor, String cardColor,
                    Double topBarOpacity, String titleColor,
                    List<PlayerSpotlight> spotlights, PlayerServerCard serverCard,
                    List<PlayerEvent> events, PlayerTheme theme,
                    List<PlayerPresentationImage> presentationImages) {
        this(productName, subtitle, serverAddress, coverObject, accentColor,
                secondaryAccentColor, brandName, brandEnglishName, newsArticles, customPage,
                contentPages, musicTracks, welcomeText, topBarColor, cardColor, topBarOpacity,
                titleColor, spotlights, serverCard, events, theme, presentationImages, null, null);
    }

    /** Uploaded assets; the release selects only files actually referenced by its showcase. */
    public Branding withPresentationImages(List<PlayerPresentationImage> images) {
        return new Branding(productName, subtitle, serverAddress, coverObject,
                accentColor, secondaryAccentColor, brandName, brandEnglishName,
                newsArticles, customPage, contentPages, musicTracks,
                welcomeText, topBarColor, cardColor, topBarOpacity, titleColor,
                spotlights, serverCard, events, theme, images, addressPlacement, brandLogoFile);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
