package cn.dreamingfish.updater.engine;

import java.time.Instant;
import java.util.List;

/** Machine-readable index written into every player backup archive. */
public record ArchiveIndex(int schemaVersion, String archiveId, String releaseId,
                           String displayVersion, Instant createdAt, List<ArchivedFile> files) {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "archive-index.json";

    public ArchiveIndex {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public ArchiveIndex withFiles(List<ArchivedFile> replacement) {
        return new ArchiveIndex(schemaVersion, archiveId, releaseId, displayVersion,
                createdAt, replacement);
    }
}
