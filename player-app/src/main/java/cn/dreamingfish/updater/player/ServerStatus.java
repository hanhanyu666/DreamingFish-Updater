package cn.dreamingfish.updater.player;

import java.time.Instant;

/** Result of an optional Minecraft server-list ping; it has no bearing on launch permission. */
public record ServerStatus(String state, Integer onlinePlayers, Integer maxPlayers,
                           Long latencyMs, String motd, String version, String favicon,
                           Instant checkedAt) {
    static ServerStatus checking() { return new ServerStatus("CHECKING", null, null, null, null, null, null, Instant.now()); }
    static ServerStatus offline() { return new ServerStatus("OFFLINE", null, null, null, null, null, null, Instant.now()); }
}
