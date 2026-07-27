package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ModMetadataReader;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class UpdatePlanner {
    UpdatePlan create(EnginePaths paths, SignedRelease target, LocalInstallation local,
                      LocalFileOverrides overrides, ProgressListener listener,
                      CancellationToken cancellationToken) {
        List<FileOperation> operations = new ArrayList<>();
        Map<String, Long> requiredObjects = new LinkedHashMap<>();
        Set<String> forcedDirectories = target.manifest().forcedSyncDirectories().stream()
                .map(UpdatePlanner::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String directory : target.manifest().forcedSyncDirectories()) {
            ProtectedPathPolicy.validate(paths, directory);
        }

        for (ManifestFile file : target.manifest().files()) {
            cancellationToken.throwIfCancelled();
            ProtectedPathPolicy.validate(paths, file.path());
            if (overrides.excludes(file)) {
                listener.onProgress(new ProgressEvent(UpdateStage.SCANNING,
                        "Keeping locally unmanaged file", file.path(), 0, 0));
                continue;
            }
            Path destination = resolve(paths, file.path());
            boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            if (exists && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                        "Managed file path is not a regular file: " + file.path());
            }

            boolean install;
            if (file.policy() == FilePolicy.DEFAULT) {
                install = !exists;
            } else {
                install = !matches(destination, file.sha256(), file.size());
            }
            if (install) {
                operations.add(new FileOperation(OperationKind.INSTALL, file.path(),
                        file.sha256(), file.size(), file.policy(), file.executable()));
                Long previousSize = requiredObjects.putIfAbsent(file.sha256(), file.size());
                if (previousSize != null && previousSize.longValue() != file.size()) {
                    throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                            "One object hash is declared with conflicting sizes");
                }
            }
            listener.onProgress(new ProgressEvent(UpdateStage.SCANNING,
                    "Scanning managed files", file.path(), 0, 0));
        }

        if (local != null) {
            Set<String> desired = new HashSet<>();
            target.manifest().files().forEach(file -> desired.add(fold(file.path())));
            Map<String, ManifestFile> oldManifestFiles = new HashMap<>();
            local.release().manifest().files().forEach(file ->
                    oldManifestFiles.put(fold(file.path()), file));
            for (InstalledFileState old : local.installation().files()) {
                cancellationToken.throwIfCancelled();
                if (old.policy() == FilePolicy.ENFORCED && !desired.contains(fold(old.path()))) {
                    ManifestFile oldManifestFile = oldManifestFiles.get(fold(old.path()));
                    if (oldManifestFile != null && overrides.excludes(oldManifestFile)) {
                        continue;
                    }
                    if (insideForcedDirectory(old.path(), forcedDirectories)) {
                        continue;
                    }
                    ProtectedPathPolicy.validate(paths, old.path());
                    Path destination = resolve(paths, old.path());
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                            throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                                    "Removed managed path is not a regular file: " + old.path());
                        }
                        operations.add(new FileOperation(OperationKind.DELETE, old.path(),
                                old.sha256(), old.size(), old.policy(), false));
                    }
                }
            }
        }

        addForcedDirectoryArchives(paths, target, forcedDirectories, overrides, operations,
                cancellationToken, listener);

        operations.sort(Comparator.comparing(FileOperation::path));
        return new UpdatePlan(target, operations, requiredObjects,
                findUnmanagedMods(paths, target, forcedDirectories, operations));
    }

    private void addForcedDirectoryArchives(EnginePaths paths, SignedRelease release,
                                            Set<String> forcedDirectories,
                                            LocalFileOverrides overrides,
                                            List<FileOperation> operations,
                                            CancellationToken cancellationToken,
                                            ProgressListener listener) {
        if (forcedDirectories.isEmpty()) return;
        Set<String> desired = release.manifest().files().stream()
                .map(ManifestFile::path)
                .map(UpdatePlanner::fold)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> operationPaths = operations.stream()
                .map(FileOperation::path)
                .map(UpdatePlanner::fold)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        for (String directory : release.manifest().forcedSyncDirectories()) {
            cancellationToken.throwIfCancelled();
            Path root = resolve(paths, directory);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                        "Forced sync path is not a safe directory: " + directory);
            }
            try (var stream = Files.walk(root)) {
                for (Path path : stream.toList()) {
                    cancellationToken.throwIfCancelled();
                    if (path.equals(root)) continue;
                    String relative = paths.instanceRoot().relativize(path)
                            .toString().replace('\\', '/');
                    if (Files.isSymbolicLink(path)) {
                        throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                                "Forced sync directory contains a symbolic link: " + relative);
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                                "Forced sync directory contains an unsupported entry: " + relative);
                    }
                    String folded = fold(relative);
                    boolean locallyDisabled = overrides.excludesPath(relative)
                            || ModMetadataReader.read(path)
                            .map(metadata -> overrides.excludesComponentAtPath(
                                    metadata.componentId(), relative))
                            .orElse(false);
                    if (locallyDisabled) continue;
                    if (!desired.contains(folded) && operationPaths.add(folded)) {
                        operations.add(new FileOperation(OperationKind.ARCHIVE, relative,
                                null, Files.size(path), null, false));
                        listener.onProgress(new ProgressEvent(UpdateStage.SCANNING,
                                "Scanning forced sync directories", relative, 0, 0));
                    }
                }
            } catch (UpdateException e) {
                throw e;
            } catch (IOException e) {
                throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                        "Unable to scan forced sync directory " + directory, e);
            }
        }
    }

    private List<Path> findUnmanagedMods(EnginePaths paths, SignedRelease release,
                                         Set<String> forcedDirectories,
                                         List<FileOperation> operations) {
        Path mods = paths.instanceRoot().resolve("mods");
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(mods)) {
            return List.of();
        }
        Set<String> managed = new HashSet<>();
        release.manifest().files().stream()
                .map(ManifestFile::path)
                .filter(path -> fold(path).startsWith("mods/"))
                .forEach(path -> managed.add(fold(path)));
        operations.stream()
                .filter(operation -> operation.kind() == OperationKind.DELETE
                        || operation.kind() == OperationKind.ARCHIVE)
                .map(FileOperation::path)
                .map(UpdatePlanner::fold)
                .forEach(managed::add);
        try (var stream = Files.walk(mods)) {
            return stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(path -> paths.instanceRoot().relativize(path))
                    .filter(path -> !managed.contains(fold(path.toString().replace('\\', '/'))))
                    .filter(path -> !insideForcedDirectory(
                            path.toString().replace('\\', '/'), forcedDirectories))
                    .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to scan local mods directory", e);
        }
    }

    private static boolean insideForcedDirectory(String path, Set<String> directories) {
        String folded = fold(path);
        for (String directory : directories) {
            if (folded.startsWith(directory + "/")) return true;
        }
        return false;
    }

    private boolean matches(Path path, String sha256, long size) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            return Files.size(path) == size && CryptoSupport.sha256(path).equals(sha256);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to inspect managed file " + path, e);
        }
    }

    private Path resolve(EnginePaths paths, String manifestPath) {
        try {
            return PathSafety.resolveInside(paths.instanceRoot(), manifestPath);
        } catch (IOException | ProtocolException e) {
            throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                    "Unsafe managed path: " + manifestPath, e);
        }
    }

    private static String fold(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
