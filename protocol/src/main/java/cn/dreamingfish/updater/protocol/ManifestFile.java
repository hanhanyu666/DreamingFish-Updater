package cn.dreamingfish.updater.protocol;

public record ManifestFile(
        String path,
        String sha256,
        long size,
        FilePolicy policy,
        boolean executable,
        String componentId,
        String displayName
) {
    public ManifestFile(String path, String sha256, long size, FilePolicy policy,
                        boolean executable) {
        this(path, sha256, size, policy, executable, null, null);
    }

    public ManifestFile {
        componentId = componentId == null || componentId.isBlank() ? null : componentId.trim();
        displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
    }
}
