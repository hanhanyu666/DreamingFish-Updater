package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.OptionalGroup;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import cn.dreamingfish.updater.protocol.Withdrawal;
import cn.dreamingfish.updater.protocol.WithdrawalItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenancePlayerTest {
    @TempDir
    Path temporary;

    @Test
    void switchedOffOptionalGroupsMoveTheirModsOutUntilSwitchedOn() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("groups"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path iris = jar(instance.resolve("mods/iris.jar"), "iris", "1.7");
        Path map = jar(instance.resolve("mods/map.jar"), "map", "1");
        ReleaseManifest release = release(List.of(
                        file(iris, "mods/iris.jar", "iris", "1.7", MaintenancePreset.SYNC, "visuals"),
                        file(map, "mods/map.jar", "map", "1", MaintenancePreset.SYNC, null)),
                List.of(new OptionalGroup("visuals", "美化包", "", true)), List.of());
        LocalModManager mods = new LocalModManager(instance, home);
        LocalOptionManager options = new LocalOptionManager(home);

        LocalModEntry irisEntry = entry(mods.scan(release), "mods/iris.jar");
        assertTrue(irisEntry.forced());
        assertEquals("visuals", irisEntry.group());
        assertEquals("1.7", irisEntry.version());
        assertThrows(java.io.IOException.class, () -> mods.setDisabled(irisEntry, true));

        options.setChoice("visuals", false);
        mods.reconcileDesiredState(release, options.snapshot().choices());
        assertFalse(Files.exists(iris));
        assertTrue(Files.exists(map));
        assertTrue(mods.snapshot().overrides().excludesComponent("iris"));
        assertFalse(options.view(release, options.snapshot().choices()).getFirst().enabled());

        options.setChoice("visuals", null);
        mods.reconcileDesiredState(release, options.snapshot().choices());
        assertFalse(mods.snapshot().overrides().excludesComponent("iris"));
        mods.finalizeSuccessfulUpdate(release);
        assertTrue(options.view(release, options.snapshot().choices()).getFirst().enabled());
        assertFalse(options.view(release, options.snapshot().choices()).getFirst().explicit());
    }

    @Test
    void anIndividualSwitchWinsOverItsGroup() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("individual"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path iris = jar(instance.resolve("mods/iris.jar"), "iris", "1.7");
        ReleaseManifest withoutGroup = release(List.of(
                file(iris, "mods/iris.jar", "iris", "1.7", MaintenancePreset.SYNC, null)), List.of(), List.of());
        LocalModManager mods = new LocalModManager(instance, home);
        mods.setDisabled(entry(mods.scan(withoutGroup), "mods/iris.jar"), true);

        ReleaseManifest grouped = release(List.of(
                        file(iris, "mods/iris.jar", "iris", "1.7", MaintenancePreset.SYNC, "visuals")),
                List.of(new OptionalGroup("visuals", "美化包", "", true)), List.of());
        mods.reconcileDesiredState(grouped, Map.of("visuals", true));
        assertFalse(Files.exists(iris));
        assertTrue(mods.snapshot().overrides().excludesComponent("iris"));
    }

    @Test
    void requiredModsIgnoreAnEarlierSwitchAfterARename() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("required"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path oldCore = jar(instance.resolve("mods/core-1.jar"), "core", "1");
        LocalModManager mods = new LocalModManager(instance, home);
        ReleaseManifest first = release(List.of(
                file(oldCore, "mods/core-1.jar", "core", "1", MaintenancePreset.SYNC, null)), List.of(), List.of());
        mods.setDisabled(entry(mods.scan(first), "mods/core-1.jar"), true);
        mods.reconcileDesiredState(first);
        assertFalse(Files.exists(oldCore));

        Path core = jar(instance.resolve("mods/core-2.jar"), "core", "2");
        ReleaseManifest second = release(List.of(
                file(core, "mods/core-2.jar", "core", "2", MaintenancePreset.REQUIRED, null)), List.of(), List.of());
        mods.reconcileDesiredState(second, Map.of());
        assertTrue(Files.exists(core));

        LocalModEntry entry = entry(mods.scan(second), "mods/core-2.jar");
        assertTrue(entry.forced());
        assertFalse(entry.disabled());
        assertEquals("REQUIRED", entry.preset());
        assertThrows(java.io.IOException.class, () -> mods.setDisabled(entry, false));
    }

    @Test
    void aSecondCopyOfAPublishedModListsThePublishedOne() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("duplicate"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path published = jar(instance.resolve("mods/b-map-2.jar"), "map", "2");
        jar(instance.resolve("mods/a-map-old.jar"), "map", "1");
        jar(instance.resolve("mods/c-map-older.jar"), "map", "0.5");
        ReleaseManifest release = release(List.of(
                file(published, "mods/b-map-2.jar", "map", "2", MaintenancePreset.SYNC, null)), List.of(), List.of());
        List<LocalModEntry> entries = new LocalModManager(instance, home).scan(release);
        assertEquals(1, entries.size());
        assertEquals("mods/b-map-2.jar", entries.getFirst().path());
        assertEquals("2", entries.getFirst().version());
    }

    @Test
    void withdrawnStoredCopiesStayOutOfTheGameWhenReEnabled() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("withdrawn"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path personal = jar(instance.resolve("mods/personal.jar"), "personal", "0.9");
        String badHash = CryptoSupport.sha256(personal);
        long badSize = Files.size(personal);
        LocalModManager mods = new LocalModManager(instance, home);
        ReleaseManifest empty = release(List.of(), List.of(), List.of());
        mods.setDisabled(entry(mods.scan(empty), "mods/personal.jar"), true);
        mods.reconcileDesiredState(empty);
        assertFalse(Files.exists(personal));

        ReleaseManifest withdrawn = release(List.of(), List.of(), List.of(new Withdrawal("bad", "崩服",
                Instant.now(), List.of(new WithdrawalItem(badHash, badSize, "mods/personal.jar", "personal", "0.9")))));
        LocalModEntry stored = entry(mods.scan(withdrawn), "mods/personal.jar");
        assertEquals("崩服", stored.withdrawnReason());
        mods.setDisabled(stored, false);
        mods.reconcileDesiredState(withdrawn);
        assertFalse(Files.exists(personal));
    }

    @Test
    void ownerDeletionDiscardsDisabledOfficialCopies() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("deleted"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path retired = jar(instance.resolve("mods/retired.jar"), "retired", "1");
        LocalModManager mods = new LocalModManager(instance, home);
        ReleaseManifest first = release(List.of(
                file(retired, "mods/retired.jar", "retired", "1", MaintenancePreset.SYNC, null)), List.of(), List.of());
        mods.setDisabled(entry(mods.scan(first), "mods/retired.jar"), true);
        mods.reconcileDesiredState(first);
        assertFalse(Files.exists(retired));
        assertEquals(1, storedJars(home));

        ReleaseManifest second = release(List.of(), List.of(), List.of());
        mods.finalizeSuccessfulUpdate(second);
        assertEquals(0, storedJars(home));
        assertTrue(mods.scan(second).isEmpty());
    }

    @Test
    void selfManagedModsAreKeptByModIdAndDefaultsCanBeRestored() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("files"));
        Path home = instance.resolve("DreamingFishUpdater");
        Path mapJar = jar(instance.resolve("mods/map-1.jar"), "map", "1");
        Path config = instance.resolve("config/map.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "zoom=2");
        ManifestFile configFile = new ManifestFile("config/map.toml", CryptoSupport.sha256(config),
                Files.size(config), FilePolicy.ENFORCED, false, null, null,
                MaintenancePreset.DEFAULT_CONFIG, null, null);
        ReleaseManifest first = release(List.of(configFile,
                file(mapJar, "mods/map-1.jar", "map", "1", MaintenancePreset.SYNC, null)), List.of(), List.of());
        LocalFileManager files = new LocalFileManager(instance, home);

        files.setManaged(entry(files.scan(first), "mods/map-1.jar"), false);
        ReleaseManifest renamed = release(List.of(configFile, new ManifestFile("mods/map-2.jar",
                "a".repeat(64), 5, FilePolicy.ENFORCED, false, "map", "Map",
                MaintenancePreset.SYNC, null, "2")), List.of(), List.of());
        assertTrue(entry(files.scan(renamed), "mods/map-2.jar").directlyExcluded());
        assertTrue(files.snapshot().overrides().selfManagesComponent("map"));

        LocalFileEntry pristine = entry(files.scan(first), "config/map.toml");
        assertEquals("DEFAULT_CONFIG", pristine.preset());
        assertEquals(Boolean.FALSE, pristine.modified());
        Files.writeString(config, "zoom=15");
        LocalFileEntry modified = entry(files.scan(first), "config/map.toml");
        assertEquals(Boolean.TRUE, modified.modified());
        files.requestReset(modified);
        assertTrue(entry(files.scan(first), "config/map.toml").resetPending());
        assertTrue(files.snapshot().overrides().resetRequested("config/map.toml"));
        assertThrows(java.io.IOException.class, () ->
                files.requestReset(entry(files.scan(renamed), "mods/map-2.jar")));
        files.clearResetRequests(List.of("config/map.toml"));
        assertFalse(files.snapshot().overrides().resetRequested("config/map.toml"));
        assertNull(entry(files.scan(first), "mods/map-1.jar").modified());
    }

    private static long storedJars(Path home) throws Exception {
        Path stored = home.resolve("local-mods/disabled");
        if (!Files.isDirectory(stored)) return 0;
        try (var files = Files.list(stored)) {
            return files.count();
        }
    }

    private static <T> T entry(List<T> entries, String path) {
        return entries.stream().filter(value -> {
            if (value instanceof LocalModEntry mod) return mod.path().equals(path);
            if (value instanceof LocalFileEntry file) return file.path().equals(path);
            return false;
        }).findFirst().orElseThrow();
    }

    private static ManifestFile file(Path jar, String path, String id, String version,
                                     MaintenancePreset preset, String group) throws Exception {
        return new ManifestFile(path, CryptoSupport.sha256(jar), Files.size(jar), FilePolicy.ENFORCED,
                false, id, id, preset, group, version);
    }

    private static ReleaseManifest release(List<ManifestFile> files, List<OptionalGroup> groups,
                                           List<Withdrawal> withdrawals) {
        List<ManifestFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(ManifestFile::path));
        return new ReleaseManifest(ProtocolConstants.RELEASE_SCHEMA_VERSION, "demo", "r1", 1,
                Instant.now(), "1.0", "0.2.0", "", Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY),
                List.of(), List.of(), List.of(), Branding.empty(), sorted, List.of(), groups, List.of(),
                withdrawals, List.of());
    }

    private static Path jar(Path path, String id, String version) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + id
                    + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
