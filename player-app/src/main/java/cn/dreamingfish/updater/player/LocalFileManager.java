package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.LocalFileIndex;
import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.MaintenanceModel.Behavior;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.OptionalGroup;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The player's choice of which published files follow the owner and which they manage personally. */
final class LocalFileManager {
    private static final int MAX_RULES = 10_000;

    record Snapshot(long revision, LocalFileOverrides overrides) {
    }

    private final Path instanceRoot;
    private final Path playerHome;
    private final Path preferencesFile;
    private final JsonCodec json = new JsonCodec();

    LocalFileManager(Path playerHome) {
        this(null, playerHome);
    }

    LocalFileManager(Path instanceRoot, Path playerHome) {
        this.instanceRoot = instanceRoot == null ? null : instanceRoot.toAbsolutePath().normalize();
        this.playerHome = playerHome.toAbsolutePath().normalize();
        preferencesFile = this.playerHome.resolve("state/local-file-preferences.json");
    }

    synchronized Snapshot snapshot() throws IOException {
        LocalFilePreferences preferences = load();
        return new Snapshot(preferences.revision(), new LocalFileOverrides(
                Set.of(), new LinkedHashSet<>(preferences.excludedFiles()),
                new LinkedHashSet<>(preferences.excludedDirectories()),
                new LinkedHashSet<>(preferences.excludedComponents()), Map.of(),
                new LinkedHashSet<>(preferences.resetRequests())));
    }

    synchronized List<LocalFileEntry> scan(ReleaseManifest release) throws IOException {
        LocalFilePreferences preferences = load();
        MaintenanceModel model = release == null ? null : MaintenanceModel.of(release);
        Map<String, String> excludedFiles = indexed(preferences.excludedFiles());
        Map<String, String> excludedDirectories = indexed(preferences.excludedDirectories());
        Set<String> excludedComponents = folded(preferences.excludedComponents());
        Set<String> resets = folded(preferences.resetRequests());
        Map<String, ManifestFile> manifestFiles = new LinkedHashMap<>();
        Set<String> directoryPaths = new LinkedHashSet<>();

        if (release != null) {
            for (ManifestFile file : release.files()) {
                manifestFiles.put(fold(file.path()), file);
                addAncestors(directoryPaths, file.path());
            }
        }
        preferences.excludedDirectories().forEach(path -> {
            directoryPaths.add(path);
            addAncestors(directoryPaths, path);
        });
        preferences.excludedFiles().forEach(path -> addAncestors(directoryPaths, path));

        List<LocalFileEntry> entries = new ArrayList<>();
        List<String> sortedDirectories = directoryPaths.stream()
                .sorted(Comparator.comparingInt(LocalFileManager::depth)
                        .thenComparing(String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (String directory : sortedDirectories) {
            String folded = fold(directory);
            String inherited = nearestExcludedAncestor(directory, excludedDirectories, false);
            boolean direct = excludedDirectories.containsKey(folded);
            List<ManifestFile> inside = manifestFiles.values().stream()
                    .filter(file -> inside(file.path(), directory)).toList();
            boolean locked = model != null && !inside.isEmpty()
                    && inside.stream().allMatch(file -> model.locked(file.path()));
            boolean partial = !direct && inherited == null && hasExcludedDescendant(
                    directory, excludedFiles.keySet(), excludedDirectories.keySet());
            entries.add(new LocalFileEntry(directory, fileName(directory), true,
                    direct, inherited, partial, !inside.isEmpty(), locked, null, inside.size(),
                    null, null, null, locked ? "这个目录中的文件都由服主设为必需同步" : null,
                    null, false));
        }

        LocalFileIndex index = instanceRoot == null ? null
                : LocalFileIndex.load(playerHome.resolve("state/file-index.json"));
        Set<String> filePaths = new LinkedHashSet<>();
        manifestFiles.values().forEach(file -> filePaths.add(file.path()));
        preferences.excludedFiles().stream()
                .filter(path -> directoryPaths.stream().noneMatch(
                        directory -> fold(directory).equals(fold(path))))
                .forEach(filePaths::add);
        for (String path : filePaths.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            ManifestFile file = manifestFiles.get(fold(path));
            String inherited = nearestExcludedAncestor(path, excludedDirectories, true);
            String name = file != null && file.displayName() != null
                    ? file.displayName() : fileName(path);
            boolean componentExcluded = file != null && file.componentId() != null
                    && ManagedPaths.isModJar(file.path())
                    && excludedComponents.contains(fold(file.componentId()));
            Behavior behavior = model == null || file == null ? null : model.behaviorOf(file);
            OptionalGroup group = model == null ? null : model.groupOf(path).orElse(null);
            String lockReason = null;
            if (behavior == Behavior.REQUIRED) {
                lockReason = "服主设为必需同步，不能取消管理";
            } else if (group != null) {
                lockReason = "由可选内容“" + group.title() + "”统一开关";
            }
            Boolean modified = behavior == Behavior.DEFAULT_CONFIG
                    ? modified(file, index) : null;
            entries.add(new LocalFileEntry(path, name, false,
                    excludedFiles.containsKey(fold(path)) || componentExcluded, inherited, false,
                    file != null, lockReason != null, file == null ? null : file.policy(), 0,
                    file == null ? null : file.componentId(),
                    behavior == null ? null : behavior.name(),
                    group == null ? null : group.id(), lockReason, modified,
                    resets.contains(fold(path))));
        }

        return entries.stream()
                .sorted(Comparator.comparing(LocalFileEntry::path,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized void setManaged(LocalFileEntry entry, boolean managed) throws IOException {
        if (entry == null || entry.forced()) {
            throw new IOException(entry != null && entry.lockReason() != null
                    ? entry.lockReason() : "Forced sync paths cannot be changed locally");
        }
        LocalFilePreferences current = load();
        Map<String, String> files = indexed(current.excludedFiles());
        Map<String, String> directories = indexed(current.excludedDirectories());
        Map<String, String> components = indexed(current.excludedComponents());
        String path = normalize(entry.path());
        boolean changed;
        if (entry.directory()) {
            changed = removeDescendants(files, path) | removeDescendants(directories, path);
            if (managed) {
                changed |= directories.remove(fold(path)) != null;
            } else {
                changed |= directories.put(fold(path), path) == null;
            }
        } else if (entry.componentId() != null && ManagedPaths.isModJar(path)) {
            // Mods are self-managed by mod ID so the choice survives renamed updates.
            changed = files.remove(fold(path)) != null;
            if (managed) {
                changed |= components.remove(fold(entry.componentId())) != null;
            } else {
                if (entry.inheritedExclusion() != null) {
                    throw new IOException("A parent directory is already excluded locally");
                }
                changed |= components.put(fold(entry.componentId()), entry.componentId()) == null;
            }
        } else if (managed) {
            changed = files.remove(fold(path)) != null;
        } else {
            if (entry.inheritedExclusion() != null) {
                throw new IOException("A parent directory is already excluded locally");
            }
            changed = files.put(fold(path), path) == null;
        }
        if (!changed) return;
        save(new LocalFilePreferences(LocalFilePreferences.SCHEMA_VERSION,
                current.revision() + 1, sorted(files.values()), sorted(directories.values()),
                sorted(components.values()), current.resetRequests()));
    }

    /** Asks the next update to put the published default back, keeping the player's copy in the backup. */
    synchronized void requestReset(LocalFileEntry entry) throws IOException {
        if (entry == null || entry.directory() || (!Behavior.DEFAULT_CONFIG.name().equals(entry.preset())
                && !Behavior.INITIAL.name().equals(entry.preset()))) {
            throw new IOException("只有首次提供或旧版默认配置文件可以恢复默认");
        }
        LocalFilePreferences current = load();
        Map<String, String> resets = indexed(current.resetRequests());
        String path = normalize(entry.path());
        if (resets.put(fold(path), path) != null) return;
        save(new LocalFilePreferences(LocalFilePreferences.SCHEMA_VERSION, current.revision() + 1,
                current.excludedFiles(), current.excludedDirectories(), current.excludedComponents(),
                sorted(resets.values())));
    }

    /** Forgets restore requests the last successful update fulfilled. */
    synchronized void clearResetRequests(Collection<String> paths) throws IOException {
        if (paths == null || paths.isEmpty()) return;
        LocalFilePreferences current = load();
        Map<String, String> resets = indexed(current.resetRequests());
        boolean changed = false;
        for (String path : paths) changed |= resets.remove(fold(path)) != null;
        if (!changed) return;
        save(new LocalFilePreferences(LocalFilePreferences.SCHEMA_VERSION, current.revision(),
                current.excludedFiles(), current.excludedDirectories(), current.excludedComponents(),
                sorted(resets.values())));
    }

    synchronized void restoreDefaults() throws IOException {
        LocalFilePreferences current = load();
        if (current.excludedFiles().isEmpty() && current.excludedDirectories().isEmpty()
                && current.excludedComponents().isEmpty() && current.resetRequests().isEmpty()) {
            return;
        }
        save(new LocalFilePreferences(LocalFilePreferences.SCHEMA_VERSION,
                current.revision() + 1, List.of(), List.of(), List.of(), List.of()));
    }

    private Boolean modified(ManifestFile file, LocalFileIndex index) {
        if (index == null || instanceRoot == null) return null;
        try {
            Path local = PathSafety.resolveInside(instanceRoot, file.path());
            if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) return null;
            return !index.matches(local, file.path(), file.sha256(), file.size());
        } catch (IOException | ProtocolException e) {
            return null;
        }
    }

    private LocalFilePreferences load() throws IOException {
        if (!Files.exists(preferencesFile, LinkOption.NOFOLLOW_LINKS)) {
            return LocalFilePreferences.empty();
        }
        if (!Files.isRegularFile(preferencesFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(preferencesFile)) {
            throw new IOException("Local file preferences path is unsafe");
        }
        LocalFilePreferences preferences = json.read(preferencesFile, LocalFilePreferences.class);
        validate(preferences);
        return preferences;
    }

    private void validate(LocalFilePreferences preferences) throws IOException {
        if (preferences.schemaVersion() != LocalFilePreferences.SCHEMA_VERSION
                || preferences.revision() < 0
                || preferences.excludedFiles().size() + preferences.excludedDirectories().size()
                + preferences.excludedComponents().size() + preferences.resetRequests().size()
                > MAX_RULES) {
            throw new IOException("Unsupported local file preferences file");
        }
        validatePaths(preferences.excludedFiles(), "file");
        validatePaths(preferences.excludedDirectories(), "directory");
        validatePaths(preferences.resetRequests(), "reset");
        Set<String> unique = new LinkedHashSet<>();
        for (String component : preferences.excludedComponents()) {
            if (component == null || !component.matches("[A-Za-z0-9_.-]{1,128}")
                    || !unique.add(fold(component))) {
                throw new IOException("Invalid locally self-managed mod ID");
            }
        }
    }

    private static void validatePaths(List<String> paths, String kind) throws IOException {
        Set<String> unique = new LinkedHashSet<>();
        for (String path : paths) {
            String normalized = normalize(path);
            if (!path.equals(normalized) || !unique.add(fold(normalized))) {
                throw new IOException("Invalid locally excluded " + kind + " path");
            }
        }
    }

    private void save(LocalFilePreferences preferences) throws IOException {
        Files.createDirectories(preferencesFile.getParent());
        Path temporary = preferencesFile.resolveSibling(
                preferencesFile.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, json.writePretty(preferences),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveReplace(temporary, preferencesFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void addAncestors(Set<String> directories, String path) {
        int slash = path.lastIndexOf('/');
        while (slash > 0) {
            String directory = path.substring(0, slash);
            directories.add(directory);
            slash = directory.lastIndexOf('/');
        }
    }

    private static String nearestExcludedAncestor(String path,
                                                  Map<String, String> directories,
                                                  boolean includeParent) {
        String candidate = path;
        int slash = candidate.lastIndexOf('/');
        if (!includeParent && slash < 0) return null;
        while (slash > 0) {
            candidate = candidate.substring(0, slash);
            String stored = directories.get(fold(candidate));
            if (stored != null) return stored;
            slash = candidate.lastIndexOf('/');
        }
        return null;
    }

    private static boolean hasExcludedDescendant(String directory, Set<String> files,
                                                  Set<String> directories) {
        String prefix = fold(directory) + "/";
        return files.stream().anyMatch(path -> path.startsWith(prefix))
                || directories.stream().anyMatch(path -> path.startsWith(prefix));
    }

    private static boolean removeDescendants(Map<String, String> paths, String directory) {
        String folded = fold(directory);
        int before = paths.size();
        paths.keySet().removeIf(path -> path.equals(folded) || path.startsWith(folded + "/"));
        return paths.size() != before;
    }

    private static Map<String, String> indexed(List<String> paths) {
        Map<String, String> result = new LinkedHashMap<>();
        paths.forEach(path -> result.put(fold(path), path));
        return result;
    }

    private static Set<String> folded(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(fold(value)));
        return result;
    }

    private static boolean inside(String path, String directory) {
        return fold(path).startsWith(fold(directory) + "/");
    }

    private static int depth(String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static List<String> sorted(Collection<String> paths) {
        return paths.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String normalize(String path) throws IOException {
        try {
            return PathSafety.normalizeManifestPath(path);
        } catch (ProtocolException e) {
            throw new IOException("Invalid local file preference path", e);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String fold(String value) {
        return ManagedPaths.fold(value);
    }
}
