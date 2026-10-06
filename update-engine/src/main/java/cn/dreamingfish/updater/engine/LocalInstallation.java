package cn.dreamingfish.updater.engine;

record LocalInstallation(SignedRelease release, VerifiedInstallation installation,
                         TrustState trustState, boolean bundledBaseline,
                         MaintenanceState state) {
    LocalInstallation(SignedRelease release, VerifiedInstallation installation,
                      TrustState trustState, boolean bundledBaseline) {
        this(release, installation, trustState, bundledBaseline,
                MaintenanceState.empty(release.manifest().projectId()));
    }
}
