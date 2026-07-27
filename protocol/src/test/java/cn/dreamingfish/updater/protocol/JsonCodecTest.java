package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonCodecTest {
    private final JsonCodec codec = new JsonCodec();

    @Test
    void writesDeterministicJsonAndRoundTripsRecords() {
        ReleaseManifest manifest = sampleManifest();
        byte[] first = codec.write(manifest);
        byte[] second = codec.write(manifest);

        assertArrayEquals(first, second);
        assertEquals(manifest, codec.read(first, ReleaseManifest.class));
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        byte[] duplicate = "{\"schemaVersion\":1,\"schemaVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> codec.read(duplicate, ProjectBinding.class));
    }

    static ReleaseManifest sampleManifest() {
        return new ReleaseManifest(
                1,
                "dreamhaven",
                "2026.07.26",
                1,
                Instant.parse("2026-07-26T00:00:00Z"),
                "2026.07.26",
                "0.1.0",
                "Initial release",
                Set.of(),
                new Branding("守望梦屿", "灾变之后，仍有人在这里守望。", "mc.example.test", null,
                        "#2ee8df", "#b06cff"),
                List.of(new ManifestFile("mods/example.jar", "a".repeat(64), 42, FilePolicy.ENFORCED, false))
        );
    }
}
