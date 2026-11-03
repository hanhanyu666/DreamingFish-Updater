package cn.dreamingfish.updater.management;

import java.io.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Header timeout alone does not bound a stalled response body. */
final class TimedHttpResponse implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final HttpResponse<InputStream> response;
    private final InputStream body;
    private final ScheduledFuture<?> deadline;
    private volatile long lastRead = System.nanoTime();
    private TimedHttpResponse(HttpResponse<InputStream> response, Duration timeout, Duration idle) {
        this.response = response;
        body = new FilterInputStream(response.body()) {
            @Override public int read() throws IOException { int value = super.read(); lastRead = System.nanoTime(); return value; }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length); lastRead = System.nanoTime(); return count;
            }
        };
        if (idle == null) deadline = DEADLINES.schedule(this::abort, timeout.toMillis(), TimeUnit.MILLISECONDS);
        else {
            // A long transfer may legitimately take hours; it is cut when nothing arrives for the idle period.
            long end = System.nanoTime() + timeout.toNanos(), every = Math.max(1, Math.min(idle.toMillis() / 4, 15_000));
            deadline = DEADLINES.scheduleWithFixedDelay(() -> {
                long now = System.nanoTime();
                if (now - end >= 0 || now - lastRead >= idle.toNanos()) abort();
            }, every, every, TimeUnit.MILLISECONDS);
        }
    }
    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("dfs-official-download-deadline").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    static TimedHttpResponse send(HttpClient client, HttpRequest request, Duration bodyTimeout) throws IOException, InterruptedException {
        return send(client, request, bodyTimeout, null);
    }
    /** With {@code idle}, the body is also closed once no bytes arrive for that long. */
    static TimedHttpResponse send(HttpClient client, HttpRequest request, Duration bodyTimeout, Duration idle)
            throws IOException, InterruptedException {
        return new TimedHttpResponse(client.send(request, HttpResponse.BodyHandlers.ofInputStream()), bodyTimeout, idle);
    }
    private void abort() { try { response.body().close(); } catch (IOException ignored) { } }
    int statusCode() { return response.statusCode(); }
    String header(String name) { return response.headers().firstValue(name).orElse(""); }
    InputStream body() { return body; }
    public void close() throws IOException { deadline.cancel(false); response.body().close(); }
}
