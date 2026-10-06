package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import com.sun.nio.file.ExtendedOpenOption;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PlayerAuditProbe {
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        releasedDisabledMod(Files.createTempDirectory(output, "released-disabled-"));
        failedLocalMove(Files.createTempDirectory(output, "move-failure-"));
    }

    private static void releasedDisabledMod(Path instance) throws Exception {
        Path playerHome = instance.resolve("DreamingFishUpdater");
        Path jar = fabricJar(instance.resolve("mods/retired.jar"), "retired");
        ManifestFile file = new ManifestFile("mods/retired.jar", CryptoSupport.sha256(jar),
                Files.size(jar), FilePolicy.ENFORCED, false, "retired", "Retired Mod");
        ReleaseManifest first = release(1, List.of(file), List.of());
        ReleaseManifest second = release(2, List.of(), List.of("mods/retired.jar"));
        LocalModManager manager = new LocalModManager(instance, playerHome);
        manager.setDisabled(manager.scan(first).getFirst(), true);
        manager.reconcileDesiredState(first);
        LocalModPreferences stored = new JsonCodec().read(
                playerHome.resolve("state/local-mod-preferences.json"), LocalModPreferences.class);
        Path backup = playerHome.resolve(stored.mods().getFirst().storedFiles().getFirst().storedPath());
        manager.reconcileDesiredState(second);
        manager.setDisabled(manager.scan(second).getFirst(), false);
        manager.reconcileDesiredState(second);
        manager.finalizeSuccessfulUpdate();
        if (!Files.exists(jar)) throw new AssertionError("Released disabled copy was lost");
        System.out.println("RELEASED_DISABLED_MOD activeCopyExists=" + Files.exists(jar)
                + " storedCopyExists=" + Files.exists(backup)
                + " remainingPreferenceCount=" + manager.scan(second).size());
    }

    private static void failedLocalMove(Path instance) throws Exception {
        Path playerHome = instance.resolve("DreamingFishUpdater");
        Path first = fabricJar(instance.resolve("mods/aa-first.jar"), "first");
        Path blocked = fabricJar(instance.resolve("mods/zz-blocked.jar"), "blocked");
        LocalModManager manager = new LocalModManager(instance, playerHome);
        for (LocalModEntry entry : manager.scan(null)) manager.setDisabled(entry, true);
        boolean failed = false;
        try (FileChannel ignored = FileChannel.open(blocked, StandardOpenOption.READ,
                ExtendedOpenOption.NOSHARE_DELETE)) {
            try {
                manager.reconcileDesiredState();
            } catch (java.io.IOException expected) {
                failed = true;
            }
        }
        LocalModPreferences preferences = new JsonCodec().read(
                playerHome.resolve("state/local-mod-preferences.json"), LocalModPreferences.class);
        long indexed = preferences.mods().stream().mapToLong(p -> p.storedFiles().size()).sum();
        Path disabled = playerHome.resolve("local-mods/disabled");
        long stored;
        try (var stream = Files.list(disabled)) { stored = stream.count(); }
        if (!failed || !Files.exists(first) || stored != indexed) {
            throw new AssertionError("Failed mod move did not restore files and indexes");
        }
        System.out.println("LOCAL_MOVE_FAILURE ioFailure=" + failed
                + " firstActiveCopyExists=" + Files.exists(first)
                + " storedFileCount=" + stored + " indexedStoredFileCount=" + indexed);
    }

    private static Path fabricJar(Path path, String id) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"id\":\"" + id + "\",\"name\":\"" + id
                    + "\",\"version\":\"1.0.0\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static ReleaseManifest release(long sequence, List<ManifestFile> files,
                                           List<String> releasedPaths) {
        return new ReleaseManifest(ProtocolConstants.RELEASE_SCHEMA_VERSION, "demo",
                "r" + sequence, sequence, Instant.now(), sequence + ".0.0", "0.1.0", "",
                releasedPaths.isEmpty() ? Set.of() : Set.of(ProtocolConstants.CAPABILITY_RELEASED_PATHS),
                List.of(), List.of(), releasedPaths, Branding.empty(), files);
    }
}
