package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.JsonCodec;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlayerServerStatusMonitorTest {
    private Branding branding(String address, boolean showStatus) {
        return new JsonCodec().read(("{\"productName\":\"测试\",\"serverAddress\":\"" + address + "\",\"serverCard\":{\"showStatus\":" + showStatus + "}}")
                .getBytes(StandardCharsets.UTF_8), Branding.class);
    }

    @Test void slowStatusWorkNeverBlocksConfigurationAndRapidRefreshIsCoalesced() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<ServerStatus> messages = new CopyOnWriteArrayList<>();
        try (var monitor = new PlayerServerStatusMonitor(status -> { messages.add(status); if (status.state().equals("OFFLINE")) finished.countDown(); }, address -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("optional status failure");
        })) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> monitor.configure(branding("example.test", true)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) monitor.refresh();
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(List.of("CHECKING", "OFFLINE"), messages.stream().map(ServerStatus::state).toList());
        }
    }

    @Test void doesNotPingWhenDisabledAndSuppressesResultsForReplacedAddress() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), replacement = new CountDownLatch(1);
        List<ServerStatus> messages = new CopyOnWriteArrayList<>();
        try (var monitor = new PlayerServerStatusMonitor(status -> { messages.add(status); if ("new".equals(status.version())) replacement.countDown(); }, address -> {
            calls.incrementAndGet();
            if (address.equals("old.test")) { entered.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            return new ServerStatus("ONLINE", 1, 10, 1L, "", address.equals("old.test") ? "old" : "new", null, java.time.Instant.now());
        })) {
            monitor.configure(branding("", true));
            monitor.configure(branding("example.test", false));
            monitor.refresh();
            assertEquals(0, calls.get());
            assertTrue(messages.isEmpty());
            monitor.configure(branding("old.test", true));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            monitor.configure(branding("new.test", true));
            release.countDown();
            assertTrue(replacement.await(2, TimeUnit.SECONDS));
            assertFalse(messages.stream().anyMatch(status -> "old".equals(status.version())));
        }
    }
}
