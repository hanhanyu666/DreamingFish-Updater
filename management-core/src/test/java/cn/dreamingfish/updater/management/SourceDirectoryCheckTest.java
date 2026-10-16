package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SourceDirectoryCheckTest {
    @TempDir
    Path temporary;
    private final List<Path> links = new ArrayList<>();

    @AfterEach
    void removeLinks() throws IOException {
        // The temporary-directory cleanup cannot walk through a junction whose target is gone.
        for (Path link : links) Files.deleteIfExists(link);
    }

    @Test
    void creationRefusesASourceWithABrokenLinkAndLeavesNoProjectBehind() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        Files.createDirectories(fixture.source.resolve("game"));
        brokenLink(fixture.source.resolve("game/old-worktree"));

        ManagementException refused = assertThrows(ManagementException.class, fixture::createProject);

        assertTrue(refused.getMessage().contains("game/old-worktree"), refused.getMessage());
        assertTrue(refused.getMessage().contains("专用文件夹"), refused.getMessage());
        assertTrue(fixture.database.listProjects().isEmpty());
        assertFalse(Files.exists(fixture.paths.keys().resolve("demo.pk8")));
    }

    @Test
    void movingAProjectToAnUnreadableSourceKeepsTheCurrentOne() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        Path desktop = Files.createDirectories(temporary.resolve("desktop/tools"));
        brokenLink(desktop.resolve("port-worktree"));

        ManagementException refused = assertThrows(ManagementException.class,
                () -> fixture.projects.configure("demo", desktop.getParent(), null, null, null));

        assertTrue(refused.getMessage().contains("tools/port-worktree"), refused.getMessage());
        assertEquals(fixture.source.toAbsolutePath().normalize(),
                fixture.database.requireProject("demo").sourceDirectory());
    }

    @Test
    void listingAndScanningNameALinkThatBrokeAfterCreation() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        Files.writeString(Files.createDirectories(fixture.source.resolve("mods")).resolve("a.jar"), "a");
        brokenLink(fixture.source.resolve("mods/cache"));
        SourceFileService files = new SourceFileService(fixture.paths, fixture.database, fixture.json);

        ManagementException listed = assertThrows(ManagementException.class, () -> files.list("demo"));
        ManagementException scanned = assertThrows(ManagementException.class,
                () -> fixture.scanner.createPreview("demo"));

        assertTrue(listed.getMessage().contains("mods/cache"), listed.getMessage());
        assertTrue(scanned.getMessage().contains("mods/cache"), scanned.getMessage());
    }

    /** Creates a link whose target no longer exists: a junction on Windows, a symbolic link elsewhere. */
    private void brokenLink(Path link) throws IOException, InterruptedException {
        Path target = Files.createDirectories(temporary.resolve("gone-" + link.getFileName()));
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            mklink.getInputStream().readAllBytes();
            assumeTrue(mklink.waitFor() == 0, "junctions are unavailable");
        } else {
            Files.createSymbolicLink(link, target);
        }
        links.add(link);
        Files.delete(target);
    }
}
