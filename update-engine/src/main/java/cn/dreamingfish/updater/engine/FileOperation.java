package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.FilePolicy;

/**
 * One planned change to the game instance.
 *
 * <ul>
 *   <li>{@code INSTALL}: write the object {@code sha256}; when {@code archiveExisting} is set the
 *       current local copy is first moved into the player backup for {@code reason}.</li>
 *   <li>{@code DELETE}: remove an unmodified copy of official content.</li>
 *   <li>{@code ARCHIVE}: move the local copy into the player backup for {@code reason}.</li>
 * </ul>
 *
 * @param localSha256 hash of the local copy when known, for the archive index
 */
record FileOperation(OperationKind kind, String path, String sha256, long size,
                     FilePolicy policy, boolean executable, boolean archiveExisting,
                     ArchiveReason reason, String detail, String localSha256,
                     String componentId, String version) {
    FileOperation(OperationKind kind, String path, String sha256, long size,
                  FilePolicy policy, boolean executable) {
        this(kind, path, sha256, size, policy, executable, false, null, null, null, null, null);
    }

    static FileOperation install(String path, String sha256, long size, boolean executable) {
        return new FileOperation(OperationKind.INSTALL, path, sha256, size, FilePolicy.ENFORCED,
                executable, false, null, null, null, null, null);
    }

    static FileOperation replace(String path, String sha256, long size, boolean executable,
                                 ArchiveReason reason, String detail,
                                 LocalFileIndex.Inspection local) {
        return new FileOperation(OperationKind.INSTALL, path, sha256, size, FilePolicy.ENFORCED,
                executable, true, reason, detail,
                local == null ? null : local.sha256(),
                local == null ? null : local.componentId(),
                local == null ? null : local.version());
    }

    static FileOperation delete(String path, String sha256, long size) {
        return new FileOperation(OperationKind.DELETE, path, sha256, size, FilePolicy.ENFORCED,
                false, false, null, null, null, null, null);
    }

    static FileOperation archive(String path, long size, ArchiveReason reason, String detail,
                                 LocalFileIndex.Inspection local) {
        return new FileOperation(OperationKind.ARCHIVE, path, null, size, null, false, false,
                reason, detail,
                local == null ? null : local.sha256(),
                local == null ? null : local.componentId(),
                local == null ? null : local.version());
    }

    /** True when the operation moves the player's current copy into the backup. */
    boolean archivesLocalCopy() {
        return kind == OperationKind.ARCHIVE || archiveExisting;
    }
}
