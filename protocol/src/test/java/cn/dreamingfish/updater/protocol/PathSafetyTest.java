package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void acceptsOrdinaryDeepPathsAndMissingDescendants() throws IOException {
        Path root = Files.createDirectories(tempDirectory.resolve("pack/.minecraft/versions/game"));
        Path file = root.resolve("tacz/default/data/index/attachments/muzzle.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");

        assertEquals(file, PathSafety.resolveInside(root,
                "tacz/default/data/index/attachments/muzzle.json"));
        assertEquals(root.resolve("tacz/new/dir/file.json"),
                PathSafety.resolveInside(root, "tacz/new/dir/file.json"));
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
        assertThrows(ProtocolException.class, () -> PathSafety.resolveInside(link, "file.txt"));
    }

    @Test
    void rejectsWindowsJunctionsAsBothAncestorsAndRoots() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Path root = Files.createDirectory(tempDirectory.resolve("junction-root"));
        Path outside = Files.createDirectory(tempDirectory.resolve("junction-outside"));
        Path link = root.resolve("mods");
        String command = "New-Item -ItemType Junction -Path '" + link.toString().replace("'", "''")
                + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        try {
            assertFalse(Files.isSymbolicLink(link));
            assertThrows(ProtocolException.class, () -> PathSafety.resolveInside(root, "mods/new/file.jar"));
            assertThrows(ProtocolException.class, () -> PathSafety.resolveInside(link, "file.jar"));
            assertThrows(ProtocolException.class, () -> PathSafety.createSafeDirectories(link.resolve("new")));
            assertFalse(Files.exists(outside.resolve("new")));
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void rejectsDirectoryReplacedByAnInRootJunctionAfterEarlierCheck() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Path root = Files.createDirectory(tempDirectory.resolve("replace-root"));
        Path directory = Files.createDirectory(root.resolve("data"));
        Path other = Files.createDirectory(root.resolve("other"));
        assertEquals(directory.resolve("new/file.json"),
                PathSafety.resolveInside(root, "data/new/file.json"));
        Files.delete(directory);
        String command = "New-Item -ItemType Junction -Path '" + directory.toString().replace("'", "''")
                + "' -Target '" + other.toString().replace("'", "''") + "' | Out-Null";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        try {
            assertThrows(ProtocolException.class, () -> PathSafety.resolveInside(root, "data/new/file.json"));
            assertThrows(ProtocolException.class, () -> PathSafety.createSafeDirectories(directory.resolve("new")));
            assertFalse(Files.exists(other.resolve("new")));
        } finally {
            Files.delete(directory);
        }
    }
}
