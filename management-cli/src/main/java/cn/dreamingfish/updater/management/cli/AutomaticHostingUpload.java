package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ExternalHostingService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Uploads to the saved OSS / WebDAV destination after something public changed, for projects whose owner
 * saved credentials and kept automatic upload on. Unchanged projects cost one cheap digest per pass.
 */
final class AutomaticHostingUpload implements AutoCloseable {
    private final ManagementCli root;
    private final Lock projectGate;
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("dfs-hosting-auto-upload").factory());
    private ScheduledFuture<?> pending;

    AutomaticHostingUpload(ManagementCli root, Lock projectGate) {
        this.root = root; this.projectGate = projectGate;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    void start() { scheduler.scheduleWithFixedDelay(this::runOnce, 1, 10, TimeUnit.MINUTES); }

    /** After a change, upload shortly instead of waiting for the next pass; bursts of changes share one run. */
    synchronized void runSoon() {
        if (scheduler.isShutdown() || pending != null && !pending.isDone()) return;
        pending = scheduler.schedule(this::runOnce, 20, TimeUnit.SECONDS);
    }

    private void runOnce() {
        projectGate.lock();
        try {
            var services = root.services();
            new ExternalHostingService(services.paths(), services.database(), services.json(),
                    services.staticDistribution(), services.distributionUploader()).runAutomaticForAll();
        } catch (RuntimeException ignored) {
            // Each project records its own problem; a service-wide failure is retried on the next pass.
        } finally {
            projectGate.unlock();
        }
    }

    @Override public void close() { scheduler.shutdownNow(); }
}
