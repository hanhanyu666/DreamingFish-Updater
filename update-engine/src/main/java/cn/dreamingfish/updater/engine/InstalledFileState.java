package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.FilePolicy;

public record InstalledFileState(String path, String sha256, long size, FilePolicy policy) {
}
