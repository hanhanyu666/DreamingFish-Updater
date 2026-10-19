package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Isolated regression probe. No existing player instance is accessed. */
public final class EngineReviewProbe {
    public static void main(String[] args) throws Exception {
        Path instance = Files.createDirectories(Path.of(args[0]).resolve("offline-running-game"));
        try (TestUpdateServer server = new TestUpdateServer()) {
            var good = server.file("config/good.txt", "good", FilePolicy.ENFORCED);
            var bad = server.file("config/bad.txt", "withdrawn", FilePolicy.ENFORCED);
            var base = server.policy(1, "r1").file(good, MaintenancePreset.SYNC)
                    .withdraw("known-bad", "review fixture", bad).build();
            var release = new ReleaseManifest(base.schemaVersion(), base.projectId(), base.releaseId(),
                    base.sequence(), base.createdAt(), base.displayVersion(), "0.2.0", base.changelog(),
                    ProtocolConstants.RELEASE_CAPABILITIES, base.forcedSyncDirectories(), base.forcedSyncFiles(),
                    base.releasedPaths(), base.branding(), base.files(), base.cleanupDirectories(),
                    base.optionalGroups(), base.retainedSelfManagedPaths(), base.withdrawals(), base.corrections());
            ManifestValidator.validateRelease(release, ProtocolConstants.RELEASE_CAPABILITIES);
            server.bundle(instance, release, true);
            Files.write(instance.resolve(bad.path()), bad.bytes());
            server.unavailable = true;
            Path lockPath = instance.resolve(".dreamingfish-bootstrap/game.lock");
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var lock = channel.lock(0, Long.MAX_VALUE, true)) {
                if (GameUpdateLock.tryAcquire(lockPath) != null) throw new AssertionError("Fixture lock not held");
                var result = new UpdateEngine().update(UpdateRequest.defaults(instance,
                        instance.resolve("DreamingFishUpdater"), server.binding(), "0.2.0",
                        ProtocolConstants.RELEASE_CAPABILITIES), null);
                System.out.println("OFFLINE_WHILE_GAME_LOCKED outcome=" + result.outcome()
                        + " badFileExists=" + Files.exists(instance.resolve(bad.path()))
                        + " archived=" + result.archived().size());
            }
        }
        Path cached = Files.createDirectories(Path.of(args[0]).resolve("cached-integrity"));
        try (TestUpdateServer server = new TestUpdateServer()) {
            var file = server.file("config/required.txt", "good", FilePolicy.ENFORCED);
            var release = server.policy(1, "r1").file(file, MaintenancePreset.REQUIRED).build();
            server.bundle(cached, release, true);
            server.serve(release);
            var request = UpdateRequest.defaults(cached, cached.resolve("DreamingFishUpdater"),
                    server.binding(), "0.2.0", ProtocolConstants.RELEASE_CAPABILITIES);
            new UpdateEngine().update(request, null);
            Path target = cached.resolve(file.path());
            var modified = Files.getLastModifiedTime(target);
            Files.writeString(target, "evil");
            Files.setLastModifiedTime(target, modified);
            server.unavailable = true;
            var result = new UpdateEngine().update(request, null);
            System.out.println("SAME_SIZE_MTIME outcome=" + result.outcome()
                    + " currentContent=" + Files.readString(target));
        }
    }
}
