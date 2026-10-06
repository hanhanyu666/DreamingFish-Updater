package cn.dreamingfish.updater.protocol;

import java.time.Instant;
import java.util.List;

/**
 * 修正配置: a persistent owner directive that puts the currently published
 * content of {@code path} onto player instances even when the file's normal
 * preset would keep the player's copy. The player's copy is backed up first.
 */
public record Correction(String id, String path, CorrectionMode mode, List<String> badSha256,
                         String reason, Instant createdAt) {
    public Correction {
        badSha256 = badSha256 == null ? List.of() : List.copyOf(badSha256);
        reason = reason == null ? "" : reason.trim();
    }
}
