package cn.dreamingfish.updater.protocol;

/**
 * One file of a release target state. {@code policy} keeps the historical wire
 * token; releases declaring {@link ProtocolConstants#CAPABILITY_MAINTENANCE_POLICY}
 * describe maintenance through {@code preset} and {@code optionalGroup}.
 */
public record ManifestFile(
        String path,
        String sha256,
        long size,
        FilePolicy policy,
        boolean executable,
        String componentId,
        String displayName,
        MaintenancePreset preset,
        String optionalGroup,
        String version
) {
    public ManifestFile(String path, String sha256, long size, FilePolicy policy,
                        boolean executable) {
        this(path, sha256, size, policy, executable, null, null, null, null, null);
    }

    public ManifestFile(String path, String sha256, long size, FilePolicy policy,
                        boolean executable, String componentId, String displayName) {
        this(path, sha256, size, policy, executable, componentId, displayName,
                null, null, null);
    }

    public ManifestFile {
        componentId = componentId == null || componentId.isBlank() ? null : componentId.trim();
        displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        optionalGroup = optionalGroup == null || optionalGroup.isBlank() ? null : optionalGroup.trim();
        version = version == null || version.isBlank() ? null : version.trim();
    }
}
