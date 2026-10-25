package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** Own worker and coalesced refresh requests keep status failures out of the update lifecycle. */
final class PlayerServerStatusMonitor implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("minecraft-status-monitor").factory());
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Consumer<ServerStatus> sink;
    private final Function<String, ServerStatus> query;
    private volatile Target target;
    private volatile boolean closed;
    private record Target(String address) { }

    PlayerServerStatusMonitor(Consumer<ServerStatus> sink) { this(sink, new MinecraftServerStatusClient()::query); }
    PlayerServerStatusMonitor(Consumer<ServerStatus> sink, Function<String, ServerStatus> query) {
        this.sink = sink;
        this.query = query;
        executor.scheduleWithFixedDelay(this::refresh, 30, 30, TimeUnit.SECONDS);
    }

    synchronized void configure(Branding branding) {
        boolean enabled = branding.serverAddress() != null && !branding.serverAddress().isBlank()
                && (branding.serverCard() == null || !Boolean.FALSE.equals(branding.serverCard().showStatus()));
        String address = enabled ? branding.serverAddress().trim() : null;
        if (target != null && target.address().equals(address)) return;
        target = enabled ? new Target(address) : null;
        if (enabled) refresh();
    }

    void refresh() {
        if (closed || target == null || !queued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                Target snapshot = target;
                try {
                    if (closed || snapshot == null) return;
                    emit(ServerStatus.checking());
                    ServerStatus status;
                    try { status = query.apply(snapshot.address()); }
                    catch (RuntimeException error) { status = ServerStatus.offline(); }
                    if (!closed && target == snapshot) emit(status);
                } finally {
                    queued.set(false);
                    if (!closed && target != snapshot) refresh();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { queued.set(false); }
    }

    private void emit(ServerStatus status) { try { sink.accept(status); } catch (RuntimeException ignored) { } }
    @Override public void close() { closed = true; target = null; executor.shutdownNow(); }
}
