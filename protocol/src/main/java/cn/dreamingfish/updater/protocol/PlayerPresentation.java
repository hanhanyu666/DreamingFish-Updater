package cn.dreamingfish.updater.protocol;

/**
 * Signed, lightweight player presentation data that can change independently
 * from a modpack release. File-backed resources remain release-managed.
 */
public record PlayerPresentation(
        int schemaVersion,
        String projectId,
        Branding branding
) {
    public PlayerPresentation {
        Branding source = branding == null ? Branding.empty() : branding;
        branding = new Branding(
                source.productName(),
                source.subtitle(),
                source.serverAddress(),
                null,
                source.accentColor(),
                source.secondaryAccentColor(),
                source.brandName(),
                source.brandEnglishName(),
                source.newsArticles(),
                source.customPage(),
                source.contentPages(),
                null,
                source.welcomeText(),
                source.topBarColor(),
                source.cardColor(),
                source.topBarOpacity(),
                source.titleColor());
    }
}
