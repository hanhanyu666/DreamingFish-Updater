package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of one update run.
 *
 * @param archived               every file moved into the player backup, with its reason
 * @param keptModifiedPaths      default configurations kept because the player changed them
 * @param skippedSelfManagedPaths published updates not applied because the player manages the file
 * @param resetPaths             default configurations restored at the player's request
 */
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
        List<Path> releasedPaths,
        List<ArchivedFile> archived,
        List<Path> keptModifiedPaths,
        List<Path> skippedSelfManagedPaths,
        List<Path> resetPaths
) {
    public UpdateResult {
        unmanagedMods = copy(unmanagedMods);
        archivedFiles = copy(archivedFiles);
        installedPaths = copy(installedPaths);
        deletedPaths = copy(deletedPaths);
        releasedPaths = copy(releasedPaths);
        archived = archived == null ? List.of() : List.copyOf(archived);
        keptModifiedPaths = copy(keptModifiedPaths);
        skippedSelfManagedPaths = copy(skippedSelfManagedPaths);
        resetPaths = copy(resetPaths);
    }

    public UpdateResult(UpdateOutcome outcome, ReleaseManifest release, int installedFiles,
                        int deletedFiles, long downloadedBytes, List<Path> unmanagedMods,
                        List<Path> archivedFiles, Path archiveDirectory, List<Path> installedPaths,
                        List<Path> deletedPaths, List<Path> releasedPaths) {
        this(outcome, release, installedFiles, deletedFiles, downloadedBytes, unmanagedMods,
                archivedFiles, archiveDirectory, installedPaths, deletedPaths, releasedPaths,
                List.of(), List.of(), List.of(), List.of());
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

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
