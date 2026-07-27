package cn.dreamingfish.updater.management;

import java.nio.file.Path;
import java.time.Instant;

public record StoredRelease(
        String projectId,
        String releaseId,
        long sequence,
        String displayVersion,
        Instant createdAt,
        String changelog,
        String manifestSha256,
        String signature,
        Path manifestPath
) {
}
