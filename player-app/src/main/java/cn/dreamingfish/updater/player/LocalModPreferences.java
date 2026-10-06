package cn.dreamingfish.updater.player;

import java.util.List;

record LocalModPreferences(int schemaVersion, long revision, List<LocalModPreference> mods,
                           List<String> appliedStoredCorrections) {
    static final int SCHEMA_VERSION = 2;

    LocalModPreferences {
        mods = mods == null ? List.of() : List.copyOf(mods);
        appliedStoredCorrections = appliedStoredCorrections == null ? List.of()
                : appliedStoredCorrections.stream().distinct().sorted().toList();
    }

    LocalModPreferences(int schemaVersion, long revision, List<LocalModPreference> mods) {
        this(schemaVersion, revision, mods, List.of());
    }

    static LocalModPreferences empty() {
        return new LocalModPreferences(SCHEMA_VERSION, 0, List.of());
    }
}
