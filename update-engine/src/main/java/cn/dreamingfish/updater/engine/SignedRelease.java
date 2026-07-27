package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ReleaseManifest;

record SignedRelease(ReleaseManifest manifest, byte[] bytes, String signature, String sha256) {
    SignedRelease {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
