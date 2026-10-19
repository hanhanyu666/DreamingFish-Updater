package cn.dreamingfish.updater.management;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

/** Uses only generated content and a loopback listener. */
public final class ManagementReviewProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]).resolve("management"));
        var fixture = new ManagementFixture(root);
        fixture.createProject();
        var service = new SourceTransferService(fixture.paths, fixture.database, fixture.json);
        byte[] content = "fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var plan = service.stage("demo", "config/test.txt", new ByteArrayInputStream(content), content.length);
        try {
            service.commit("demo", plan.id(), "SKIP", plan.stamp(), false);
            System.out.println("SKIP accepted");
        } catch (ManagementException rejected) {
            System.out.println("SKIP rejected=" + rejected.getMessage());
        }
        service.commit("demo", plan.id(), "ADD", plan.stamp(), false);
        Path imports = fixture.paths.root().resolve("source-imports");
        try (var files = Files.walk(imports)) {
            System.out.println("RETAINED_IMPORT=" + files.filter(Files::isRegularFile)
                    .map(imports::relativize).toList());
        }

        Path large = root.resolve("download.bin");
        byte[] buffer = new byte[128 * 1024];
        new Random(42).nextBytes(buffer);
        try (OutputStream out = Files.newOutputStream(large)) {
            for (int i = 0; i < 256; i++) out.write(buffer);
        }
        var object = fixture.objects.importFile(large);
        List<Socket> slow = new ArrayList<>();
        try (var server = new PublicFileServer(fixture.database, fixture.objects, new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var field = PublicFileServer.class.getDeclaredField("executor");
            field.setAccessible(true);
            var pool = (ThreadPoolExecutor) field.get(server);
            System.out.println("PUBLIC_POOL workers=" + pool.getMaximumPoolSize()
                    + " queueRemainingCapacity=" + pool.getQueue().remainingCapacity());
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                URI health = URI.create("http://127.0.0.1:" + server.address().getPort() + "/healthz");
                System.out.println("HEALTH_BEFORE=" + client.send(HttpRequest.newBuilder(health)
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                try {
                    for (int i = 0; i < 64; i++) {
                        Socket socket = new Socket();
                        socket.setReceiveBufferSize(1024);
                        socket.connect(server.address(), 2000);
                        slow.add(socket);
                        socket.getOutputStream().write(("GET /v1/objects/sha256/" + object.sha256()
                                + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (pool.getActiveCount() < 64 && System.nanoTime() < deadline) Thread.sleep(20);
                    System.out.println("SLOW_READERS active=" + pool.getActiveCount());
                    try {
                        int status = client.send(HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(2)).build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode();
                        System.out.println("HEALTH_DURING=" + status);
                    } catch (HttpTimeoutException expected) {
                        System.out.println("HEALTH_DURING=TIMEOUT queueSize=" + pool.getQueue().size());
                    }
                } finally {
                    for (Socket socket : slow) socket.close();
                }
                System.out.println("HEALTH_AFTER=" + client.send(HttpRequest.newBuilder(health)
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }
}
