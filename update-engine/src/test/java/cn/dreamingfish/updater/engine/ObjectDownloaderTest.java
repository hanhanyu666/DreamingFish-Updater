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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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

    @Test
    void cancelsStalledWorkersBeforeReturningAndCanImmediatelyRetry() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        byte[] first = "first stalled content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "second stalled content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, byte[]> content = Map.of(
                cn.dreamingfish.updater.protocol.CryptoSupport.sha256(first), first,
                cn.dreamingfish.updater.protocol.CryptoSupport.sha256(second), second);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/v1/objects/sha256/", exchange -> {
            byte[] bytes = content.get(exchange.getRequestURI().getPath().substring("/v1/objects/sha256/".length()));
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                started.countDown();
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { }
            finally { exchange.close(); }
        });
        server.start();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            Path instance = Files.createDirectories(temporary.resolve("stalled-instance"));
            Path home = instance.resolve("DreamingFishUpdater");
            EnginePaths paths = EnginePaths.of(instance, home);
            paths.createDirectories();
            var binding = new cn.dreamingfish.updater.protocol.ProjectBinding(1, "demo",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "unused-transport-test-key",
                    "DreamingFishUpdater", null, cn.dreamingfish.updater.protocol.Branding.empty());
            AtomicBoolean cancelled = new AtomicBoolean();
            UpdateRequest request = new UpdateRequest(instance, home, binding, "0.1.40", Set.of(),
                    null, java.time.Duration.ofSeconds(5), null, cancelled::get);
            Map<String, Long> objects = new LinkedHashMap<>();
            content.forEach((hash, bytes) -> objects.put(hash, (long) bytes.length));
            var task = executor.submit(() -> new ObjectDownloader().download(request, paths, objects, ProgressListener.NONE));
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            cancelled.set(true);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(UpdateErrorCode.CANCELLED, ((UpdateException) error.getCause()).code());
            // Returning from download must mean all its writers have exited.
            release.countDown();
            UpdateRequest retry = new UpdateRequest(instance, home, binding, "0.1.40", Set.of(),
                    null, java.time.Duration.ofSeconds(5), request.httpClient(), CancellationToken.NEVER);
            new ObjectDownloader().download(retry, paths, objects, ProgressListener.NONE);
            for (var object : content.entrySet()) {
                assertArrayEquals(object.getValue(), Files.readAllBytes(paths.cacheObject(object.getKey())));
            }
        } finally {
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
            assertTrue(handlers.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
