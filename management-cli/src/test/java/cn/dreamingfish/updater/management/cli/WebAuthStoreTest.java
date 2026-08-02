package cn.dreamingfish.updater.management.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class WebAuthStoreTest {
    @TempDir Path temporary;

    @Test void hashesCredentialsAndManagesOpaqueSessions() throws Exception {
        Path file = temporary.resolve("management-web-auth.json");
        WebAuthStore store = new WebAuthStore(file);
        store.register("admin_user", "correct horse battery".toCharArray(), true);
        String persisted = Files.readString(file);
        assertFalse(persisted.contains("correct horse battery"));
        assertTrue(persisted.contains("600000"));
        assertTrue(store.verify("admin_user", "correct horse battery".toCharArray()));
        assertFalse(store.verify("admin_user", "incorrect password".toCharArray()));
        String session = store.createSession();
        assertTrue(store.sessionValid(session));
        store.logout(session);
        assertFalse(store.sessionValid(session));
    }

    @Test void validatesUsernameAndPasswordBounds() {
        WebAuthStore store = new WebAuthStore(temporary.resolve("auth.json"));
        assertThrows(IllegalArgumentException.class, () -> store.register("x", "short".toCharArray(), false));
        assertThrows(IllegalArgumentException.class, () -> store.register("valid-name", "x".repeat(257).toCharArray(), false));
    }

    @Test void cleanupDoesNotRefreshIdleSessions() {
        MutableClock clock = new MutableClock();
        WebAuthStore store = new WebAuthStore(
                temporary.resolve("auth.json"), clock);
        store.register("valid-name", "a sufficiently long password".toCharArray(), false);
        String session = store.createSession();
        clock.advance(Duration.ofMinutes(29));
        store.cleanup();
        clock.advance(Duration.ofMinutes(2));
        assertFalse(store.sessionValid(session));
    }

    @Test void rejectsUnsafePersistedHashParameters() throws Exception {
        Path file = temporary.resolve("auth.json");
        Files.writeString(file, """
                {"schemaVersion":1,"username":"admin","salt":"AA==",\
                "hash":"AA==","iterations":1,"allowLocalBypass":false}
                """);
        assertThrows(RuntimeException.class, () -> new WebAuthStore(file));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-02T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
