package cn.dreamingfish.updater.player;

import java.time.Instant;
import java.util.List;

record LocalModPreference(
        String key,
        String componentId,
        String path,
        String displayName,
        boolean disabled,
        boolean managedAtDisable,
        List<StoredLocalMod> storedFiles,
        Instant changedAt
) {
    LocalModPreference {
        storedFiles = storedFiles == null ? List.of() : List.copyOf(storedFiles);
        changedAt = changedAt == null ? Instant.now() : changedAt;
    }

    LocalModPreference withStoredFiles(List<StoredLocalMod> files) {
        return new LocalModPreference(key, componentId, path, displayName, disabled,
                managedAtDisable, files, changedAt);
    }

    LocalModPreference withDisabled(boolean value) {
        return new LocalModPreference(key, componentId, path, displayName, value,
                managedAtDisable, storedFiles, Instant.now());
    }
}
