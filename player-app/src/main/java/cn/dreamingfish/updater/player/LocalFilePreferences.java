package cn.dreamingfish.updater.player;

import java.util.List;

record LocalFilePreferences(
        int schemaVersion,
        long revision,
        List<String> excludedFiles,
        List<String> excludedDirectories
) {
    static final int SCHEMA_VERSION = 1;

    LocalFilePreferences {
        excludedFiles = excludedFiles == null ? List.of() : List.copyOf(excludedFiles);
        excludedDirectories = excludedDirectories == null
                ? List.of() : List.copyOf(excludedDirectories);
    }

    static LocalFilePreferences empty() {
        return new LocalFilePreferences(SCHEMA_VERSION, 0, List.of(), List.of());
    }
}
