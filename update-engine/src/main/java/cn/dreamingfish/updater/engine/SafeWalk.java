package cn.dreamingfish.updater.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists regular files below a directory without crossing symbolic links,
 * Windows junctions or other reparse points. Java reports junctions as
 * directories that are also "other" entries, so both flags are checked.
 */
final class SafeWalk {
    enum Mode {
        /** Any alias or special entry fails the update. */
        STRICT,
        /** Aliases and special entries are skipped, for read-only reports. */
        LENIENT
    }

    private SafeWalk() {
    }

    static List<Path> regularFiles(Path root, Mode mode, String label) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        return reject(directory, "a directory alias");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        files.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                    if (attributes.isDirectory() && attributes.isOther()) {
                        return reject(file, "a directory alias");
                    }
                    return reject(file, attributes.isSymbolicLink()
                            ? "a symbolic link" : "an unsupported entry");
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                    if (mode == Mode.LENIENT) return FileVisitResult.CONTINUE;
                    throw error;
                }

                private FileVisitResult reject(Path path, String kind) {
                    if (mode == Mode.LENIENT) return FileVisitResult.SKIP_SUBTREE;
                    throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                            label + " contains " + kind + ": " + path);
                }
            });
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to scan " + label, e);
        }
        files.sort(null);
        return files;
    }
}
