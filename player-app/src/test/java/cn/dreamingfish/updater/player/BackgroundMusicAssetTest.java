package cn.dreamingfish.updater.player;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BackgroundMusicAssetTest {
    @Test
    void bundlesTheExactWebsiteBackgroundMusic() throws Exception {
        try (InputStream input = BackgroundMusic.class.getResourceAsStream("audio/bg_music.mp3")) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            assertEquals(4_954_757, bytes.length);
            assertEquals("c41a9dbe5a72280a2bb8ae815366bb23dd6e9f437480c39adfac1f0e420dbfec",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
    }
}
