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
        Path archiveDirectory,
        List<Path> installedPaths,
        List<Path> deletedPaths,
        List<Path> releasedPaths
) {
    public UpdateResult {
        unmanagedMods = unmanagedMods == null ? List.of() : List.copyOf(unmanagedMods);
        archivedFiles = archivedFiles == null ? List.of() : List.copyOf(archivedFiles);
        installedPaths = installedPaths == null ? List.of() : List.copyOf(installedPaths);
        deletedPaths = deletedPaths == null ? List.of() : List.copyOf(deletedPaths);
        releasedPaths = releasedPaths == null ? List.of() : List.copyOf(releasedPaths);
    }

    public UpdateResult(UpdateOutcome outcome, ReleaseManifest release, int installedFiles,
                        int deletedFiles, long downloadedBytes, List<Path> unmanagedMods,
                        List<Path> archivedFiles, Path archiveDirectory) {
        this(outcome, release, installedFiles, deletedFiles, downloadedBytes, unmanagedMods,
                archivedFiles, archiveDirectory, List.of(), List.of(), List.of());
    }

    public boolean launchAllowed() {
        return outcome != UpdateOutcome.GAME_RUNNING;
    }
}
