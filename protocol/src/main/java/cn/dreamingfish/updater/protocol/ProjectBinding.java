package cn.dreamingfish.updater.protocol;

public record ProjectBinding(
        int schemaVersion,
        String projectId,
        String baseUrl,
        String publicKey,
        String playerHome,
        String bundledCoverPath,
        Branding fallbackBranding
) {
    public ProjectBinding {
        fallbackBranding = fallbackBranding == null ? Branding.empty() : fallbackBranding;
    }

    public ProjectBinding withPlayerHome(String newPlayerHome) {
        return new ProjectBinding(
                schemaVersion,
                projectId,
                baseUrl,
                publicKey,
                newPlayerHome,
                bundledCoverPath,
                fallbackBranding
        );
    }
}
