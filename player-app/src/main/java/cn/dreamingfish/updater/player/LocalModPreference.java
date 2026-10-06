package cn.dreamingfish.updater.player;

import java.time.Instant;
import java.util.List;

/**
 * One local mod switch.
 *
 * @param group the optional group whose switch created this entry, or {@code null}
 *              when the player disabled the mod individually
 */
record LocalModPreference(
        String key,
        String componentId,
        String path,
        String displayName,
        boolean disabled,
        boolean managedAtDisable,
        List<StoredLocalMod> storedFiles,
        Instant changedAt,
        String group
) {
    LocalModPreference {
        storedFiles = storedFiles == null ? List.of() : List.copyOf(storedFiles);
        changedAt = changedAt == null ? Instant.now() : changedAt;
        group = group == null || group.isBlank() ? null : group;
    }

    LocalModPreference(String key, String componentId, String path, String displayName,
                       boolean disabled, boolean managedAtDisable, List<StoredLocalMod> storedFiles,
                       Instant changedAt) {
        this(key, componentId, path, displayName, disabled, managedAtDisable, storedFiles,
                changedAt, null);
    }

    LocalModPreference withStoredFiles(List<StoredLocalMod> files) {
        return new LocalModPreference(key, componentId, path, displayName, disabled,
                managedAtDisable, files, changedAt, group);
    }

    LocalModPreference withDisabled(boolean value) {
        return new LocalModPreference(key, componentId, path, displayName, value,
                managedAtDisable, storedFiles, Instant.now(), group);
    }

    LocalModPreference withManagedAtDisable(boolean value) {
        return new LocalModPreference(key, componentId, path, displayName, disabled,
                value, storedFiles, changedAt, group);
    }

    LocalModPreference withGroup(String value) {
        return new LocalModPreference(key, componentId, path, displayName, disabled,
                managedAtDisable, storedFiles, changedAt, value);
    }
}
