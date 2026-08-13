package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.PathSafety;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Manages physical files in a project's standard source directory. */
public final class SourceFileService {
    public static final long MAX_UPLOAD_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final DateTimeFormatter ARCHIVE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC);

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final ScanService scanner;

    public SourceFileService(ManagementPaths paths, ManagementDatabase database,
                             cn.dreamingfish.updater.protocol.JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.scanner = new ScanService(paths, database, json);
    }

    public List<SourceFileEntry> list(String projectId) {
        ProjectRecord project = database.requireProject(projectId);
        RuleSet ruleSet = new RuleSet(project.rules());
        Set<String> forcedFiles = folded(project.rules().forcedSyncFiles());
        Set<String> publishedFiles = latestPublishedFiles(projectId);
        List<SourceFileEntry> result = new ArrayList<>();
        try (var stream = Files.walk(project.sourceDirectory())) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(project.sourceDirectory())) continue;
                String relative = relative(project, path);
                if (Files.isSymbolicLink(path)) {
                    throw new ManagementException(
                            "Managed source path cannot be a symbolic link: " + relative);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                RuleSet.Decision decision = ruleSet.decide(relative);
                if (decision.excluded()) continue;
                boolean directoryForced = project.rules().forcedSyncDirectories().stream()
                        .anyMatch(directory -> insideDirectory(relative, directory));
                boolean fileForced = forcedFiles.contains(fold(relative));
                FilePolicy policy = directoryForced || fileForced
                        ? FilePolicy.ENFORCED : decision.policy();
                result.add(new SourceFileEntry(relative, Files.size(path),
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(),
                        policy, directoryForced, fileForced,
                        publishedFiles.contains(fold(relative))));
            }
        } catch (IOException e) {
            throw new ManagementException(
                    "Unable to read standard modpack directory " + project.sourceDirectory(), e);
        }
        result.sort(Comparator.comparing(SourceFileEntry::path));
        return List.copyOf(result);
    }

    /** Lists every real directory, including empty upload destinations. */
    public List<String> listDirectories(String projectId) {
        ProjectRecord project = database.requireProject(projectId);
        List<String> result = new ArrayList<>();
        try (var stream = Files.walk(project.sourceDirectory())) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(project.sourceDirectory())) continue;
                String relative = relative(project, path);
                if (Files.isSymbolicLink(path)) {
                    throw new ManagementException(
                            "Managed source path cannot be a symbolic link: " + relative);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.add(relative);
                }
            }
        } catch (IOException e) {
            throw new ManagementException(
                    "Unable to read standard modpack directories "
                            + project.sourceDirectory(), e);
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    /** Creates an empty upload destination below the standard source root. */
    public String createDirectory(String projectId, String relativePath) {
        ProjectRecord project = database.requireProject(projectId);
        String normalized = PathSafety.normalizeManifestPath(relativePath);
        ensureManageable(project, normalized + "/.dfs-directory-check");
        Path target = resolve(project, normalized);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)) {
                throw new ManagementException(
                        "A managed source directory already exists at " + normalized);
            }
            throw new ManagementException(
                    "A managed source file already exists at " + normalized);
        }
        try {
            Files.createDirectories(target);
            Path current = target;
            Path root = project.sourceDirectory().toAbsolutePath().normalize();
            while (current != null && !current.equals(root)
                    && current.startsWith(root)) {
                if (Files.isSymbolicLink(current)) {
                    throw new ManagementException(
                            "Managed source directory cannot contain symbolic links: "
                                    + normalized);
                }
                current = current.getParent();
            }
            return normalized;
        } catch (IOException e) {
            throw new ManagementException(
                    "Unable to create managed source directory: " + normalized, e);
        }
    }

    public SourceMutation importFile(String projectId, Path externalFile,
                                     String targetDirectory, boolean overwrite) {
        ProjectRecord project = database.requireProject(projectId);
        Path source = externalFile.toAbsolutePath().normalize();
        requireSafeRegularFile(source, "Imported source file");
        String fileName = source.getFileName().toString();
        String targetPath = targetDirectory == null || targetDirectory.isBlank()
                ? fileName
                : PathSafety.normalizeManifestPath(targetDirectory.trim()) + "/" + fileName;
        Path temporary = prepareTemporary(project, targetPath);
        try {
            BasicFileAttributes before = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            BasicFileAttributes after = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || Files.size(temporary) != after.size()
                    || !CryptoSupport.sha256(source).equals(CryptoSupport.sha256(temporary))) {
                throw new ManagementException("Imported source file changed while it was being copied");
            }
            return install(project, targetPath, temporary, overwrite);
        } catch (IOException e) {
            throw new ManagementException("Unable to import source file: " + source, e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    public SourceMutation upload(String projectId, String targetPath, InputStream input,
                                 long expectedBytes, boolean overwrite) {
        return upload(projectId, targetPath, input, expectedBytes, overwrite, true);
    }

    public SourceMutation upload(String projectId, String targetPath, InputStream input,
                                 long expectedBytes, boolean overwrite,
                                 boolean refreshPreview) {
        ProjectRecord project = database.requireProject(projectId);
        if (expectedBytes > MAX_UPLOAD_BYTES) {
            throw new ManagementException("Uploaded file exceeds the 4 GiB limit");
        }
        String normalized = PathSafety.normalizeManifestPath(targetPath);
        Path temporary = prepareTemporary(project, normalized);
        long copied = 0;
        try (OutputStream output = Files.newOutputStream(
                temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                copied += read;
                if (copied > MAX_UPLOAD_BYTES) {
                    throw new ManagementException("Uploaded file exceeds the 4 GiB limit");
                }
                output.write(buffer, 0, read);
            }
            if (expectedBytes >= 0 && copied != expectedBytes) {
                throw new ManagementException("Uploaded file length does not match the request");
            }
            return install(project, normalized, temporary, overwrite, refreshPreview);
        } catch (IOException e) {
            throw new ManagementException("Unable to store uploaded source file", e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    public SourceMutation remove(String projectId, String sourcePath,
                                 RemovalAction action) {
        SourceBatchMutation batch = removeBatch(projectId,
                List.of(new SourceRemoval(sourcePath, action)));
        RemovedSourceFile removed = batch.removed().getFirst();
        return new SourceMutation(
                removed.path(), removed.archivedPreviousFile(), batch.preview());
    }

    public SourceBatchMutation removeBatch(
            String projectId, List<SourceRemoval> removals) {
        if (removals == null || removals.isEmpty()) {
            throw new ManagementException("Choose at least one managed source file to remove");
        }
        ProjectRecord project = database.requireProject(projectId);
        Set<String> publishedFiles = latestPublishedFiles(projectId);
        Set<String> unique = new HashSet<>();
        List<PreparedRemoval> prepared = new ArrayList<>();
        for (SourceRemoval removal : removals) {
            if (removal == null) {
                throw new ManagementException("Source removal entry cannot be empty");
            }
            String normalized = PathSafety.normalizeManifestPath(removal.path());
            if (!unique.add(fold(normalized))) {
                throw new ManagementException(
                        "Managed source file was selected more than once: " + normalized);
            }
            boolean insideForcedDirectory = project.rules().forcedSyncDirectories().stream()
                    .anyMatch(directory -> insideDirectory(normalized, directory));
            if (insideForcedDirectory && removal.action() == RemovalAction.RELEASE) {
                throw new ManagementException(
                        "Files inside a forced sync directory cannot be released from management");
            }
            Path source = resolve(project, normalized);
            requireSafeRegularFile(source, "Managed source file");
            boolean published = publishedFiles.contains(fold(normalized));
            if (published && removal.action() == null) {
                throw new ManagementException(
                        "Choose whether players should delete or retain the removed file");
            }
            prepared.add(new PreparedRemoval(
                    normalized, source, removal.action(), published));
        }

        List<ArchivedRemoval> archived = new ArrayList<>();
        for (PreparedRemoval removal : prepared) {
            archived.add(new ArchivedRemoval(removal,
                    archive(project, removal.path(), removal.source())));
        }

        List<ArchivedRemoval> deleted = new ArrayList<>();
        try {
            for (ArchivedRemoval removal : archived) {
                Files.delete(removal.removal().source());
                deleted.add(removal);
            }
            for (ArchivedRemoval removal : deleted) {
                removeEmptyParents(project.sourceDirectory(),
                        removal.removal().source().getParent());
            }
            clearExactForcedFiles(project, unique);
        } catch (IOException | RuntimeException e) {
            for (int index = deleted.size() - 1; index >= 0; index--) {
                ArchivedRemoval removal = deleted.get(index);
                restoreArchive(removal.archive(), removal.removal().source(), e);
            }
            if (e instanceof ManagementException managementException) {
                throw managementException;
            }
            throw new ManagementException(
                    "Unable to remove managed source files", e);
        }

        PublishPreview preview = scanner.createPreview(projectId);
        List<RemovalDecision> decisions = prepared.stream()
                .filter(PreparedRemoval::published)
                .map(removal -> new RemovalDecision(
                        removal.path(), removal.action()))
                .toList();
        if (!decisions.isEmpty()) {
            preview = scanner.decideRemovals(projectId, decisions);
        }
        List<RemovedSourceFile> removed = archived.stream()
                .map(removal -> new RemovedSourceFile(
                        removal.removal().path(), removal.archive()))
                .toList();
        return new SourceBatchMutation(removed, preview);
    }

    private SourceMutation install(ProjectRecord project, String targetPath,
                                   Path temporary, boolean overwrite) {
        return install(project, targetPath, temporary, overwrite, true);
    }

    private SourceMutation install(ProjectRecord project, String targetPath,
                                   Path temporary, boolean overwrite,
                                   boolean refreshPreview) {
        String normalized = PathSafety.normalizeManifestPath(targetPath);
        ensureManageable(project, normalized);
        Path target = resolve(project, normalized);
        Path archived = null;
        try {
            Files.createDirectories(target.getParent());
            target = resolve(project, normalized);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeRegularFile(target, "Existing managed source file");
                if (!overwrite) {
                    throw new ManagementException(
                            "A source file already exists at " + normalized);
                }
                archived = archive(project, normalized, target);
                Files.delete(target);
            }
            AtomicFiles.moveReplace(temporary, target);
        } catch (IOException | RuntimeException e) {
            if (archived != null && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                restoreArchive(archived, target, e);
            }
            if (e instanceof ManagementException managementException) {
                throw managementException;
            }
            throw new ManagementException("Unable to install managed source file: " + normalized, e);
        }
        PublishPreview preview = refreshPreview
                ? scanner.createPreview(project.id()) : null;
        return new SourceMutation(normalized, archived, preview);
    }

    private Path prepareTemporary(ProjectRecord project, String targetPath) {
        String normalized = PathSafety.normalizeManifestPath(targetPath);
        ensureManageable(project, normalized);
        Path target = resolve(project, normalized);
        try {
            Files.createDirectories(target.getParent());
            resolve(project, normalized);
            return Files.createTempFile(target.getParent(), ".dfs-upload-", ".part");
        } catch (IOException e) {
            throw new ManagementException("Unable to prepare source file upload", e);
        }
    }

    private void ensureManageable(ProjectRecord project, String path) {
        RuleSet.Decision decision = new RuleSet(project.rules()).decide(path);
        if (decision.excluded()) {
            throw new ManagementException(
                    "The selected destination is excluded by project rules: " + path);
        }
    }

    private Path archive(ProjectRecord project, String relative, Path source) {
        String batch = ARCHIVE_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path root = paths.root().resolve("source-archive")
                .resolve(project.id()).resolve(batch);
        Path target;
        try {
            target = PathSafety.resolveInside(root, relative);
            AtomicFiles.copyReplace(source, target);
            if (Files.size(source) != Files.size(target)
                    || !CryptoSupport.sha256(source).equals(CryptoSupport.sha256(target))) {
                Files.deleteIfExists(target);
                throw new ManagementException("Source archive verification failed: " + relative);
            }
            return target;
        } catch (IOException e) {
            throw new ManagementException("Unable to archive source file before changing it: " + relative, e);
        }
    }

    private void clearExactForcedFiles(ProjectRecord project, Set<String> foldedPaths) {
        List<String> forced = project.rules().forcedSyncFiles().stream()
                .filter(candidate -> !foldedPaths.contains(fold(candidate)))
                .toList();
        if (forced.size() == project.rules().forcedSyncFiles().size()) return;
        ProjectRules rules = project.rules().withForcedSyncFiles(forced);
        database.updateProject(project.id(), project.displayName(),
                project.sourceDirectory(), project.publicBaseUrl(), project.branding(), rules);
    }

    private Set<String> latestPublishedFiles(String projectId) {
        return database.latestRelease(projectId)
                .map(database::readManifest)
                .map(manifest -> manifest.files().stream()
                        .map(ManifestFile::path)
                        .map(SourceFileService::fold)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
    }

    private static Set<String> folded(List<String> paths) {
        Set<String> result = new HashSet<>();
        paths.forEach(path -> result.add(fold(path)));
        return Set.copyOf(result);
    }

    private static String relative(ProjectRecord project, Path path) {
        return PathSafety.normalizeManifestPath(
                project.sourceDirectory().relativize(path)
                        .toString().replace('\\', '/'));
    }

    private static Path resolve(ProjectRecord project, String path) {
        try {
            return PathSafety.resolveInside(project.sourceDirectory(), path);
        } catch (IOException e) {
            throw new ManagementException("Unable to resolve managed source path: " + path, e);
        }
    }

    private static boolean insideDirectory(String path, String directory) {
        return fold(path).startsWith(fold(directory) + "/");
    }

    private static String fold(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static void requireSafeRegularFile(Path path, String label) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new ManagementException(label + " does not exist or is unsafe: " + path);
        }
    }

    private static void removeEmptyParents(Path root, Path current) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = current;
        while (candidate != null && !candidate.equals(normalizedRoot)
                && candidate.startsWith(normalizedRoot)) {
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                candidate = candidate.getParent();
                continue;
            }
            try (var children = Files.list(candidate)) {
                if (children.findAny().isPresent()) break;
            }
            Files.delete(candidate);
            candidate = candidate.getParent();
        }
    }

    private static void restoreArchive(Path archive, Path target, Throwable primary) {
        try {
            AtomicFiles.copyReplace(archive, target);
        } catch (IOException restoreFailure) {
            primary.addSuppressed(restoreFailure);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A stale temporary file is preferable to hiding the primary failure.
        }
    }

    public record SourceFileEntry(
            String path,
            long size,
            long lastModifiedMillis,
            FilePolicy policy,
            boolean forcedByDirectory,
            boolean forcedByFile,
            boolean published
    ) {
    }

    public record SourceMutation(
            String path,
            Path archivedPreviousFile,
            PublishPreview preview
    ) {
    }

    public record SourceRemoval(String path, RemovalAction action) {
    }

    public record RemovedSourceFile(String path, Path archivedPreviousFile) {
    }

    public record SourceBatchMutation(
            List<RemovedSourceFile> removed,
            PublishPreview preview
    ) {
        public SourceBatchMutation {
            removed = List.copyOf(removed);
        }
    }

    private record PreparedRemoval(
            String path,
            Path source,
            RemovalAction action,
            boolean published
    ) {
    }

    private record ArchivedRemoval(
            PreparedRemoval removal,
            Path archive
    ) {
    }
}
