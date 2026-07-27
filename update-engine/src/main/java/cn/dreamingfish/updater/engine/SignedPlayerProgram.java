package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.PlayerProgramManifest;

record SignedPlayerProgram(PlayerProgramManifest manifest, byte[] bytes, String signature, String sha256) {
    SignedPlayerProgram {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
