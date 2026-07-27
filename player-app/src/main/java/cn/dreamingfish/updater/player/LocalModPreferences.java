package cn.dreamingfish.updater.player;

import java.util.List;

record LocalModPreferences(int schemaVersion, long revision, List<LocalModPreference> mods) {
    static final int SCHEMA_VERSION = 1;

    LocalModPreferences {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }

    static LocalModPreferences empty() {
        return new LocalModPreferences(SCHEMA_VERSION, 0, List.of());
    }
}
