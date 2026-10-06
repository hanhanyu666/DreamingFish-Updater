package cn.dreamingfish.updater.player;

import java.util.List;

/**
 * The player's file-level choices.
 *
 * @param excludedComponents mods the player manages personally, by mod ID
 * @param resetRequests      default configurations to restore at the next update
 */
record LocalFilePreferences(
        int schemaVersion,
        long revision,
        List<String> excludedFiles,
        List<String> excludedDirectories,
        List<String> excludedComponents,
        List<String> resetRequests
) {
    static final int SCHEMA_VERSION = 1;

    LocalFilePreferences {
        excludedFiles = excludedFiles == null ? List.of() : List.copyOf(excludedFiles);
        excludedDirectories = excludedDirectories == null
                ? List.of() : List.copyOf(excludedDirectories);
        excludedComponents = excludedComponents == null ? List.of() : List.copyOf(excludedComponents);
        resetRequests = resetRequests == null ? List.of() : List.copyOf(resetRequests);
    }

    LocalFilePreferences(int schemaVersion, long revision, List<String> excludedFiles,
                         List<String> excludedDirectories) {
        this(schemaVersion, revision, excludedFiles, excludedDirectories, List.of(), List.of());
    }

    static LocalFilePreferences empty() {
        return new LocalFilePreferences(SCHEMA_VERSION, 0, List.of(), List.of(), List.of(), List.of());
    }
}
