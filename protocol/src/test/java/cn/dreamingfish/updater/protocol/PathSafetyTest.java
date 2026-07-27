package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathSafetyTest {
    @TempDir
    Path tempDirectory;

    @Test
    void acceptsNormalizedRelativePaths() {
        assertEquals("mods/example.jar", PathSafety.normalizeManifestPath("mods/example.jar"));
        assertEquals("config/a.b.toml", PathSafety.normalizeManifestPath("config/a.b.toml"));
    }

    @Test
    void rejectsTraversalAndWindowsAmbiguities() {
        List<String> unsafe = List.of(
                "../outside", "/absolute", "mods\\bad.jar", "mods//bad.jar", "mods/./bad.jar",
                "mods/con.txt", "config/name. ", "config/name.", "config/a:b", "config/*"
        );
        for (String value : unsafe) {
            assertThrows(ProtocolException.class, () -> PathSafety.normalizeManifestPath(value), value);
        }
    }

    @Test
    void rejectsCaseInsensitiveCollisions() {
        assertThrows(ProtocolException.class,
                () -> PathSafety.validateDistinctPaths(List.of("mods/A.jar", "mods/a.jar")));
    }

    @Test
    void rejectsExistingSymbolicLinkTraversalWhenSupported() throws IOException {
        Path root = Files.createDirectory(tempDirectory.resolve("root"));
        Path outside = Files.createDirectory(tempDirectory.resolve("outside"));
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        assertThrows(ProtocolException.class, () -> PathSafety.resolveInside(root, "linked/file.txt"));
    }
}
