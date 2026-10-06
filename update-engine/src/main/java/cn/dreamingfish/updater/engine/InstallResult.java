package cn.dreamingfish.updater.engine;

import java.nio.file.Path;
import java.util.List;

record InstallResult(List<Path> archivedFiles, Path archiveDirectory, List<ArchivedFile> archived) {
    InstallResult {
        archivedFiles = archivedFiles == null ? List.of() : List.copyOf(archivedFiles);
        archived = archived == null ? List.of() : List.copyOf(archived);
    }

    static InstallResult empty() {
        return new InstallResult(List.of(), null, List.of());
    }
}
