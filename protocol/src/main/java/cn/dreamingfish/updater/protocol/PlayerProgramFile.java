package cn.dreamingfish.updater.protocol;

public record PlayerProgramFile(String path, String sha256, long size, boolean executable) {
}
