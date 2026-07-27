package cn.dreamingfish.updater.engine;

import java.time.Instant;
import java.util.List;

public record VerifiedInstallation(
        int schemaVersion,
        String projectId,
        String releaseId,
        long sequence,
        String manifestSha256,
        Instant verifiedAt,
        List<InstalledFileState> files
) {
    public static final int SCHEMA_VERSION = 1;

    public VerifiedInstallation {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
