package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.CancellationToken;
import cn.dreamingfish.updater.protocol.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlayerBackgroundImageTest {
    @TempDir Path root;

    @Test void switchesPublishedBackgroundCachesOfflineAndClearsWithoutResurrectingBundledImage() throws Exception {
        byte[] old = "old background".getBytes(), next = "new background".getBytes();
        String oldHash = CryptoSupport.sha256(old), nextHash = CryptoSupport.sha256(next);
        Path bundled = root.resolve("project-cover"); Files.write(bundled, old);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/updates/v1/objects/sha256/", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, next.length);
            try (var out = exchange.getResponseBody()) { out.write(next); }
        });
        server.start();
        var binding = new ProjectBinding(1, "demo", "http://127.0.0.1:" + server.getAddress().getPort() + "/updates/", "", "DreamingFishUpdater", null, Branding.empty());
        try {
            var cache = new PlayerBackgroundImage();
            assertArrayEquals(old, Files.readAllBytes(cache.sync(binding, root, oldHash, bundled, CancellationToken.NEVER)));
            assertEquals(0, requests.get(), "Matching bundled artwork needs no network");
            Path updated = cache.sync(binding, root, nextHash, bundled, CancellationToken.NEVER);
            assertArrayEquals(next, Files.readAllBytes(updated));
            assertArrayEquals(old, Files.readAllBytes(bundled), "Original deployment remains untouched");
            assertEquals(1, requests.get());
            assertThrows(java.io.IOException.class, () -> cache.sync(binding, root, "a".repeat(64), bundled, CancellationToken.NEVER));
            assertNull(cache.cached(root, "a".repeat(64)));
            assertArrayEquals(next, Files.readAllBytes(updated), "Bad responses cannot overwrite the verified image");
            assertNull(cache.sync(binding, root, null, bundled, CancellationToken.NEVER), "Removal does not fall back to bundled artwork");
            server.stop(0);
            assertEquals(updated, cache.sync(binding, root, nextHash, bundled, CancellationToken.NEVER));
            assertThrows(java.io.IOException.class, () -> cache.sync(binding, root, "../escape", bundled, CancellationToken.NEVER));
            try (var files = Files.list(updated.getParent())) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp"))); }
        } finally { server.stop(0); }
    }

    @Test void cancelledRequestsDoNotPublishAnImage() throws Exception {
        var cache = new PlayerBackgroundImage();
        assertThrows(RuntimeException.class, () -> cache.sync(null, root, "a".repeat(64), null, () -> true));
        assertNull(cache.cached(root, "a".repeat(64)));
    }
}
