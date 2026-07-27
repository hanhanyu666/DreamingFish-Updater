package cn.dreamingfish.updater.management;

import java.nio.file.Path;
import java.time.Instant;

public record StoredPlayerProgram(
        String projectId,
        String platform,
        String version,
        Instant createdAt,
        String manifestSha256,
        String signature,
        Path manifestPath
) {
}
