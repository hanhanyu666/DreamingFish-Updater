package cn.dreamingfish.updater.engine;

import java.time.Instant;

/**
 * One file inside a player backup archive.
 *
 * @param originalPath instance-relative path the file was moved from
 * @param storedPath   archive-relative path of the stored copy
 * @param detail       owner-provided explanation, such as a withdrawal reason
 * @param restoredAt   when the player restored the copy, or {@code null}
 */
public record ArchivedFile(String originalPath, String storedPath, String sha256, long size,
                           ArchiveReason reason, String detail, String componentId,
                           String version, Instant restoredAt) {
    public ArchivedFile {
        detail = detail == null ? "" : detail;
    }

    public ArchivedFile restored(Instant time) {
        return new ArchivedFile(originalPath, storedPath, sha256, size, reason, detail,
                componentId, version, time);
    }
}
