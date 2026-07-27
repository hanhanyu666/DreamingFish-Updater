package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {
    @Test
    void supportsStableForwardSlashGlobSemantics() {
        var pattern = GlobMatcher.compile("config/**/*.toml");
        assertTrue(pattern.matcher("config/a.toml").matches());
        assertTrue(pattern.matcher("config/sub/a.toml").matches());
        assertFalse(pattern.matcher("mods/a.toml").matches());
    }
}
