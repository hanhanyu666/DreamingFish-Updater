package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EngineAuditProbe {
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        junction(Path.of(arguments[1]).toAbsolutePath().normalize());
        changingStaticSignature(Files.createTempDirectory(output, "signature-race-"));
        stalledBody(Files.createTempDirectory(output, "stalled-body-"));
    }

    private static void junction(Path instance) throws Exception {
        Path directory = instance.resolve("mods");
        Path outside = instance.getParent().resolve("outside");
        if (!directory.toRealPath().equals(outside.toRealPath())) {
            throw new IllegalArgumentException("The junction must target the isolated sibling probe directory");
        }
        boolean rejected = false;
        try { PathSafety.resolveInside(instance, "mods/probe-marker.txt"); }
        catch (cn.dreamingfish.updater.protocol.ProtocolException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Junction escaped path validation");
        System.out.println("JUNCTION_ESCAPE rejected=true outsideUntouched="
                + !Files.exists(outside.resolve("probe-marker.txt")));
    }

    private static void changingStaticSignature(Path instance) throws Exception {
        KeyPair key = CryptoSupport.generateEd25519KeyPair();
        JsonCodec json = new JsonCodec();
        byte[] oldPayload = json.writePretty(release(1));
        byte[] newPayload = json.writePretty(release(2));
        String newSignature = Base64.getEncoder().encodeToString(
                CryptoSupport.sign(newPayload, key.getPrivate()));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A static latest payload was read just before promotion; its sidecar is read afterward.
        server.createContext("/v1/projects/demo/latest", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.endsWith(".signed")
                    ? new cn.dreamingfish.updater.protocol.SignedDocument(newPayload, newSignature).encode()
                    : path.endsWith(".sig") ? newSignature.getBytes(StandardCharsets.US_ASCII) : oldPayload;
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            ProjectBinding binding = binding(key, server.getAddress().getPort());
            UpdateRequest request = UpdateRequest.defaults(instance, instance.resolve("home"),
                    binding, "0.1.40", Set.of());
            SignedRelease accepted = new ManifestFetcher().fetch(request, key.getPublic(), null);
            if (accepted.manifest().sequence() != 2) throw new AssertionError("Atomic static payload was not selected");
            System.out.println("STATIC_SIGNATURE_RACE result=VERIFIED_ATOMIC_DOCUMENT sequence=2");
        } finally { server.stop(0); }
    }

    private static void stalledBody(Path instance) throws Exception {
        byte[] content = "object-content".getBytes(StandardCharsets.UTF_8);
        String hash = CryptoSupport.sha256(content);
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean enteredBodyRead = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/objects/sha256/", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(content, 0, 1);
                stream.flush();
                flushed.countDown();
                try { releaseBody.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                stream.write(content, 1, content.length - 1);
            }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            ProjectBinding binding = binding(CryptoSupport.generateEd25519KeyPair(),
                    server.getAddress().getPort());
            CancellationToken token = () -> {
                String thread = Thread.currentThread().getName();
                if (thread.startsWith("dreamingfish-download-")) enteredBodyRead.set(true);
                return cancelled.get();
            };
            UpdateRequest request = new UpdateRequest(instance, instance.resolve("home"),
                    binding, "0.1.40", Set.of(), Duration.ofSeconds(2),
                    Duration.ofMillis(200), null, token);
            EnginePaths paths = EnginePaths.of(instance, request.playerHome());
            paths.createDirectories();
            var task = executor.submit(() -> new ObjectDownloader().download(request, paths,
                    Map.of(hash, (long) content.length), ProgressListener.NONE));
            try {
                if (!flushed.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Server did not flush");
                Thread.sleep(500);
                boolean timedOut = task.isDone();
                if (!timedOut) throw new AssertionError("Response-body timeout did not stop the transfer");
                cancelled.set(true);
                Thread.sleep(400);
                System.out.println("STALLED_BODY requestTimeoutMs=200 elapsedAtLeastMs=900"
                        + " finishedBeforeCancel=" + timedOut
                        + " finishedAfterCancel=" + task.isDone()
                        + " workerWasRunning=" + enteredBodyRead.get());
            } finally {
                releaseBody.countDown();
                try { task.get(3, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException expected) { }
            }
        } finally { server.stop(0); }
    }

    private static ProjectBinding binding(KeyPair key, int port) {
        return new ProjectBinding(ProtocolConstants.BINDING_SCHEMA_VERSION, "demo",
                "http://127.0.0.1:" + port, CryptoSupport.encodePublicKey(key.getPublic()),
                "home", null, Branding.empty());
    }

    private static ReleaseManifest release(long sequence) {
        return new ReleaseManifest(ProtocolConstants.RELEASE_SCHEMA_VERSION, "demo", "r" + sequence,
                sequence, Instant.now(), sequence + ".0.0", "0.1.0", "", Set.of(),
                Branding.empty(), List.of());
    }
}
