package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicFileServerTest {
    @TempDir
    Path temporary;

    @Test
    void servesSignedManifestAndRangeAddressedObjects() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        ProjectRecord project = fixture.createProject();
        Files.createDirectories(fixture.source.resolve("mods"));
        byte[] content = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(fixture.source.resolve("mods/example.jar"), content);
        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish("demo", "1.0.0", "0.1.0", "Ready");
        String hash = fixture.database.readManifest(release).files().getFirst().sha256();
        Path playerSource = Files.createDirectories(temporary.resolve("player-program"));
        byte[] playerBytes = "player-launcher".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(playerSource.resolve("player.cmd"), playerBytes);
        StoredPlayerProgram playerProgram = new PlayerProgramService(
                fixture.paths, fixture.database, fixture.json).publish(
                "demo", "windows-x64", "0.2.0", playerSource, "player.cmd", "0.1.0");

        try (PublicFileServer server = new PublicFileServer(
                fixture.database, fixture.objects, new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            String base = "http://127.0.0.1:" + server.address().getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<byte[]> manifest = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/projects/demo/latest")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, manifest.statusCode());
            String signature = manifest.headers().firstValue(ProtocolConstants.SIGNATURE_HEADER).orElseThrow();
            assertTrue(CryptoSupport.verify(
                    manifest.body(), Base64.getDecoder().decode(signature),
                    CryptoSupport.decodePublicKey(project.publicKey())));

            HttpResponse<byte[]> playerManifestResponse = client.send(
                    HttpRequest.newBuilder(URI.create(base
                                    + "/v1/projects/demo/player/windows-x64/latest"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, playerManifestResponse.statusCode());
            String playerSignature = playerManifestResponse.headers()
                    .firstValue(ProtocolConstants.SIGNATURE_HEADER).orElseThrow();
            assertTrue(CryptoSupport.verify(
                    playerManifestResponse.body(), Base64.getDecoder().decode(playerSignature),
                    CryptoSupport.decodePublicKey(project.publicKey())));
            PlayerProgramManifest playerManifest = new JsonCodec().read(
                    playerManifestResponse.body(), PlayerProgramManifest.class);
            assertEquals("0.2.0", playerManifest.version());
            assertEquals("windows-x64", playerManifest.platform());
            assertEquals(playerProgram.manifestSha256(),
                    CryptoSupport.sha256(playerManifestResponse.body()));

            HttpResponse<byte[]> playerObject = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/objects/sha256/"
                                    + playerManifest.files().getFirst().sha256()))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, playerObject.statusCode());
            assertArrayEquals(playerBytes, playerObject.body());

            HttpResponse<byte[]> range = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/objects/sha256/" + hash))
                            .header("Range", "bytes=2-5").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, range.statusCode());
            assertArrayEquals("2345".getBytes(java.nio.charset.StandardCharsets.UTF_8), range.body());
            assertEquals("bytes 2-5/10", range.headers().firstValue("Content-Range").orElseThrow());

            HttpResponse<byte[]> cached = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/v1/objects/sha256/" + hash))
                            .header("If-None-Match", "\"" + hash + "\"").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(304, cached.statusCode());
        }
    }
}
