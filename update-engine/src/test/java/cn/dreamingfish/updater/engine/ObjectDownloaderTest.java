package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.FilePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectDownloaderTest {
    @TempDir
    Path temporary;

    @Test
    void cancelsAParallelBatchWithoutHanging() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("cancel-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            EnginePaths paths = EnginePaths.of(instance, playerHome);
            paths.createDirectories();
            Map<String, Long> objects = new LinkedHashMap<>();
            for (int index = 0; index < 8; index++) {
                TestUpdateServer.TestFile file = server.file("mods/cancel-" + index + ".jar",
                        ("object-" + index).repeat(40_000), FilePolicy.ENFORCED);
                objects.put(file.sha256(), (long) file.bytes().length);
            }
            server.objectDelayMillis = 50;
            AtomicBoolean cancelled = new AtomicBoolean();
            UpdateRequest request = new UpdateRequest(instance, playerHome, server.binding(),
                    "0.1.20", Set.of(), null, null, null, cancelled::get);

            UpdateException error = assertThrows(UpdateException.class,
                    () -> new ObjectDownloader().download(request, paths, objects, event -> {
                        if (event.completedBytes() > 0) cancelled.set(true);
                    }));

            assertEquals(UpdateErrorCode.CANCELLED, error.code());
            assertTrue(server.maximumConcurrentObjectRequests.get() <= 4);
        }
    }

    @Test
    void reportsAHashFailureFromOneWorkerAndStopsTheBatch() throws Exception {
        try (TestUpdateServer server = new TestUpdateServer()) {
            Path instance = Files.createDirectories(temporary.resolve("hash-instance"));
            Path playerHome = instance.resolve("DreamingFishUpdater");
            EnginePaths paths = EnginePaths.of(instance, playerHome);
            paths.createDirectories();
            Map<String, Long> objects = new LinkedHashMap<>();
            for (int index = 0; index < 6; index++) {
                TestUpdateServer.TestFile file = server.file("mods/hash-" + index + ".jar",
                        "trusted-content-" + index, FilePolicy.ENFORCED);
                objects.put(file.sha256(), (long) file.bytes().length);
                if (index == 2) server.tamperObject(file.sha256(), "untrusted-content");
            }

            UpdateRequest request = UpdateRequest.defaults(
                    instance, playerHome, server.binding(), "0.1.20", Set.of());
            UpdateException error = assertThrows(UpdateException.class,
                    () -> new ObjectDownloader().download(
                            request, paths, objects, ProgressListener.NONE));

            assertEquals(UpdateErrorCode.HASH_MISMATCH, error.code());
        }
    }
}
