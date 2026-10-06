package cn.dreamingfish.updater.engine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HttpTransferTest {
    @Test
    void timesOutAResponseBodyEvenAfterHeadersWereReceived() throws Exception {
        try (StalledServer server = new StalledServer(false)) {
            var response = HttpTransfer.send(HttpClient.newHttpClient(), server.request(),
                    Duration.ofMillis(150), CancellationToken.NEVER);
            try (var input = response.body()) {
                assertEquals('x', input.read());
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                        assertThrows(HttpTimeoutException.class, input::read));
            }
        }
    }

    @Test
    void cancellationClosesABlockedBodyRead() throws Exception {
        try (StalledServer server = new StalledServer(false);
             var executor = Executors.newSingleThreadExecutor()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            var task = executor.submit(() -> {
                var response = HttpTransfer.send(HttpClient.newHttpClient(), server.request(),
                        Duration.ofSeconds(10), cancelled::get);
                try (var input = response.body()) { input.readAllBytes(); }
                return null;
            });
            assertTrue(server.headers.await(3, TimeUnit.SECONDS));
            cancelled.set(true);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(3, TimeUnit.SECONDS));
            assertEquals(UpdateErrorCode.CANCELLED, ((UpdateException) error.getCause()).code());
        }
    }

    @Test
    void cancellationAlsoStopsWaitingForResponseHeaders() throws Exception {
        try (StalledServer server = new StalledServer(true);
             var executor = Executors.newSingleThreadExecutor()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            var task = executor.submit(() -> HttpTransfer.send(HttpClient.newHttpClient(), server.request(),
                    Duration.ofSeconds(10), cancelled::get));
            assertTrue(server.entered.await(3, TimeUnit.SECONDS));
            cancelled.set(true);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(3, TimeUnit.SECONDS));
            assertEquals(UpdateErrorCode.CANCELLED, ((UpdateException) error.getCause()).code());
        }
    }

    private static final class StalledServer implements AutoCloseable {
        final HttpServer server;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch headers = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        StalledServer(boolean stallHeaders) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                entered.countDown();
                try {
                    if (stallHeaders) release.await(5, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    headers.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    exchange.getResponseBody().write('y');
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (IOException expectedDisconnect) { }
                finally { exchange.close(); }
            });
            server.start();
        }

        HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .timeout(Duration.ofSeconds(5)).GET().build();
        }

        @Override public void close() { release.countDown(); server.stop(0); }
    }
}
