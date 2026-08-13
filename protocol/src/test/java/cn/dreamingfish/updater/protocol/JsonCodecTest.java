package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void prettyWriterKeepsFilesReadableAndRoundTrips() {
        byte[] pretty = codec.writePretty(sampleManifest());
        String text = new String(pretty, java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains(System.lineSeparator())
                || text.contains("\n"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("  \"schemaVersion\""));
        assertEquals(sampleManifest(), codec.read(pretty, ReleaseManifest.class));
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        byte[] duplicate = "{\"schemaVersion\":1,\"schemaVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(ProtocolException.class, () -> codec.read(duplicate, ProjectBinding.class));
    }

    @Test
    void suppliesTitleBarBrandDefaultsForLegacyJson() {
        String legacy = """
                {"productName":"旧整合包","subtitle":"旧说明",\
                "serverAddress":"","coverObject":null,\
                "accentColor":"#2ee8df","secondaryAccentColor":"#b06cff"}
                """;

        Branding branding = codec.read(
                legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Branding.class);

        assertEquals("梦鱼服", branding.brandName());
        assertEquals("DreamingFish", branding.brandEnglishName());
        assertNull(branding.newsArticles());
        assertNull(branding.customPage());
        assertNull(branding.contentPages());
    }

    @Test
    void readsLegacyDefaultPolicyWithoutGeneratingANewPolicyName() {
        byte[] legacy = "\"DEFAULT\"".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(FilePolicy.LEGACY_MISSING_ONLY,
                codec.read(legacy, FilePolicy.class));
        assertEquals("\"DEFAULT\"", new String(
                codec.write(FilePolicy.LEGACY_MISSING_ONLY),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsConfigurablePlayerContent() {
        Branding branding = new Branding(
                "星河", "一起出发", "play.example.com", null,
                "#112233", "#445566", "星河服", "StarRiver",
                List.of(new PlayerNewsArticle(
                        "welcome", "欢迎", "第一条消息", "2026-08-04",
                        "https://example.com/cover.jpg", "# 欢迎\n正文")),
                new PlayerCustomPage(true, "玩法介绍", "GUIDE",
                        "从这里开始", "先看看这几件事", "- 安装整合包"),
                List.of(new PlayerContentPage("rules", "服务器规则", false,
                        "RULES", "游玩规则", "一起维护良好环境", "- 友善交流", List.of())));

        Branding restored = codec.read(codec.write(branding), Branding.class);

        assertEquals(branding, restored);
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
