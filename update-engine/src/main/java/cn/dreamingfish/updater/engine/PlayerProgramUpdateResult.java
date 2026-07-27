package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.PlayerProgramManifest;

public record PlayerProgramUpdateResult(
        PlayerProgramUpdateOutcome outcome,
        PlayerProgramManifest manifest,
        long downloadedBytes
) {
    public boolean restartRequired() {
        return outcome == PlayerProgramUpdateOutcome.INSTALLED_RESTART_REQUIRED;
    }
}
