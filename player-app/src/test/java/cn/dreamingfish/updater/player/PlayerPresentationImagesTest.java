package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.CancellationToken;
import cn.dreamingfish.updater.protocol.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPresentationImagesTest {
    @TempDir Path temporary;

    private ProjectBinding binding(HttpServer server) {
        return new ProjectBinding(1, "demo", "http://127.0.0.1:" + server.getAddress().getPort() + "/updates/", "", "DreamingFishUpdater", null, Branding.empty());
    }

    @Test void verifiesSignedSizesAndHashesCachesImagesAndLeavesGoodFilesOnDownloadFailure() throws Exception {
        byte[] bytes = "signed image bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var image = new PlayerPresentationImage("scene.webp", CryptoSupport.sha256(bytes), bytes.length);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/updates/v1/objects/sha256/", exchange -> {
            requests.incrementAndGet();
            byte[] response = exchange.getRequestURI().getPath().endsWith(image.sha256()) ? bytes : "corrupt".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new PlayerPresentationImages();
            assertTrue(client.sync(binding(server), temporary, List.of(image), CancellationToken.NEVER));
            Path target = temporary.resolve("DreamingFishUpdater/presentation-images/scene.webp");
            assertArrayEquals(bytes, Files.readAllBytes(target));
            assertFalse(client.sync(binding(server), temporary, List.of(image), CancellationToken.NEVER));
            assertEquals(1, requests.get());
            var bad = new PlayerPresentationImage("scene.webp", "a".repeat(64), 7);
            assertFalse(client.sync(binding(server), temporary, List.of(bad), CancellationToken.NEVER));
            assertArrayEquals(bytes, Files.readAllBytes(target));
            assertEquals(2, requests.get());
            try (var files = Files.list(target.getParent())) { assertEquals(List.of(target), files.toList()); }
        } finally { server.stop(0); }
    }

    @Test void missingResourcesUnsafePathsAndCancellationRemainOptional() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            var client = new PlayerPresentationImages();
            assertFalse(client.sync(binding(server), temporary, List.of(new PlayerPresentationImage("missing.png", "a".repeat(64), 42)), CancellationToken.NEVER));
            assertFalse(client.sync(binding(server), temporary, List.of(new PlayerPresentationImage("../escape.png", "a".repeat(64), 42)), CancellationToken.NEVER));
            assertFalse(client.sync(binding(server), temporary, List.of(new PlayerPresentationImage("cancelled.png", "a".repeat(64), 42)), () -> true));
            assertFalse(Files.exists(temporary.resolve("DreamingFishUpdater/presentation-images/cancelled.png")));
            assertFalse(Files.exists(temporary.resolve("DreamingFishUpdater/escape.png")));
        } finally { server.stop(0); }
    }
}
