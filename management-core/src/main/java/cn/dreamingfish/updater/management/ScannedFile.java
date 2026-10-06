package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.MaintenancePreset;

/**
 * One file of the standard source directory together with the maintenance
 * the owner's rules give it. {@code policy} keeps the historical wire token.
 */
public record ScannedFile(
        String path,
        String sha256,
        long size,
        long lastModifiedMillis,
        FilePolicy policy,
        boolean executable,
        String componentId,
        String displayName,
        MaintenancePreset preset,
        String optionalGroup,
        String version
) {
    public ScannedFile(String path, String sha256, long size, long lastModifiedMillis,
                       FilePolicy policy, boolean executable) {
        this(path, sha256, size, lastModifiedMillis, policy, executable, null, null,
                MaintenancePreset.SYNC, null, null);
    }

    public ScannedFile(String path, String sha256, long size, long lastModifiedMillis,
                       FilePolicy policy, boolean executable, String componentId,
                       String displayName) {
        this(path, sha256, size, lastModifiedMillis, policy, executable, componentId,
                displayName, MaintenancePreset.SYNC, null, null);
    }

    public ScannedFile {
        preset = preset == null ? MaintenancePreset.SYNC : preset;
    }
}
