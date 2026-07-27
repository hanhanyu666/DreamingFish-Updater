package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateEngineTest {
    @TempDir
    Path temporary;

    @Test
    void installsRepairsUpdatesAndAllowsVerifiedOfflineUse() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            Files.createDirectories(instance.resolve("mods"));
            Files.writeString(instance.resolve("mods/custom.jar"), "custom");

            TestUpdateServer.TestFile forcedV1 = server.file("config/forced.txt", "forced-v1", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile defaults = server.file("config/player.txt", "default", FilePolicy.DEFAULT);
            TestUpdateServer.TestFile managedMod = server.file("mods/managed.jar", "managed", FilePolicy.ENFORCED);
            ReleaseManifest first = server.release(1, "release-1", forcedV1, defaults, managedMod);
            server.serve(first);
            server.bundle(instance, first, false);

            UpdateEngine engine = new UpdateEngine();
            UpdateResult installed = engine.update(request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.UPDATED, installed.outcome());
            assertEquals("forced-v1", Files.readString(instance.resolve("config/forced.txt")));
            assertEquals("default", Files.readString(instance.resolve("config/player.txt")));
            assertEquals(Set.of(Path.of("mods/custom.jar")), Set.copyOf(installed.unmanagedMods()));

            Files.writeString(instance.resolve("config/player.txt"), "player-choice");
            Files.writeString(instance.resolve("config/forced.txt"), "tampered");
            UpdateResult repaired = engine.update(request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.UPDATED, repaired.outcome());
            assertEquals("forced-v1", Files.readString(instance.resolve("config/forced.txt")));
            assertEquals("player-choice", Files.readString(instance.resolve("config/player.txt")));

            UpdateResult current = engine.update(request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.UP_TO_DATE, current.outcome());

            TestUpdateServer.TestFile forcedV2 = server.file("config/forced.txt", "forced-v2", FilePolicy.ENFORCED);
            ReleaseManifest second = server.release(2, "release-2", forcedV2);
            server.serve(second);
            UpdateResult updated = engine.update(request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.UPDATED, updated.outcome());
            assertEquals("forced-v2", Files.readString(instance.resolve("config/forced.txt")));
            assertFalse(Files.exists(instance.resolve("mods/managed.jar")));
            assertEquals("player-choice", Files.readString(instance.resolve("config/player.txt")));

            server.unavailable = true;
            UpdateResult offline = engine.update(request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.OFFLINE_ALLOWED, offline.outcome());

            Files.writeString(instance.resolve("config/forced.txt"), "damaged");
            UpdateException rejected = assertThrows(UpdateException.class,
                    () -> engine.update(request(instance, playerHome, server.binding()), null));
            assertEquals(UpdateErrorCode.LOCAL_STATE_INVALID, rejected.code());
        }
    }

    @Test
    void refusesBadSignaturesAndReplayedReleases() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("signature-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile firstFile = server.file("config/value.txt", "one", FilePolicy.ENFORCED);
            ReleaseManifest first = server.release(1, "release-1", firstFile);
            server.serve(first);
            server.bundle(instance, first, false);
            UpdateEngine engine = new UpdateEngine();
            engine.update(request(instance, playerHome, server.binding()), null);

            server.invalidSignature = true;
            UpdateException invalid = assertThrows(UpdateException.class,
                    () -> engine.update(request(instance, playerHome, server.binding()), null));
            assertEquals(UpdateErrorCode.INVALID_SIGNATURE, invalid.code());

            TestUpdateServer.TestFile secondFile = server.file("config/value.txt", "two", FilePolicy.ENFORCED);
            ReleaseManifest second = server.release(2, "release-2", secondFile);
            server.serve(second);
            engine.update(request(instance, playerHome, server.binding()), null);

            server.serve(first);
            UpdateException replay = assertThrows(UpdateException.class,
                    () -> engine.update(request(instance, playerHome, server.binding()), null));
            assertEquals(UpdateErrorCode.REPLAY_DETECTED, replay.code());
        }
    }

    @Test
    void resumesPartialObjectDownloads() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("resume-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            String content = "0123456789".repeat(20_000);
            TestUpdateServer.TestFile file = server.file("mods/large.jar", content, FilePolicy.ENFORCED);
            ReleaseManifest first = server.release(1, "release-1", file);
            server.serve(first);
            server.bundle(instance, first, false);

            Path partial = playerHome.resolve("staging/downloads/" + file.sha256() + ".part");
            Files.createDirectories(partial.getParent());
            Files.write(partial, java.util.Arrays.copyOf(file.bytes(), 12_345));

            UpdateResult result = new UpdateEngine().update(request(instance, playerHome, server.binding()), null);
            assertEquals("bytes=12345-", server.lastRange);
            assertEquals(file.bytes().length - 12_345L, result.downloadedBytes());
            assertEquals(file.sha256(), cn.dreamingfish.updater.protocol.CryptoSupport.sha256(
                    instance.resolve("mods/large.jar")));
        }
    }

    @Test
    void restoresThePreviousInstallationAfterAnInterruptedCommit() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("crash-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile oldA = server.file("config/a.txt", "old-a", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile oldB = server.file("config/b.txt", "old-b", FilePolicy.ENFORCED);
            ReleaseManifest first = server.release(1, "release-1", oldA, oldB);
            server.serve(first);
            server.bundle(instance, first, false);
            new UpdateEngine().update(request(instance, playerHome, server.binding()), null);

            TestUpdateServer.TestFile newA = server.file("config/a.txt", "new-a", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile newB = server.file("config/b.txt", "new-b", FilePolicy.ENFORCED);
            server.serve(server.release(2, "release-2", newA, newB));
            UpdateEngine crashing = new UpdateEngine(new TransactionFaultInjector() {
                @Override
                public void afterOperation(int operationIndex) {
                    if (operationIndex == 0) throw new SimulatedCrash();
                }
            });
            assertThrows(SimulatedCrash.class,
                    () -> crashing.update(request(instance, playerHome, server.binding()), null));

            server.serve(first);
            UpdateResult recovered = new UpdateEngine().update(request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.UP_TO_DATE, recovered.outcome());
            assertEquals("old-a", Files.readString(instance.resolve("config/a.txt")));
            assertEquals("old-b", Files.readString(instance.resolve("config/b.txt")));
            try (var stream = Files.list(playerHome.resolve("state/transactions"))) {
                assertEquals(0, stream.count());
            }
        }
    }

    @Test
    void recoversAtEveryPersistedTransactionBoundary() throws Exception {
        for (CrashPoint point : CrashPoint.values()) {
            try (TestUpdateServer server = new TestUpdateServer()) {
                Path instance = Files.createDirectories(
                        temporary.resolve("boundary-" + point.name().toLowerCase()));
                Path playerHome = instance.resolve("DreamingFishUpdater");
                TestUpdateServer.TestFile oldFile = server.file(
                        "config/value.txt", "old", FilePolicy.ENFORCED);
                ReleaseManifest first = server.release(1, "release-1", oldFile);
                server.serve(first);
                server.bundle(instance, first, false);
                new UpdateEngine().update(request(instance, playerHome, server.binding()), null);

                TestUpdateServer.TestFile newFile = server.file(
                        "config/value.txt", "new", FilePolicy.ENFORCED);
                ReleaseManifest second = server.release(2, "release-2", newFile);
                server.serve(second);
                UpdateEngine crashing = new UpdateEngine(new TransactionFaultInjector() {
                    @Override
                    public void afterPhase(TransactionPhase phase) {
                        if (point.phase == phase) throw new SimulatedCrash();
                    }

                    @Override
                    public void beforeCommit() {
                        if (point == CrashPoint.BEFORE_COMMIT) throw new SimulatedCrash();
                    }
                });
                assertThrows(SimulatedCrash.class,
                        () -> crashing.update(request(instance, playerHome, server.binding()), null));

                String immediatelyAfterCrash = Files.readString(instance.resolve("config/value.txt"));
                if (point == CrashPoint.BACKED_UP || point == CrashPoint.COMMITTING) {
                    assertEquals("old", immediatelyAfterCrash);
                } else {
                    assertEquals("new", immediatelyAfterCrash);
                }

                if (point == CrashPoint.COMMITTED) server.serve(second);
                else server.serve(first);
                UpdateResult recovered = new UpdateEngine().update(
                        request(instance, playerHome, server.binding()), null);
                assertEquals(UpdateOutcome.UP_TO_DATE, recovered.outcome());
                assertEquals(point == CrashPoint.COMMITTED ? "new" : "old",
                        Files.readString(instance.resolve("config/value.txt")));
                try (var transactions = Files.list(playerHome.resolve("state/transactions"))) {
                    assertEquals(0, transactions.count());
                }
            }
        }
    }

    @Test
    void blocksUpdatesWhileMinecraftHoldsTheInstanceMarker() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("running-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile oldFile = server.file("config/value.txt", "old", FilePolicy.ENFORCED);
            ReleaseManifest first = server.release(1, "release-1", oldFile);
            server.serve(first);
            server.bundle(instance, first, false);
            UpdateEngine engine = new UpdateEngine();
            engine.update(request(instance, playerHome, server.binding()), null);

            Path marker = instance.resolve(".dreamingfish-bootstrap/game.lock");
            try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                assertEquals(UpdateOutcome.UP_TO_DATE,
                        engine.update(request(instance, playerHome, server.binding()), null).outcome());

                TestUpdateServer.TestFile newFile = server.file("config/value.txt", "new", FilePolicy.ENFORCED);
                server.serve(server.release(2, "release-2", newFile));
                UpdateException blocked = assertThrows(UpdateException.class,
                        () -> engine.update(request(instance, playerHome, server.binding()), null));
                assertEquals(UpdateErrorCode.GAME_RUNNING, blocked.code());
                assertEquals("old", Files.readString(instance.resolve("config/value.txt")));
            }
        }
    }

    @Test
    void doesNotRecoverAnInterruptedCommitWhileMinecraftIsRunning() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("running-recovery-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile oldA = server.file("config/a.txt", "old-a", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile oldB = server.file("config/b.txt", "old-b", FilePolicy.ENFORCED);
            ReleaseManifest first = server.release(1, "release-1", oldA, oldB);
            server.serve(first);
            server.bundle(instance, first, false);
            new UpdateEngine().update(request(instance, playerHome, server.binding()), null);

            TestUpdateServer.TestFile newA = server.file("config/a.txt", "new-a", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile newB = server.file("config/b.txt", "new-b", FilePolicy.ENFORCED);
            server.serve(server.release(2, "release-2", newA, newB));
            UpdateEngine crashing = new UpdateEngine(new TransactionFaultInjector() {
                @Override
                public void afterOperation(int operationIndex) {
                    if (operationIndex == 0) throw new SimulatedCrash();
                }
            });
            assertThrows(SimulatedCrash.class,
                    () -> crashing.update(request(instance, playerHome, server.binding()), null));
            assertEquals("new-a", Files.readString(instance.resolve("config/a.txt")));

            Path marker = instance.resolve(".dreamingfish-bootstrap/game.lock");
            try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
                UpdateException blocked = assertThrows(UpdateException.class,
                        () -> new UpdateEngine().update(request(instance, playerHome, server.binding()), null));
                assertEquals(UpdateErrorCode.GAME_RUNNING, blocked.code());
                assertEquals("new-a", Files.readString(instance.resolve("config/a.txt")));
            }

            server.serve(first);
            UpdateResult recovered = new UpdateEngine().update(
                    request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.UP_TO_DATE, recovered.outcome());
            assertEquals("old-a", Files.readString(instance.resolve("config/a.txt")));
            assertEquals("old-b", Files.readString(instance.resolve("config/b.txt")));
        }
    }

    @Test
    void protectsBootstrapAndPlayerUpdaterDirectories() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("protected-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile bootstrap = server.file(
                    ".dreamingfish-bootstrap/bootstrap-agent.jar", "bad", FilePolicy.ENFORCED);
            ReleaseManifest release = server.release(1, "release-1", bootstrap);
            server.serve(release);
            server.bundle(instance, release, false);
            UpdateException rejected = assertThrows(UpdateException.class,
                    () -> new UpdateEngine().update(request(instance, playerHome, server.binding()), null));
            assertEquals(UpdateErrorCode.PATH_UNSAFE, rejected.code());
        }
    }

    @Test
    void directlyConvergesAnyOldOfficialBundleToTheLatestRelease() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            List<TestUpdateServer.TestFile> original = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                original.add(server.file("mods/original-" + index + ".jar",
                        "original-" + index, FilePolicy.ENFORCED));
            }
            List<TestUpdateServer.TestFile> additions = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                additions.add(server.file("mods/addition-" + index + ".jar",
                        "addition-" + index, FilePolicy.ENFORCED));
            }
            List<TestUpdateServer.TestFile> releaseTwoFiles = new ArrayList<>(original);
            releaseTwoFiles.addAll(additions);
            List<TestUpdateServer.TestFile> releaseThreeFiles = new ArrayList<>(
                    original.subList(8, 10));
            releaseThreeFiles.addAll(additions);

            ReleaseManifest releaseOne = server.release(1, "release-1.1",
                    original.toArray(TestUpdateServer.TestFile[]::new));
            ReleaseManifest releaseTwo = server.release(2, "release-1.2",
                    releaseTwoFiles.toArray(TestUpdateServer.TestFile[]::new));
            ReleaseManifest releaseThree = server.release(3, "release-1.3",
                    releaseThreeFiles.toArray(TestUpdateServer.TestFile[]::new));
            server.serve(releaseThree);

            for (ReleaseManifest baseline : List.of(releaseOne, releaseTwo)) {
                Path instance = Files.createDirectories(
                        temporary.resolve("direct-" + baseline.releaseId()));
                Path playerHome = instance.resolve("DreamingFishUpdater");
                server.bundle(instance, baseline, true);
                Path custom = instance.resolve("mods/player-choice.jar");
                Files.writeString(custom, "player-choice");

                UpdateResult result = new UpdateEngine().update(
                        request(instance, playerHome, server.binding()), null);

                assertEquals(UpdateOutcome.UPDATED, result.outcome());
                assertEquals(8, result.deletedFiles());
                assertEquals(baseline == releaseOne ? 5 : 0, result.installedFiles());
                for (int index = 0; index < 8; index++) {
                    assertFalse(Files.exists(instance.resolve("mods/original-" + index + ".jar")));
                }
                for (TestUpdateServer.TestFile expected : releaseThreeFiles) {
                    assertTrue(Files.isRegularFile(instance.resolve(expected.path())));
                }
                assertEquals("player-choice", Files.readString(custom));
                assertEquals(Set.of(Path.of("mods/player-choice.jar")),
                        Set.copyOf(result.unmanagedMods()));
                assertTrue(Files.isRegularFile(
                        playerHome.resolve("state/verified-installation.json")));
            }
        }
    }

    @Test
    void forceSyncsOnlySelectedDirectoriesAndKeepsEveryArchive() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("forced-directory"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile oldMod = server.file(
                    "mods/old-official.jar", "old", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile config = server.file(
                    "config/managed.toml", "managed=true", FilePolicy.ENFORCED);
            ReleaseManifest baseline = server.release(1, "release-1", oldMod, config);
            server.bundle(instance, baseline, true);
            Files.writeString(instance.resolve("mods/player-extra.jar"), "extra");
            Files.createDirectories(instance.resolve("mods/notes"));
            Files.writeString(instance.resolve("mods/notes/readme.txt"), "not a jar");
            Files.writeString(instance.resolve("config/player-extra.toml"), "player=true");

            TestUpdateServer.TestFile newMod = server.file(
                    "mods/new-official.jar", "new", FilePolicy.ENFORCED);
            ReleaseManifest target = server.release(
                    2, "release-2", List.of("mods"), config, newMod);
            server.serve(target);

            UpdateResult first = new UpdateEngine().update(
                    forcedRequest(instance, playerHome, server.binding()), null);
            assertEquals(3, first.archivedFiles().size());
            assertTrue(first.archiveDirectory().startsWith(
                    playerHome.resolve("backups/forced-sync")));
            assertEquals("old", Files.readString(
                    first.archiveDirectory().resolve("mods/old-official.jar")));
            assertEquals("extra", Files.readString(
                    first.archiveDirectory().resolve("mods/player-extra.jar")));
            assertEquals("not a jar", Files.readString(
                    first.archiveDirectory().resolve("mods/notes/readme.txt")));
            assertTrue(Files.readString(first.archiveDirectory().resolve("archived-files.txt"))
                    .contains("Remote management forced directories: mods"));
            assertTrue(Files.isRegularFile(instance.resolve("mods/new-official.jar")));
            assertFalse(Files.exists(instance.resolve("mods/old-official.jar")));
            assertFalse(Files.exists(instance.resolve("mods/player-extra.jar")));
            assertTrue(Files.isRegularFile(instance.resolve("config/player-extra.toml")));

            Files.writeString(instance.resolve("mods/player-extra.jar"), "extra-again");
            UpdateResult second = new UpdateEngine().update(
                    forcedRequest(instance, playerHome, server.binding()), null);
            assertEquals(1, second.archivedFiles().size());
            assertFalse(first.archiveDirectory().equals(second.archiveDirectory()));
            assertEquals("extra", Files.readString(
                    first.archiveDirectory().resolve("mods/player-extra.jar")));
            assertEquals("extra-again", Files.readString(
                    second.archiveDirectory().resolve("mods/player-extra.jar")));
        }
    }

    @Test
    void forceSyncsAnExplicitlyEmptyDirectoryAndArchivesEveryFile() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("forced-empty-directory"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile oldOfficial = server.file(
                    "mods/old-official.jar", "official", FilePolicy.ENFORCED);
            ReleaseManifest baseline = server.release(1, "release-1", oldOfficial);
            server.bundle(instance, baseline, true);
            Files.writeString(instance.resolve("mods/player-extra.jar"), "extra");
            Files.createDirectories(instance.resolve("mods/notes"));
            Files.writeString(instance.resolve("mods/notes/readme.txt"), "notes");

            ReleaseManifest emptyMods = server.release(
                    2, "release-2", List.of("mods"));
            server.serve(emptyMods);

            UpdateResult result = new UpdateEngine().update(
                    forcedRequest(instance, playerHome, server.binding()), null);

            assertEquals(UpdateOutcome.UPDATED, result.outcome());
            assertEquals(3, result.archivedFiles().size());
            assertEquals("official", Files.readString(
                    result.archiveDirectory().resolve("mods/old-official.jar")));
            assertEquals("extra", Files.readString(
                    result.archiveDirectory().resolve("mods/player-extra.jar")));
            assertEquals("notes", Files.readString(
                    result.archiveDirectory().resolve("mods/notes/readme.txt")));
            try (var files = Files.walk(instance.resolve("mods"))) {
                assertEquals(0, files.filter(Files::isRegularFile).count());
            }
        }
    }

    @Test
    void restoresForcedSyncFilesAfterAnInterruptedArchive() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("archive-crash"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            ReleaseManifest baseline = server.release(1, "release-1");
            server.bundle(instance, baseline, true);
            Path custom = instance.resolve("mods/custom.jar");
            Files.createDirectories(custom.getParent());
            Files.writeString(custom, "custom");
            ReleaseManifest forced = server.release(2, "release-2", List.of("mods"));
            server.serve(forced);

            UpdateEngine crashing = new UpdateEngine(new TransactionFaultInjector() {
                @Override
                public void afterOperation(int operationIndex) {
                    throw new SimulatedCrash();
                }
            });
            assertThrows(SimulatedCrash.class,
                    () -> crashing.update(forcedRequest(instance, playerHome, server.binding()), null));

            server.serve(baseline);
            UpdateResult recovered = new UpdateEngine().update(
                    request(instance, playerHome, server.binding()), null);
            assertEquals(UpdateOutcome.UP_TO_DATE, recovered.outcome());
            assertEquals("custom", Files.readString(custom));
            try (var archives = Files.list(playerHome.resolve("backups/forced-sync"))) {
                assertEquals(0, archives.count());
            }
        }
    }

    @Test
    void refusesMissingIncompleteOrInvalidBundledBaselines() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            ReleaseManifest release = server.release(1, "release-1");
            server.serve(release);

            Path missing = Files.createDirectories(temporary.resolve("missing-baseline"));
            UpdateException missingFailure = assertThrows(UpdateException.class,
                    () -> new UpdateEngine().update(
                            request(missing, missing.resolve("DreamingFishUpdater"), server.binding()), null));
            assertEquals(UpdateErrorCode.LOCAL_STATE_INVALID, missingFailure.code());

            Path manifestOnly = Files.createDirectories(temporary.resolve("manifest-only-baseline"));
            server.bundle(manifestOnly, release, false);
            Files.delete(manifestOnly.resolve(
                    ".dreamingfish-bootstrap/bundled-release/manifest.sig"));
            UpdateException manifestOnlyFailure = assertThrows(UpdateException.class,
                    () -> new UpdateEngine().update(request(
                            manifestOnly, manifestOnly.resolve("DreamingFishUpdater"), server.binding()), null));
            assertEquals(UpdateErrorCode.LOCAL_STATE_INVALID, manifestOnlyFailure.code());

            Path signatureOnly = Files.createDirectories(temporary.resolve("signature-only-baseline"));
            server.bundle(signatureOnly, release, false);
            Files.delete(signatureOnly.resolve(
                    ".dreamingfish-bootstrap/bundled-release/manifest.json"));
            UpdateException signatureOnlyFailure = assertThrows(UpdateException.class,
                    () -> new UpdateEngine().update(request(
                            signatureOnly, signatureOnly.resolve("DreamingFishUpdater"), server.binding()), null));
            assertEquals(UpdateErrorCode.LOCAL_STATE_INVALID, signatureOnlyFailure.code());

            Path invalid = Files.createDirectories(temporary.resolve("invalid-baseline"));
            server.bundle(invalid, release, true);
            Files.writeString(invalid.resolve(
                    ".dreamingfish-bootstrap/bundled-release/manifest.sig"), "invalid");
            UpdateException invalidFailure = assertThrows(UpdateException.class,
                    () -> new UpdateEngine().update(
                            request(invalid, invalid.resolve("DreamingFishUpdater"), server.binding()), null));
            assertEquals(UpdateErrorCode.INVALID_SIGNATURE, invalidFailure.code());
        }
    }

    @Test
    void refusesAValidlySignedBundledBaselineFromAnotherProject() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            ReleaseManifest release = server.release(1, "release-1");
            ReleaseManifest otherProject = new ReleaseManifest(
                    release.schemaVersion(), "another-project", release.releaseId(),
                    release.sequence(), release.createdAt(), release.displayVersion(),
                    release.minimumPlayerVersion(), release.changelog(),
                    release.requiredCapabilities(), release.forcedSyncDirectories(),
                    release.branding(), release.files());
            server.serve(release);
            Path instance = Files.createDirectories(temporary.resolve("other-project-baseline"));
            server.bundle(instance, otherProject, false);

            UpdateException failure = assertThrows(UpdateException.class,
                    () -> new UpdateEngine().update(
                            request(instance, instance.resolve("DreamingFishUpdater"), server.binding()), null));

            assertEquals(UpdateErrorCode.LOCAL_STATE_INVALID, failure.code());
            assertFalse(Files.exists(instance.resolve(
                    "DreamingFishUpdater/state/verified-installation.json")));
        }
    }

    @Test
    void leavesTheInstanceUntouchedWhenTheArchiveRootIsUnavailable() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("archive-unavailable"));
            Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
            ReleaseManifest baseline = server.release(1, "release-1");
            server.bundle(instance, baseline, true);
            Path custom = instance.resolve("mods/custom.jar");
            Files.createDirectories(custom.getParent());
            Files.writeString(custom, "custom");
            Path badArchiveRoot = playerHome.resolve("backups/forced-sync");
            Files.createDirectories(badArchiveRoot.getParent());
            Files.writeString(badArchiveRoot, "not-a-directory");
            server.serve(server.release(2, "release-2", List.of("mods")));

            UpdateException failure = assertThrows(UpdateException.class,
                    () -> new UpdateEngine().update(
                            forcedRequest(instance, playerHome, server.binding()), null));
            assertEquals(UpdateErrorCode.LOCAL_STATE_INVALID, failure.code());
            assertEquals("custom", Files.readString(custom));
        }
    }

    @Test
    void locallyDisabledManagedModStaysAbsentOnlineAndOfflineWhileOtherFilesAreRepaired()
            throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("local-disabled"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile mod = server.mod(
                    "mods/legacy-renderer.jar", "renderer", FilePolicy.ENFORCED,
                    "legacy_renderer", "Legacy Renderer");
            TestUpdateServer.TestFile config = server.file(
                    "config/required.toml", "correct", FilePolicy.ENFORCED);
            ReleaseManifest release = server.release(1, "release-1", mod, config);
            server.bundle(instance, release, true);
            server.serve(release);

            Files.delete(instance.resolve("mods/legacy-renderer.jar"));
            Files.writeString(instance.resolve("config/required.toml"), "damaged");
            LocalFileOverrides overrides = new LocalFileOverrides(
                    Set.of("legacy_renderer"), Set.of("mods/legacy-renderer.jar"));

            UpdateResult repaired = new UpdateEngine().update(
                    request(instance, playerHome, server.binding(), overrides), null);
            assertEquals(UpdateOutcome.UPDATED, repaired.outcome());
            assertFalse(Files.exists(instance.resolve("mods/legacy-renderer.jar")));
            assertEquals("correct", Files.readString(instance.resolve("config/required.toml")));
            assertEquals(List.of(Path.of("config/required.toml")), repaired.installedPaths());

            server.unavailable = true;
            UpdateResult offline = new UpdateEngine().update(
                    request(instance, playerHome, server.binding(), overrides), null);
            assertEquals(UpdateOutcome.OFFLINE_ALLOWED, offline.outcome());
            assertFalse(Files.exists(instance.resolve("mods/legacy-renderer.jar")));
        }
    }

    @Test
    void locallyExcludedFileAndDirectoryStayUntouchedOnlineAndOffline() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("local-file-exclusions"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile personal = server.file(
                    "config/personal.toml", "remote", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile nested = server.file(
                    "config/visual/client.toml", "remote-visual", FilePolicy.ENFORCED);
            TestUpdateServer.TestFile required = server.file(
                    "defaultconfigs/required.toml", "correct", FilePolicy.ENFORCED);
            ReleaseManifest release = server.release(
                    1, "release-1", personal, nested, required);
            server.bundle(instance, release, true);
            server.serve(release);

            Files.writeString(instance.resolve("config/personal.toml"), "player-choice");
            Files.delete(instance.resolve("config/visual/client.toml"));
            Files.writeString(instance.resolve("defaultconfigs/required.toml"), "damaged");
            LocalFileOverrides overrides = new LocalFileOverrides(
                    Set.of(), Set.of("config/personal.toml"), Set.of("config/visual"));

            UpdateResult repaired = new UpdateEngine().update(
                    request(instance, playerHome, server.binding(), overrides), null);
            assertEquals("player-choice",
                    Files.readString(instance.resolve("config/personal.toml")));
            assertFalse(Files.exists(instance.resolve("config/visual/client.toml")));
            assertEquals("correct",
                    Files.readString(instance.resolve("defaultconfigs/required.toml")));
            assertEquals(List.of(Path.of("defaultconfigs/required.toml")),
                    repaired.installedPaths());

            server.unavailable = true;
            assertEquals(UpdateOutcome.OFFLINE_ALLOWED, new UpdateEngine().update(
                    request(instance, playerHome, server.binding(), overrides), null).outcome());
        }
    }

    @Test
    void forcedSyncOverridesLocalExemptionsAndArchivesAllExtraFiles()
            throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("forced-local-disabled"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile managed = server.mod(
                    "mods/renderer.jar", "renderer", FilePolicy.ENFORCED,
                    "renderer", "Renderer");
            ReleaseManifest release = server.release(
                    1, "release-1", List.of("mods"), managed);
            server.bundle(instance, release, true);
            server.serve(release);
            Files.delete(instance.resolve("mods/renderer.jar"));
            Files.writeString(instance.resolve("mods/player-disabled.jar"), "personal-disabled");
            Files.writeString(instance.resolve("mods/remove-me.jar"), "remove-me");

            LocalFileOverrides overrides = new LocalFileOverrides(
                    Set.of("renderer"),
                    Set.of("mods/renderer.jar", "mods/player-disabled.jar"),
                    Set.of("mods"));
            UpdateResult result = new UpdateEngine().update(
                    forcedRequest(instance, playerHome, server.binding(), overrides), null);

            assertEquals("renderer", Files.readString(instance.resolve("mods/renderer.jar")));
            assertFalse(Files.exists(instance.resolve("mods/player-disabled.jar")));
            assertFalse(Files.exists(instance.resolve("mods/remove-me.jar")));
            assertEquals(List.of(
                    Path.of("mods/player-disabled.jar"),
                    Path.of("mods/remove-me.jar")), result.archivedFiles());
        }
    }

    @Test
    void componentIdKeepsARenamedModDisabledUntilThePlayerRestoresIt() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("renamed-disabled"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            TestUpdateServer.TestFile oldMod = server.mod(
                    "mods/renderer-1.jar", "old", FilePolicy.ENFORCED,
                    "renderer", "Renderer");
            ReleaseManifest first = server.release(1, "release-1", oldMod);
            server.bundle(instance, first, true);
            Files.delete(instance.resolve("mods/renderer-1.jar"));

            TestUpdateServer.TestFile newMod = server.mod(
                    "mods/renderer-2.jar", "new", FilePolicy.ENFORCED,
                    "renderer", "Renderer");
            ReleaseManifest second = server.release(2, "release-2", newMod);
            server.serve(second);
            LocalFileOverrides disabled = new LocalFileOverrides(
                    Set.of("renderer"), Set.of("mods/renderer-1.jar"));

            UpdateResult heldBack = new UpdateEngine().update(
                    request(instance, playerHome, server.binding(), disabled), null);
            assertEquals(UpdateOutcome.UPDATED, heldBack.outcome());
            assertFalse(Files.exists(instance.resolve("mods/renderer-1.jar")));
            assertFalse(Files.exists(instance.resolve("mods/renderer-2.jar")));
            assertTrue(heldBack.installedPaths().isEmpty());

            UpdateResult restored = new UpdateEngine().update(
                    request(instance, playerHome, server.binding(), LocalFileOverrides.NONE), null);
            assertEquals(UpdateOutcome.UPDATED, restored.outcome());
            assertEquals("new", Files.readString(instance.resolve("mods/renderer-2.jar")));
            assertEquals(List.of(Path.of("mods/renderer-2.jar")), restored.installedPaths());
        }
    }

    private UpdateRequest request(Path instance, Path playerHome, ProjectBinding binding) {
        return UpdateRequest.defaults(instance, playerHome, binding, "0.1.0", Set.of());
    }

    private UpdateRequest request(Path instance, Path playerHome, ProjectBinding binding,
                                  LocalFileOverrides overrides) {
        return new UpdateRequest(instance, playerHome, binding, "0.1.0", Set.of(),
                null, null, null, CancellationToken.NEVER, overrides);
    }

    private UpdateRequest forcedRequest(Path instance, Path playerHome, ProjectBinding binding) {
        return UpdateRequest.defaults(instance, playerHome, binding, "0.1.4",
                Set.of(cn.dreamingfish.updater.protocol.ProtocolConstants
                        .CAPABILITY_FORCED_DIRECTORY_SYNC));
    }

    private UpdateRequest forcedRequest(Path instance, Path playerHome, ProjectBinding binding,
                                        LocalFileOverrides overrides) {
        return new UpdateRequest(instance, playerHome, binding, "0.1.4",
                Set.of(cn.dreamingfish.updater.protocol.ProtocolConstants
                        .CAPABILITY_FORCED_DIRECTORY_SYNC),
                null, null, null, CancellationToken.NEVER, overrides);
    }

    private static final class SimulatedCrash extends Error {
    }

    private enum CrashPoint {
        BACKED_UP(TransactionPhase.BACKED_UP),
        COMMITTING(TransactionPhase.COMMITTING),
        BEFORE_COMMIT(null),
        COMMITTED(TransactionPhase.COMMITTED);

        private final TransactionPhase phase;

        CrashPoint(TransactionPhase phase) {
            this.phase = phase;
        }
    }
}
