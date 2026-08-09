package cn.dreamingfish.updater.management;

import java.nio.file.Path;
import java.time.Instant;

public record StaticDistributionExportResult(
        String projectId,
        Path outputDirectory,
        Instant generatedAt,
        int releaseCount,
        int playerProgramCount,
        int objectCount,
        int copiedObjectCount,
        int reusedObjectCount,
        long totalObjectBytes,
        long copiedObjectBytes
) {
}
