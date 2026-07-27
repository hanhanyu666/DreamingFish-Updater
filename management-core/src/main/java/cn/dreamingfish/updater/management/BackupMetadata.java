package cn.dreamingfish.updater.management;

import java.time.Instant;

record BackupMetadata(int formatVersion, Instant createdAt, String productVersion) {
}
