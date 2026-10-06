package cn.dreamingfish.updater.management;

import java.time.Instant;

/** A private settings journal entry; snapshots are never sent by public distribution. */
public record SettingsOperation(String id, String projectId, Instant createdAt, String kind,
                                ProjectSettingsSnapshot before, ProjectSettingsSnapshot after,
                                boolean undone) {}
