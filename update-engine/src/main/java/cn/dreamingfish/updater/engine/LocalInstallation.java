package cn.dreamingfish.updater.engine;

record LocalInstallation(SignedRelease release, VerifiedInstallation installation,
                         TrustState trustState, boolean bundledBaseline) {
}
