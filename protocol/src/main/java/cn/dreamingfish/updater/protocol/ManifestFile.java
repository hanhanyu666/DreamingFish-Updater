package cn.dreamingfish.updater.protocol;

public record ManifestFile(
        String path,
        String sha256,
        long size,
        FilePolicy policy,
        boolean executable
) {
}
