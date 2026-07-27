package cn.dreamingfish.updater.engine;

import java.time.Instant;

public record TrustState(
        int schemaVersion,
        String projectId,
        long highestSequence,
        String releaseId,
        String manifestSha256,
        Instant updatedAt
) {
    public static final int SCHEMA_VERSION = 1;
}
