package cn.dreamingfish.updater.engine;

import java.nio.file.Path;
import java.util.List;

record InstallResult(List<Path> archivedFiles, Path archiveDirectory) {
    InstallResult {
        archivedFiles = archivedFiles == null ? List.of() : List.copyOf(archivedFiles);
    }

    static InstallResult empty() {
        return new InstallResult(List.of(), null);
    }
}
