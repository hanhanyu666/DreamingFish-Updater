package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
