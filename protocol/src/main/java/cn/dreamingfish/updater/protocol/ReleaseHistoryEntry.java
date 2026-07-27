package cn.dreamingfish.updater.protocol;

import java.time.Instant;

public record ReleaseHistoryEntry(
        String releaseId,
        long sequence,
        String displayVersion,
        Instant createdAt,
        String changelog
) {
    public ReleaseHistoryEntry {
        changelog = changelog == null ? "" : changelog;
    }
}
