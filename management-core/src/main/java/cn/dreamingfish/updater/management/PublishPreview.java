package cn.dreamingfish.updater.management;

import java.time.Instant;
import java.util.List;

public record PublishPreview(
        int schemaVersion,
        String previewId,
        String projectId,
        String baseReleaseId,
        Instant createdAt,
        List<ScannedFile> files,
        List<PreviewChange> changes,
        long totalManagedBytes,
        long estimatedDownloadBytes
) {
    public PublishPreview {
        files = files == null ? List.of() : List.copyOf(files);
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
