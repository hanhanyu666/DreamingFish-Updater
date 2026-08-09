package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PlayerPresentation;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerPresentationClientTest {
    @TempDir
    Path temporary;

    @Test
    void fetchesSignedPresentationUsesConditionalCacheAndRejectsTampering() throws Exception {
        KeyPair keys = CryptoSupport.generateEd25519KeyPair();
        PlayerPresentation presentation = new PlayerPresentation(
                ProtocolConstants.PLAYER_PRESENTATION_SCHEMA_VERSION,
                "demo",
                new Branding("实时标题", "实时副标题", "mc.example.test",
                        null, "#123456", "#654321"));
        byte[] payload = new JsonCodec().writePretty(presentation);
        String signature = Base64.getEncoder().encodeToString(
                CryptoSupport.sign(payload, keys.getPrivate()));
        String etag = '"' + CryptoSupport.sha256(payload) + '"';
        AtomicInteger notModified = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/projects/demo/presentation", exchange -> {
            try {
                if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    notModified.incrementAndGet();
                    exchange.sendResponseHeaders(304, -1);
                    return;
                }
                exchange.getResponseHeaders().set(
                        ProtocolConstants.SIGNATURE_HEADER, signature);
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Path playerHome = temporary.resolve("player-home");
            ProjectBinding binding = new ProjectBinding(
                    ProtocolConstants.BINDING_SCHEMA_VERSION,
                    "demo",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    CryptoSupport.encodePublicKey(keys.getPublic()),
                    "DreamingFishUpdater", null, Branding.empty());
            UpdateRequest request = UpdateRequest.defaults(
                    temporary.resolve("instance"), playerHome, binding,
                    "0.1.34", Set.of());
            PlayerPresentationClient client = new PlayerPresentationClient();

            assertEquals("实时标题", client.fetch(request).productName());
            assertEquals("实时标题", client.fetch(request).productName());
            assertEquals(1, notModified.get());
            assertEquals("实时标题",
                    client.loadCached(binding, playerHome).productName());

            Files.writeString(playerHome.resolve("state/player-presentation.json"),
                    "{\"projectId\":\"attacker\"}", StandardCharsets.UTF_8);
            assertNull(client.loadCached(binding, playerHome));
        } finally {
            server.stop(0);
        }
    }
}
