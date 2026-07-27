package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.FilePolicy;

record FileOperation(OperationKind kind, String path, String sha256, long size,
                     FilePolicy policy, boolean executable) {
}
