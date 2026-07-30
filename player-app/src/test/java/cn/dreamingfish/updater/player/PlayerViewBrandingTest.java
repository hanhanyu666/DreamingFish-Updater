package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerViewBrandingTest {
    @Test
    void usesDreamingFishDefaultsWhenBrandingIsMissing() {
        assertEquals(Branding.empty(), PlayerView.displayBranding(null));
    }

    @Test
    void replacesACharacterEncodingDamagedBrand() {
        Branding damaged = new Branding("\uFFFD\uFFFD\uFFFD", "Minecraft 整合包更新", "", null,
                "#ffffff", "#000000");

        assertEquals(Branding.empty(), PlayerView.displayBranding(damaged));
    }

    @Test
    void preservesAValidCustomBrandAndDefaultsOnlyItsSubtitle() {
        Branding custom = new Branding("Custom Pack", "", "mc.example.test", null,
                "#112233", "#445566");

        Branding display = PlayerView.displayBranding(custom);

        assertEquals("Custom Pack", display.productName());
        assertEquals("灾变之后，仍有人在这里守望。", display.subtitle());
        assertEquals("#112233", display.accentColor());
        assertEquals("#445566", display.secondaryAccentColor());
    }

    @Test
    void formatsHoverDetailsByFileOperationAndLimitsLongUpdates() {
        List<Path> installed = java.util.stream.IntStream.range(0, 32)
                .mapToObj(index -> Path.of("mods", "updated-" + index + ".jar"))
                .toList();

        String details = PlayerView.formatUpdateFileDetails(
                installed,
                List.of(Path.of("mods", "removed.jar")),
                List.of(Path.of("mods", "archived.jar")));

        assertTrue(details.contains("安装 / 更新"));
        assertTrue(details.contains("mods/updated-0.jar"));
        assertTrue(details.contains("另外 4 项未展开"));
    }

    @Test
    void formatsPlayerAddedModsForTheHomePageHoverEntry() {
        String details = PlayerView.formatUnmanagedModDetails(List.of(
                Path.of("mods", "xaeros-minimap.jar"),
                Path.of("mods", "embeddium-options-api.jar")));

        assertTrue(details.contains("玩家自选模组（2 个）"));
        assertTrue(details.contains("mods/xaeros-minimap.jar"));
        assertTrue(details.contains("点击进入“本地文件 → 模组启停”管理"));
    }
}
