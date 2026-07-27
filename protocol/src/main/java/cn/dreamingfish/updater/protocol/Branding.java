package cn.dreamingfish.updater.protocol;

public record Branding(
        String productName,
        String subtitle,
        String serverAddress,
        String coverObject,
        String accentColor,
        String secondaryAccentColor
) {
    public static Branding empty() {
        return new Branding("梦屿", "灾变之后，仍有人在这里守望。", "", null,
                "#2ee8df", "#b06cff");
    }
}
