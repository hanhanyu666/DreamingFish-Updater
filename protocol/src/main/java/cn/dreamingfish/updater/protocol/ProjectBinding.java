package cn.dreamingfish.updater.protocol;

public record ProjectBinding(
        int schemaVersion,
        String projectId,
        String baseUrl,
        String publicKey,
        String playerHome,
        String bundledCoverPath,
        Branding fallbackBranding,
        PackRootSpec packRoot
) {
    public ProjectBinding {
        fallbackBranding = fallbackBranding == null ? Branding.empty() : fallbackBranding;
    }

    public ProjectBinding(int schemaVersion, String projectId, String baseUrl, String publicKey,
                          String playerHome, String bundledCoverPath, Branding fallbackBranding) {
        this(schemaVersion, projectId, baseUrl, publicKey, playerHome, bundledCoverPath, fallbackBranding, null);
    }

    public ProjectBinding withPlayerHome(String newPlayerHome) {
        return new ProjectBinding(
                schemaVersion,
                projectId,
                baseUrl,
                publicKey,
                newPlayerHome,
                bundledCoverPath,
                fallbackBranding, packRoot
        );
    }

    public ProjectBinding withPackRoot(PackRootSpec grant) {
        return new ProjectBinding(schemaVersion, projectId, baseUrl, publicKey, playerHome,
                bundledCoverPath, fallbackBranding, grant);
    }
}
