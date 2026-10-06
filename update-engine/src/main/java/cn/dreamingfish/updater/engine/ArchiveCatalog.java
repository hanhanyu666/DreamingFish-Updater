package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lists and restores the player backups that updates create. Files the
 * update engine moved out of the game are never deleted automatically; the
 * player decides whether to restore or discard them.
 */
public final class ArchiveCatalog {
    private static final int MAX_ARCHIVES = 500;

    private final Path instanceRoot;
    private final EnginePaths paths;
    private final JsonCodec json = new JsonCodec();

    public ArchiveCatalog(Path instanceRoot, Path playerHome) {
        this.instanceRoot = instanceRoot.toAbsolutePath().normalize();
        this.paths = EnginePaths.of(instanceRoot, playerHome);
    }

    /** One player backup, newest archives first in {@link #list()}. */
    public record Archive(String id, boolean legacy, Instant createdAt, String releaseId,
                          String displayVersion, List<ArchivedFile> files, Path directory) {
        public Archive {
            files = List.copyOf(files);
        }

        public long totalBytes() {
            return files.stream().mapToLong(ArchivedFile::size).sum();
        }
    }

    /** Whether a backed-up file can go back into the game, and why not. */
    public record RestoreCheck(boolean allowed, String reason) {
        static RestoreCheck ok() {
            return new RestoreCheck(true, null);
        }

        static RestoreCheck refused(String reason) {
            return new RestoreCheck(false, reason);
        }
    }

    public List<Archive> list() {
        List<Archive> archives = new ArrayList<>();
        collect(paths.archiveBackups(), false, archives);
        collect(paths.forcedSyncBackups(), true, archives);
        archives.sort(Comparator.comparing(Archive::createdAt).reversed());
        return archives.size() > MAX_ARCHIVES ? List.copyOf(archives.subList(0, MAX_ARCHIVES))
                : List.copyOf(archives);
    }

    public Optional<Archive> find(String archiveId) {
        return list().stream().filter(archive -> archive.id().equals(archiveId)).findFirst();
    }

    /**
     * Decides with the installed release whether restoring would survive the
     * next update. Content the owner withdrew, extra files in cleanup
     * directories, synchronized files and duplicate mods would be moved out
     * again, so they are refused with an explanation instead.
     */
    public RestoreCheck check(String archiveId, String originalPath, ReleaseManifest installed,
                              LocalFileOverrides choices) {
        Archive archive = find(archiveId).orElse(null);
        if (archive == null) return RestoreCheck.refused("这份备份已不存在");
        ArchivedFile file = entry(archive, originalPath).orElse(null);
        if (file == null) return RestoreCheck.refused("备份中没有这个文件");
        if (file.restoredAt() != null) return RestoreCheck.refused("这个文件已经恢复过");
        Path stored;
        Path destination;
        try {
            stored = PathSafety.resolveInside(archive.directory(), file.storedPath());
            destination = PathSafety.resolveInside(instanceRoot, file.originalPath());
            ProtectedPathPolicy.validate(paths, file.originalPath());
        } catch (IOException | ProtocolException | UpdateException e) {
            return RestoreCheck.refused("备份中的路径不安全");
        }
        if (!Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS)) {
            return RestoreCheck.refused("备份文件已被移走或删除");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return RestoreCheck.refused("游戏目录中已有同名文件；请先移走它再恢复");
        }
        if (installed == null) return RestoreCheck.ok();
        LocalFileOverrides player = choices == null ? LocalFileOverrides.NONE : choices;
        MaintenanceModel model = MaintenanceModel.of(installed);
        String hash = file.sha256();
        try {
            if (hash == null || hash.isBlank()) hash = CryptoSupport.sha256(stored);
        } catch (IOException e) {
            return RestoreCheck.refused("无法读取备份文件");
        }
        var withdrawal = model.withdrawalFor(file.originalPath(), hash, file.componentId(), file.version());
        if (withdrawal.isPresent()) {
            String reason = withdrawal.get().withdrawal().reason();
            return RestoreCheck.refused((withdrawal.get().withdrawal().removal()
                    ? "服主已持续移除这个文件" : "服主已撤回这个版本")
                    + (reason.isBlank() ? "" : "：" + reason));
        }
        Optional<ManifestFile> published = model.file(file.originalPath());
        if (published.isEmpty() && model.insideCleanupDirectory(file.originalPath())) {
            return RestoreCheck.refused("这个目录由服主统一管理，恢复后会在下次启动时被再次移走");
        }
        if (published.isPresent() && !player.excludes(published.get(), model)
                && model.behaviorOf(published.get()).convergesContent()
                && !published.get().sha256().equals(hash)) {
            return RestoreCheck.refused("这个文件由服主同步管理，恢复后会被服主版本覆盖；"
                    + "如需保留自己的版本，请先在“本地文件”里把它设为自行管理");
        }
        if (file.componentId() != null && ManagedPaths.isModJar(file.originalPath())) {
            for (ManifestFile mod : model.filesForComponent(file.componentId())) {
                if (!mod.path().equalsIgnoreCase(file.originalPath()) && !player.excludes(mod, model)) {
                    return RestoreCheck.refused("整合包已经包含同一个模组（" + mod.path()
                            + "），恢复后会造成重复");
                }
            }
        }
        return RestoreCheck.ok();
    }

    /** Copies a backed-up file back into the game and records the restore in the archive index. */
    public void restore(String archiveId, String originalPath, ReleaseManifest installed,
                        LocalFileOverrides choices) throws IOException {
        RestoreCheck check = check(archiveId, originalPath, installed, choices);
        if (!check.allowed()) throw new IOException(check.reason());
        Archive archive = find(archiveId).orElseThrow(() -> new IOException("这份备份已不存在"));
        ArchivedFile file = entry(archive, originalPath).orElseThrow(() -> new IOException("备份中没有这个文件"));
        Path stored = PathSafety.resolveInside(archive.directory(), file.storedPath());
        Path destination = PathSafety.resolveInside(instanceRoot, file.originalPath());
        AtomicFileSupport.copyReplace(stored, destination);
        if (!archive.legacy()) {
            List<ArchivedFile> updated = archive.files().stream()
                    .map(entry -> entry.originalPath().equals(file.originalPath())
                            ? entry.restored(Instant.now()) : entry)
                    .toList();
            ArchiveIndex index = readIndex(archive.directory());
            AtomicFileSupport.write(archive.directory().resolve(ArchiveIndex.FILE_NAME),
                    json.writePretty(index.withFiles(updated)));
        }
    }

    /** Permanently removes one player backup at the player's request. */
    public void delete(String archiveId) throws IOException {
        Archive archive = find(archiveId).orElseThrow(() -> new IOException("这份备份已不存在"));
        Path root = archive.legacy() ? paths.forcedSyncBackups() : paths.archiveBackups();
        Path directory = archive.directory();
        if (!directory.getParent().equals(root)) throw new IOException("备份目录位置异常");
        PathSafety.assertSafePathTree(directory);
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("备份目录包含链接，已停止删除");
                Files.deleteIfExists(path);
            }
        }
    }

    private void collect(Path root, boolean legacy, List<Archive> archives) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return;
        try (var entries = Files.list(root)) {
            for (Path directory : entries.toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(directory)) continue;
                try {
                    archives.add(legacy ? readLegacy(directory) : readCurrent(directory));
                } catch (IOException | RuntimeException unreadable) {
                    // A damaged index never hides the stored files; they stay on disk.
                }
            }
        } catch (IOException ignored) {
            // Missing or unreadable backup roots simply have no archives to show.
        }
    }

    private Archive readCurrent(Path directory) throws IOException {
        ArchiveIndex index = readIndex(directory);
        return new Archive(directory.getFileName().toString(), false,
                index.createdAt() == null ? creationTime(directory) : index.createdAt(),
                index.releaseId(), index.displayVersion(), index.files(), directory);
    }

    private ArchiveIndex readIndex(Path directory) throws IOException {
        Path file = directory.resolve(ArchiveIndex.FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing archive index");
        }
        ArchiveIndex index = json.read(file, ArchiveIndex.class);
        if (index.schemaVersion() != ArchiveIndex.SCHEMA_VERSION) {
            throw new IOException("Unsupported archive index");
        }
        for (ArchivedFile entry : index.files()) {
            PathSafety.normalizeManifestPath(entry.originalPath());
            PathSafety.normalizeManifestPath(entry.storedPath());
        }
        return index;
    }

    /** Forced sync archives from earlier player versions only list their files. */
    private Archive readLegacy(Path directory) throws IOException {
        List<ArchivedFile> files = new ArrayList<>();
        Path list = directory.resolve("archived-files.txt");
        String releaseId = null;
        String displayVersion = null;
        if (Files.isRegularFile(list, LinkOption.NOFOLLOW_LINKS)) {
            boolean body = false;
            for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                if (!body) {
                    if (line.startsWith("Release: ")) {
                        String release = line.substring("Release: ".length());
                        int open = release.lastIndexOf(" (");
                        if (open > 0 && release.endsWith(")")) {
                            displayVersion = release.substring(0, open);
                            releaseId = release.substring(open + 2, release.length() - 1);
                        }
                    }
                    if (line.isBlank()) body = true;
                    continue;
                }
                if (line.isBlank()) continue;
                try {
                    String path = PathSafety.normalizeManifestPath(line.trim());
                    Path stored = PathSafety.resolveInside(directory, path);
                    long size = Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS) ? Files.size(stored) : 0;
                    files.add(new ArchivedFile(path, path, null, size, ArchiveReason.LEGACY, "",
                            null, null, null));
                } catch (ProtocolException ignored) {
                    // Skip lines that are not file paths.
                }
            }
        }
        return new Archive(directory.getFileName().toString(), true, creationTime(directory),
                releaseId, displayVersion, files, directory);
    }

    private static Optional<ArchivedFile> entry(Archive archive, String originalPath) {
        return archive.files().stream()
                .filter(file -> file.originalPath().equalsIgnoreCase(originalPath))
                .findFirst();
    }

    private static Instant creationTime(Path directory) throws IOException {
        return Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .creationTime().toInstant();
    }
}
