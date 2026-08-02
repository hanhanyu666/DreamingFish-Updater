package cn.dreamingfish.updater.protocol;

public record Branding(
        String productName,
        String subtitle,
        String serverAddress,
        String coverObject,
        String accentColor,
        String secondaryAccentColor,
        String brandName,
        String brandEnglishName
) {
    public static final String DEFAULT_BRAND_NAME = "梦鱼服";
    public static final String DEFAULT_BRAND_ENGLISH_NAME = "DreamingFish";

    public Branding {
        brandName = defaultText(brandName, DEFAULT_BRAND_NAME);
        brandEnglishName = defaultText(
                brandEnglishName, DEFAULT_BRAND_ENGLISH_NAME);
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

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
