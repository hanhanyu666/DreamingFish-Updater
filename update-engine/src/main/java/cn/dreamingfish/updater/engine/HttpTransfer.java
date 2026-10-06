package cn.dreamingfish.updater.engine;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation and blocked-read deadlines, including after response headers arrive. */
public final class HttpTransfer {
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    private HttpTransfer() { }

    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("dfs-http-watchdog").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public static HttpResponse<InputStream> send(HttpClient client, HttpRequest request,
                                                 Duration readTimeout, CancellationToken cancellation)
            throws IOException, InterruptedException {
        cancellation.throwIfCancelled();
        if (readTimeout.isZero() || readTimeout.isNegative()) throw new IllegalArgumentException("Read timeout must be positive");
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        boolean received = false;
        try {
            while (true) {
                cancellation.throwIfCancelled();
                try {
                    HttpResponse<InputStream> response = pending.get(50, TimeUnit.MILLISECONDS);
                    received = true;
                    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
                        response.body().close();
                        throw new UpdateException(UpdateErrorCode.CANCELLED, "HTTP request was cancelled");
                    }
                    InputStream guarded = new GuardedStream(response.body(), readTimeout, cancellation);
                    return new HttpResponse<>() {
                        @Override public int statusCode() { return response.statusCode(); }
                        @Override public HttpRequest request() { return response.request(); }
                        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return response.previousResponse(); }
                        @Override public HttpHeaders headers() { return response.headers(); }
                        @Override public InputStream body() { return guarded; }
                        @Override public Optional<SSLSession> sslSession() { return response.sslSession(); }
                        @Override public URI uri() { return response.uri(); }
                        @Override public HttpClient.Version version() { return response.version(); }
                    };
                } catch (TimeoutException retry) {
                    // Poll the token while headers or the connection are pending.
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new IOException("HTTP request failed", cause);
                }
            }
        } finally {
            if (!received) {
                pending.whenComplete((response, error) -> {
                    if (response != null) try { response.body().close(); } catch (IOException ignored) { }
                });
                pending.cancel(true);
            }
        }
    }

    private static final class GuardedStream extends InputStream {
        private final InputStream input;
        private final CancellationToken cancellation;
        private volatile Thread reader;
        private final long timeoutNanos;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean reading;
        private volatile long readStarted;
        private volatile boolean cancelled;
        private volatile IOException timeout;
        private volatile ScheduledFuture<?> monitor;

        GuardedStream(InputStream input, Duration timeout, CancellationToken cancellation) {
            this.input = input;
            this.cancellation = cancellation;
            this.reader = Thread.currentThread();
            this.timeoutNanos = timeout.toNanos();
            monitor = WATCHDOG.scheduleWithFixedDelay(this::check, 20, 20, TimeUnit.MILLISECONDS);
        }

        private void check() {
            if (closed.get()) return;
            if (cancellation.isCancelled() || reader.isInterrupted()) {
                cancelled = true;
            } else if (reading && System.nanoTime() - readStarted >= timeoutNanos) {
                timeout = new HttpTimeoutException("HTTP response body read timed out");
            } else return;
            try { close(); } catch (IOException ignored) { }
        }

        private void failIfAborted() throws IOException {
            if (cancelled || cancellation.isCancelled() || reader.isInterrupted()) {
                throw new UpdateException(UpdateErrorCode.CANCELLED, "HTTP transfer was cancelled");
            }
            if (timeout != null) throw timeout;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            reader = Thread.currentThread();
            failIfAborted();
            readStarted = System.nanoTime();
            reading = true;
            try {
                int count = input.read(bytes, offset, length);
                failIfAborted();
                return count;
            } catch (IOException error) {
                failIfAborted();
                throw error;
            } finally { reading = false; }
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> task = monitor;
            if (task != null) task.cancel(false);
            input.close();
        }
    }
}
