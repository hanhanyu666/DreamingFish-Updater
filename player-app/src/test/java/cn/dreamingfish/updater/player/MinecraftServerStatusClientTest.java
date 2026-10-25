package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftServerStatusClientTest {
    private JsonNode component(String json) { return new JsonCodec().read(json.getBytes(StandardCharsets.UTF_8), JsonNode.class); }

    @Test void convertsPlainTextExtraInheritedStylesAndHexColors() {
        assertEquals("欢迎§a回来", MinecraftServerStatusClient.legacyMotd(component("\"欢迎§a回来\"")));
        assertEquals("§a欢迎§r§b玩家§r§a回来", MinecraftServerStatusClient.legacyMotd(component("""
                {"text":"欢迎","color":"green","extra":[{"text":"玩家","color":"aqua"},{"text":"回来"}]}
                """)));
        assertEquals("§x§1§2§A§b§0§f§l鲸歌", MinecraftServerStatusClient.legacyMotd(component("""
                {"text":"鲸歌","color":"#12Ab0f","bold":true}
                """)));
        assertEquals("§l粗体§r普通", MinecraftServerStatusClient.legacyMotd(component("""
                [{"text":"粗体","bold":true},{"text":"普通"}]
                """)));
    }

    @Test void parsesIpv4Ipv6AndExplicitPorts() {
        assertEquals(new MinecraftServerStatusClient.Address("example.com", 25565, false), MinecraftServerStatusClient.parseAddress("example.com"));
        assertEquals(new MinecraftServerStatusClient.Address("::1", 25566, true), MinecraftServerStatusClient.parseAddress("[::1]:25566"));
        assertEquals(new MinecraftServerStatusClient.Address("::1", 25565, false), MinecraftServerStatusClient.parseAddress("::1"));
        for (String value : new String[]{"example.com:", "example.com:0", "example.com:65536", "http://example.com", "[::1", "bad host"}) {
            assertThrows(RuntimeException.class, () -> MinecraftServerStatusClient.parseAddress(value), value);
        }
    }

    @Test void queriesARealSocketAndUsesSrvOnlyWithoutExplicitPort() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> handshake = new CompletableFuture<>();
            respondOnce(server, handshake);
            AtomicInteger lookups = new AtomicInteger();
            var client = new MinecraftServerStatusClient(Duration.ofSeconds(2), host -> {
                lookups.incrementAndGet();
                assertEquals("example.test", host);
                return new MinecraftServerStatusClient.Srv("127.0.0.1", server.getLocalPort(), 0, 0);
            });
            ServerStatus result = client.query("example.test");
            assertEquals("ONLINE", result.state());
            assertEquals(37, result.onlinePlayers());
            assertEquals(120, result.maxPlayers());
            assertEquals("1.20.1", result.version());
            assertEquals("§b鲸歌", result.motd());
            assertTrue(result.latencyMs() >= 0);
            assertEquals("example.test", handshake.get(2, TimeUnit.SECONDS));
            assertEquals(1, lookups.get());
            CompletableFuture<String> second = new CompletableFuture<>();
            respondOnce(server, second);
            assertEquals("ONLINE", client.query("127.0.0.1:" + server.getLocalPort()).state());
            assertEquals(1, lookups.get());
            assertEquals("127.0.0.1", second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void totalDeadlineCoversDnsAndStalledServers() throws Exception {
        var client = new MinecraftServerStatusClient(Duration.ofMillis(100), host -> { Thread.sleep(10_000); return null; });
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals("OFFLINE", client.query("slow.example.test").state()));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var accepted = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) { accepted.complete(null); Thread.sleep(1000); }
                catch (Exception ignored) { }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals("OFFLINE", client.query("127.0.0.1:" + server.getLocalPort()).state()));
            accepted.get(1, TimeUnit.SECONDS);
        }
    }

    @Test void malformedPacketsAndUnreachableHostsOnlyBecomeOffline() throws Exception {
        assertEquals("OFFLINE", new MinecraftServerStatusClient().query("bad address").state());
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) { socket.getOutputStream().write(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f}); }
                catch (Exception ignored) { }
            });
            assertEquals("OFFLINE", new MinecraftServerStatusClient().query("127.0.0.1:" + server.getLocalPort()).state());
        }
    }

    private void respondOnce(ServerSocket server, CompletableFuture<String> hostname) {
        Thread.ofVirtual().start(() -> {
            try (var socket = server.accept()) {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                int packetSize = MinecraftServerStatusClient.readVarInt(input);
                DataInputStream handshake = new DataInputStream(new java.io.ByteArrayInputStream(input.readNBytes(packetSize)));
                assertEquals(0, MinecraftServerStatusClient.readVarInt(handshake));
                assertEquals(-1, MinecraftServerStatusClient.readVarInt(handshake));
                String host = new String(handshake.readNBytes(MinecraftServerStatusClient.readVarInt(handshake)), StandardCharsets.UTF_8);
                assertEquals(server.getLocalPort(), handshake.readUnsignedShort());
                assertEquals(1, MinecraftServerStatusClient.readVarInt(handshake));
                assertEquals(1, MinecraftServerStatusClient.readVarInt(input));
                assertEquals(0, MinecraftServerStatusClient.readVarInt(input));
                byte[] json = "{\"players\":{\"online\":37,\"max\":120},\"version\":{\"name\":\"1.20.1\"},\"description\":{\"text\":\"鲸歌\",\"color\":\"aqua\"}}".getBytes(StandardCharsets.UTF_8);
                var buffer = new ByteArrayOutputStream();
                var body = new DataOutputStream(buffer);
                MinecraftServerStatusClient.writeVarInt(body, 0);
                MinecraftServerStatusClient.writeVarInt(body, json.length);
                body.write(json);
                var output = new DataOutputStream(socket.getOutputStream());
                MinecraftServerStatusClient.writeVarInt(output, buffer.size());
                output.write(buffer.toByteArray());
                output.flush();
                hostname.complete(host);
            } catch (Throwable error) { hostname.completeExceptionally(error); }
        });
    }
}
