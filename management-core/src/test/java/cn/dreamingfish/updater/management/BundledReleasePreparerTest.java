package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledReleasePreparerTest {
    @TempDir
    Path temporary;

    @Test
    void materializesAndSignsTheExplicitHistoricalReleaseAndClearsRuntimeState() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        Path sourceMod = fixture.source.resolve("mods/example.jar");
        Files.createDirectories(sourceMod.getParent());
        Files.writeString(sourceMod, "release-one");
        fixture.scanner.createPreview("demo");
        StoredRelease releaseOne = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.0", "One");

        Files.writeString(sourceMod, "release-two");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "2.0.0", "0.1.0", "Two");

        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        Path state = Files.createDirectories(playerHome.resolve("state"));
        Files.writeString(state.resolve("active-player.properties"), "schema=1\n");
        Files.writeString(state.resolve("first-run-complete"), "1\n");
        Files.createDirectories(playerHome.resolve("logs"));
        Files.writeString(playerHome.resolve("logs/player-updater.log"), "test run");

        BundledReleasePreparer.PreparedBundledRelease prepared =
                new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                        .prepare("demo", releaseOne.releaseId(), instance, playerHome);

        assertEquals(releaseOne.releaseId(), prepared.releaseId());
        assertEquals("release-one", Files.readString(instance.resolve("mods/example.jar")));
        assertArrayEquals(Files.readAllBytes(releaseOne.manifestPath()), Files.readAllBytes(
                instance.resolve(".dreamingfish-bootstrap/bundled-release/manifest.json")));
        assertTrue(Files.isRegularFile(instance.resolve(
                ".dreamingfish-bootstrap/bundled-release/manifest.sig")));
        assertTrue(Files.isRegularFile(state.resolve("active-player.properties")));
        assertFalse(Files.exists(state.resolve("first-run-complete")));
        assertFalse(Files.exists(playerHome.resolve("logs")));
    }

    @Test
    void rejectsAnInstanceThatContainsFilesFromADifferentReleaseBeforeCleaningIt() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("wrong-management"));
        fixture.createProject();
        Path sourceMod = fixture.source.resolve("mods/example.jar");
        Files.createDirectories(sourceMod.getParent());
        Files.writeString(sourceMod, "release-one");
        fixture.scanner.createPreview("demo");
        StoredRelease releaseOne = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.0", "One");

        Path instance = Files.createDirectories(temporary.resolve("wrong-instance"));
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        Path state = Files.createDirectories(playerHome.resolve("state"));
        Files.writeString(state.resolve("first-run-complete"), "must-survive\n");
        Path instanceMod = instance.resolve("mods/example.jar");
        Files.createDirectories(instanceMod.getParent());
        Files.writeString(instanceMod, "release-two");

        assertThrows(ManagementException.class,
                () -> new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                        .prepare("demo", releaseOne.releaseId(), instance, playerHome));
        assertEquals("release-two", Files.readString(instanceMod));
        assertEquals("must-survive\n", Files.readString(state.resolve("first-run-complete")));
        assertFalse(Files.exists(instance.resolve(
                ".dreamingfish-bootstrap/bundled-release/manifest.json")));
    }

    @Test
    void rejectsSelectingAnOldReleaseWhenTheInstanceContainsOnlyNewlyAddedManagedFiles() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("added-management"));
        fixture.createProject();
        Path mods = Files.createDirectories(fixture.source.resolve("mods"));
        Files.writeString(mods.resolve("common.jar"), "common");
        fixture.scanner.createPreview("demo");
        StoredRelease releaseOne = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.0", "One");
        Files.writeString(mods.resolve("added-in-two.jar"), "added");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "2.0.0", "0.1.0", "Two");

        Path instance = Files.createDirectories(temporary.resolve("added-instance"));
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        Path instanceMods = Files.createDirectories(instance.resolve("mods"));
        Files.writeString(instanceMods.resolve("common.jar"), "common");
        Files.writeString(instanceMods.resolve("added-in-two.jar"), "added");

        ManagementException failure = assertThrows(ManagementException.class,
                () -> new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                        .prepare("demo", releaseOne.releaseId(), instance, playerHome));
        assertTrue(failure.getMessage().contains("another release"));
        assertFalse(Files.exists(instance.resolve(
                ".dreamingfish-bootstrap/bundled-release/manifest.json")));
    }
}
