package cn.dreamingfish.updater.protocol;

import java.util.List;

public record ReleaseHistory(
        int schemaVersion,
        String projectId,
        List<ReleaseHistoryEntry> releases
) {
    public ReleaseHistory {
        releases = releases == null ? List.of() : List.copyOf(releases);
    }
}
