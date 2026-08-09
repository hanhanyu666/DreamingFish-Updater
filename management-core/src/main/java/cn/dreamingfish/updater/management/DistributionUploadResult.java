package cn.dreamingfish.updater.management;

import java.time.Instant;

public record DistributionUploadResult(
        String provider,
        String destination,
        Instant completedAt,
        int fileCount,
        int uploadedFileCount,
        int skippedFileCount,
        long totalBytes,
        long uploadedBytes
) {
}
