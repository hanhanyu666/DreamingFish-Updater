package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.nio.file.Path;
import java.util.List;

public record UpdateResult(
        UpdateOutcome outcome,
        ReleaseManifest release,
        int installedFiles,
        int deletedFiles,
        long downloadedBytes,
        List<Path> unmanagedMods,
        List<Path> archivedFiles,
        Path archiveDirectory
) {
    public UpdateResult {
        unmanagedMods = unmanagedMods == null ? List.of() : List.copyOf(unmanagedMods);
        archivedFiles = archivedFiles == null ? List.of() : List.copyOf(archivedFiles);
    }

    public boolean launchAllowed() {
        return outcome != UpdateOutcome.GAME_RUNNING;
    }
}
