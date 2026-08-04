package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LocalFileManager {
    private static final int MAX_RULES = 10_000;

    record Snapshot(long revision, LocalFileOverrides overrides) {
    }

    private final Path preferencesFile;
    private final JsonCodec json = new JsonCodec();

    LocalFileManager(Path playerHome) {
        preferencesFile = playerHome.toAbsolutePath().normalize()
                .resolve("state/local-file-preferences.json");
    }

    synchronized Snapshot snapshot() throws IOException {
        LocalFilePreferences preferences = load();
        return new Snapshot(preferences.revision(), new LocalFileOverrides(
                Set.of(), new LinkedHashSet<>(preferences.excludedFiles()),
                new LinkedHashSet<>(preferences.excludedDirectories())));
    }

    synchronized List<LocalFileEntry> scan(ReleaseManifest release) throws IOException {
        LocalFilePreferences preferences = load();
        Map<String, String> excludedFiles = indexed(preferences.excludedFiles());
        Map<String, String> excludedDirectories = indexed(preferences.excludedDirectories());
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
            boolean forced = isForced(directory, release);
            int count = (int) manifestFiles.values().stream()
                    .filter(file -> inside(file.path(), directory))
                    .count();
            boolean partial = !direct && inherited == null && hasExcludedDescendant(
                    directory, excludedFiles.keySet(), excludedDirectories.keySet());
            entries.add(new LocalFileEntry(directory, fileName(directory), true,
                    direct, inherited, partial, count > 0, forced,
                    null, count));
        }

        Set<String> filePaths = new LinkedHashSet<>();
        manifestFiles.values().forEach(file -> filePaths.add(file.path()));
        preferences.excludedFiles().stream()
                .filter(path -> !directoryPaths.stream().anyMatch(
                        directory -> fold(directory).equals(fold(path))))
                .forEach(filePaths::add);
        for (String path : filePaths.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            ManifestFile file = manifestFiles.get(fold(path));
            String inherited = nearestExcludedAncestor(path, excludedDirectories, true);
            boolean forced = isForced(path, release);
            String name = file != null && file.displayName() != null
                    ? file.displayName() : fileName(path);
            entries.add(new LocalFileEntry(path, name, false,
                    excludedFiles.containsKey(fold(path)), inherited, false,
                    file != null, forced, file == null ? null : file.policy(), 0));
        }

        return entries.stream()
                .sorted(Comparator.comparing(LocalFileEntry::path,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized void setManaged(LocalFileEntry entry, boolean managed) throws IOException {
        if (entry == null || entry.forced()) {
            throw new IOException("Forced sync paths cannot be changed locally");
        }
        LocalFilePreferences current = load();
        Map<String, String> files = indexed(current.excludedFiles());
        Map<String, String> directories = indexed(current.excludedDirectories());
        String path = normalize(entry.path());
        boolean changed;
        if (entry.directory()) {
            changed = removeDescendants(files, path) | removeDescendants(directories, path);
            if (managed) {
                changed |= directories.remove(fold(path)) != null;
            } else {
                changed |= directories.put(fold(path), path) == null;
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
                current.revision() + 1, sorted(files.values()), sorted(directories.values())));
    }

    synchronized void restoreDefaults() throws IOException {
        LocalFilePreferences current = load();
        if (current.excludedFiles().isEmpty() && current.excludedDirectories().isEmpty()) return;
        save(new LocalFilePreferences(LocalFilePreferences.SCHEMA_VERSION,
                current.revision() + 1, List.of(), List.of()));
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
                > MAX_RULES) {
            throw new IOException("Unsupported local file preferences file");
        }
        validatePaths(preferences.excludedFiles(), "file");
        validatePaths(preferences.excludedDirectories(), "directory");
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
        int slash = includeParent ? candidate.lastIndexOf('/') : candidate.lastIndexOf('/');
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

    private static boolean isForced(String path, ReleaseManifest release) {
        return release != null && (release.forcedSyncFiles().stream()
                .anyMatch(file -> fold(path).equals(fold(file)))
                || release.forcedSyncDirectories().stream()
                .anyMatch(directory -> fold(path).equals(fold(directory))
                        || fold(path).startsWith(fold(directory) + "/")));
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

    private static List<String> sorted(java.util.Collection<String> paths) {
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
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
