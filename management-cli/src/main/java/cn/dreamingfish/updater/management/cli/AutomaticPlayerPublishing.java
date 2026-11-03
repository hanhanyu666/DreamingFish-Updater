package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.OfficialPlayerPublisher;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Publishes newer official players for projects whose owner opted in. It runs only while this
 * management service runs; the catalogue cache keeps network checks to one every six hours.
 */
final class AutomaticPlayerPublishing implements AutoCloseable {
    private final ManagementCli root;
    private final Lock projectGate;
    private final Runnable afterPass;
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("dfs-official-player-auto").factory());

    /** {@code afterPass} lets a newly published player reach saved external hosting without waiting. */
    AutomaticPlayerPublishing(ManagementCli root, Lock projectGate, Runnable afterPass) {
        this.root = root; this.projectGate = projectGate; this.afterPass = afterPass;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    void start() { scheduler.scheduleWithFixedDelay(this::runOnce, 2, 60, TimeUnit.MINUTES); }

    /** After the owner turns it on, the first pass need not wait for the hourly schedule. */
    void runSoon() {
        if (!scheduler.isShutdown()) scheduler.schedule(this::runOnce, 5, TimeUnit.SECONDS);
    }

    private void runOnce() {
        projectGate.lock();
        try {
            var services = root.services();
            new OfficialPlayerPublisher(services.paths(), services.database(), services.json())
                    .runAutomaticForAll(ManagementCli.VERSION);
        } catch (RuntimeException ignored) {
            // Each project records its own problem; a service-wide failure is retried on the next pass.
        } finally {
            projectGate.unlock();
        }
        afterPass.run();
    }

    @Override public void close() { scheduler.shutdownNow(); }
}
