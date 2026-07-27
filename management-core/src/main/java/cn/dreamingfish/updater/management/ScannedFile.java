package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.FilePolicy;

public record ScannedFile(
        String path,
        String sha256,
        long size,
        long lastModifiedMillis,
        FilePolicy policy,
        boolean executable
) {
}
