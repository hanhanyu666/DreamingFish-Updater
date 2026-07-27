package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.PlayerProgramFile;
import cn.dreamingfish.updater.protocol.PlayerProgramManifest;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TestUpdateServer implements AutoCloseable {
    private final JsonCodec json = new JsonCodec();
    private final KeyPair keys = CryptoSupport.generateEd25519KeyPair();
    private final Map<String, byte[]> objects = new HashMap<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile byte[] manifestBytes;
    private volatile String signature;
    private volatile byte[] playerManifestBytes;
    private volatile String playerSignature;
    volatile boolean invalidSignature;
    volatile boolean unavailable;
    volatile String lastRange;

    TestUpdateServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    ProjectBinding binding() {
        return new ProjectBinding(ProtocolConstants.BINDING_SCHEMA_VERSION, "demo",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                CryptoSupport.encodePublicKey(keys.getPublic()), "DreamingFishUpdater",
                null, Branding.empty());
    }

    TestFile file(String path, String text, FilePolicy policy) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = CryptoSupport.sha256(bytes);
        objects.put(hash, bytes);
        return new TestFile(path, bytes, hash, policy, null, null);
    }

    TestFile mod(String path, String text, FilePolicy policy,
                 String componentId, String displayName) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = CryptoSupport.sha256(bytes);
        objects.put(hash, bytes);
        return new TestFile(path, bytes, hash, policy, componentId, displayName);
    }

    ReleaseManifest release(long sequence, String id, TestFile... testFiles) {
        return release(sequence, id, List.of(), testFiles);
    }

    ReleaseManifest release(long sequence, String id, List<String> forcedSyncDirectories,
                            TestFile... testFiles) {
        List<ManifestFile> files = new ArrayList<>();
        for (TestFile file : testFiles) {
            files.add(new ManifestFile(file.path(), file.sha256(), file.bytes().length,
                    file.policy(), false, file.componentId(), file.displayName()));
        }
        files.sort(Comparator.comparing(ManifestFile::path));
        Set<String> capabilities = forcedSyncDirectories.isEmpty()
                ? Set.of()
                : Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC);
        return new ReleaseManifest(ProtocolConstants.RELEASE_SCHEMA_VERSION, "demo", id,
                sequence, Instant.now(), "1.0." + sequence, "0.1.0", "release " + sequence,
                capabilities, forcedSyncDirectories, Branding.empty(), files);
    }

    void serve(ReleaseManifest manifest) {
        manifestBytes = json.write(manifest);
        signature = Base64.getEncoder().encodeToString(CryptoSupport.sign(manifestBytes, keys.getPrivate()));
        invalidSignature = false;
        unavailable = false;
        lastRange = null;
    }

    void bundle(Path instance, ReleaseManifest manifest, boolean materializeFiles) throws IOException {
        byte[] bytes = json.write(manifest);
        String bundledSignature = Base64.getEncoder().encodeToString(
                CryptoSupport.sign(bytes, keys.getPrivate()));
        Path directory = Files.createDirectories(
                instance.resolve(".dreamingfish-bootstrap/bundled-release"));
        Files.write(directory.resolve("manifest.json"), bytes);
        Files.writeString(directory.resolve("manifest.sig"), bundledSignature + "\n",
                StandardCharsets.US_ASCII);
        if (materializeFiles) {
            for (ManifestFile file : manifest.files()) {
                byte[] content = objects.get(file.sha256());
                if (content == null) throw new IOException("Missing test object " + file.sha256());
                Path target = instance.resolve(file.path().replace('/', java.io.File.separatorChar));
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            }
            for (String forced : manifest.forcedSyncDirectories()) {
                Files.createDirectories(instance.resolve(forced));
            }
        }
    }

    PlayerProgramFile playerFile(String path, String text, boolean executable) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = CryptoSupport.sha256(bytes);
        objects.put(hash, bytes);
        return new PlayerProgramFile(path, hash, bytes.length, executable);
    }

    PlayerProgramManifest playerProgram(String version, String launchPath,
                                        PlayerProgramFile... programFiles) {
        List<PlayerProgramFile> files = new ArrayList<>(List.of(programFiles));
        files.sort(Comparator.comparing(PlayerProgramFile::path));
        return new PlayerProgramManifest(ProtocolConstants.PLAYER_PROGRAM_SCHEMA_VERSION,
                "demo", "windows-x64", version, Instant.now(), launchPath,
                "0.1.0", Set.of(), files);
    }

    void servePlayerProgram(PlayerProgramManifest manifest) {
        playerManifestBytes = json.write(manifest);
        playerSignature = Base64.getEncoder().encodeToString(
                CryptoSupport.sign(playerManifestBytes, keys.getPrivate()));
        invalidSignature = false;
        unavailable = false;
        lastRange = null;
    }

    void tamperObject(String hash, String replacement) {
        objects.put(hash, replacement.getBytes(StandardCharsets.UTF_8));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (unavailable) {
                send(exchange, 503, "unavailable".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/projects/demo/latest")) {
                exchange.getResponseHeaders().set(ProtocolConstants.SIGNATURE_HEADER,
                        invalidSignature ? Base64.getEncoder().encodeToString(new byte[64]) : signature);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                send(exchange, 200, manifestBytes);
                return;
            }
            if (path.equals("/v1/projects/demo/player/windows-x64/latest")) {
                if (playerManifestBytes == null) {
                    send(exchange, 404, new byte[0]);
                    return;
                }
                exchange.getResponseHeaders().set(ProtocolConstants.SIGNATURE_HEADER,
                        invalidSignature ? Base64.getEncoder().encodeToString(new byte[64]) : playerSignature);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                send(exchange, 200, playerManifestBytes);
                return;
            }
            String prefix = "/v1/objects/sha256/";
            if (path.startsWith(prefix)) {
                byte[] object = objects.get(path.substring(prefix.length()));
                if (object == null) {
                    send(exchange, 404, new byte[0]);
                    return;
                }
                String range = exchange.getRequestHeaders().getFirst("Range");
                lastRange = range;
                if (range != null && range.matches("bytes=\\d+-")) {
                    int offset = Integer.parseInt(range.substring(6, range.length() - 1));
                    if (offset >= object.length) {
                        exchange.getResponseHeaders().set("Content-Range", "bytes */" + object.length);
                        send(exchange, 416, new byte[0]);
                        return;
                    }
                    byte[] remainder = java.util.Arrays.copyOfRange(object, offset, object.length);
                    exchange.getResponseHeaders().set("Content-Range",
                            "bytes " + offset + "-" + (object.length - 1) + "/" + object.length);
                    send(exchange, 206, remainder);
                    return;
                }
                send(exchange, 200, object);
                return;
            }
            send(exchange, 404, new byte[0]);
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    record TestFile(String path, byte[] bytes, String sha256, FilePolicy policy,
                    String componentId, String displayName) {
        TestFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
