package cn.dreamingfish.updater.player;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerArgumentsTest {
    @Test
    void parsesTheBootstrapContract() {
        PlayerArguments arguments = PlayerArguments.parse(List.of(
                "--bootstrap-port", "24567",
                "--bootstrap-token", "a".repeat(43),
                "--instance", "instance",
                "--binding", "instance/.dreamingfish-bootstrap/project-binding.json",
                "--player-name", "Player",
                "--player-uuid", "00000000000000000000000000000000",
                "--launcher-brand", "PCL2",
                "--launcher-version", "2.9.4"
        ));

        assertEquals(24567, arguments.bootstrapPort());
        assertEquals(Path.of("instance").toAbsolutePath().normalize(), arguments.instanceRoot());
        assertEquals("Player", arguments.playerName());
        assertEquals("PCL2", arguments.launcherBrand());
    }

    @Test
    void recognizesPreviewModeAndRejectsUnknownArguments() {
        assertTrue(PlayerArguments.parse(List.of("--preview")).preview());
        assertThrows(IllegalArgumentException.class, () -> PlayerArguments.parse(List.of(
                "--bootstrap-port", "24567",
                "--bootstrap-token", "a".repeat(43),
                "--instance", "instance",
                "--binding", "binding.json",
                "--unexpected", "value"
        )));
    }
}
