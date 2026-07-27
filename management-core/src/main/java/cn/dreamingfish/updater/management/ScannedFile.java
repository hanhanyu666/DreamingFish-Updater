package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.FilePolicy;

public record ScannedFile(
        String path,
        String sha256,
        long size,
        long lastModifiedMillis,
        FilePolicy policy,
        boolean executable,
        String componentId,
        String displayName
) {
    public ScannedFile(String path, String sha256, long size, long lastModifiedMillis,
                       FilePolicy policy, boolean executable) {
        this(path, sha256, size, lastModifiedMillis, policy, executable, null, null);
    }
}
