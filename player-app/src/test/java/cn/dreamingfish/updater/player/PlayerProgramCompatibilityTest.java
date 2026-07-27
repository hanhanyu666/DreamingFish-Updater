package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.PlayerProgramUpdater;
import cn.dreamingfish.updater.engine.UpdateErrorCode;
import cn.dreamingfish.updater.engine.UpdateException;
import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerProgramCompatibilityTest {
    @Test
    void packagedAgentMeetsThePublishedCompatibilityFloor() {
        PlayerProgramManifest manifest = manifestRequiring(PlayerApplication.BOOTSTRAP_AGENT_VERSION);

        assertDoesNotThrow(() -> PlayerProgramUpdater.requireCompatibleVersions(
                PlayerApplication.VERSION, PlayerApplication.BOOTSTRAP_AGENT_VERSION, manifest));
    }

    @Test
    void catchesAPlayerVersionMistakenForTheBootstrapVersion() {
        PlayerProgramManifest manifest = manifestRequiring("0.1.4");

        UpdateException error = assertThrows(UpdateException.class,
                () -> PlayerProgramUpdater.requireCompatibleVersions(
                        PlayerApplication.VERSION, PlayerApplication.BOOTSTRAP_AGENT_VERSION, manifest));

        assertEquals(UpdateErrorCode.UNSUPPORTED_PLAYER_VERSION, error.code());
    }

    private static PlayerProgramManifest manifestRequiring(String bootstrapVersion) {
        return new PlayerProgramManifest(1, "build_server", "windows-x64",
                PlayerApplication.VERSION, Instant.parse("2026-07-26T00:00:00Z"),
                "DreamingFishUpdater.exe", bootstrapVersion, Set.of(),
                List.of(new PlayerProgramFile("DreamingFishUpdater.exe", "a".repeat(64), 1, true)));
    }
}
