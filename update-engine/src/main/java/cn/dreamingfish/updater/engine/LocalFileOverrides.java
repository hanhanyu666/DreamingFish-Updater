package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;

import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** Local, player-owned exemptions. These values are never sent to the server. */
public record LocalFileOverrides(
        Set<String> disabledComponentIds,
        Set<String> excludedPaths,
        Set<String> excludedDirectories,
        Set<String> forcedDirectories
) {
    public static final LocalFileOverrides NONE = new LocalFileOverrides(
            Set.of(), Set.of(), Set.of(), Set.of());

    public LocalFileOverrides(Set<String> disabledComponentIds, Set<String> excludedPaths) {
        this(disabledComponentIds, excludedPaths, Set.of(), Set.of());
    }

    public LocalFileOverrides(Set<String> disabledComponentIds, Set<String> excludedPaths,
                              Set<String> excludedDirectories) {
        this(disabledComponentIds, excludedPaths, excludedDirectories, Set.of());
    }

    public LocalFileOverrides {
        disabledComponentIds = normalizeComponentIds(disabledComponentIds);
        excludedPaths = normalizePaths(excludedPaths, "Invalid locally excluded path");
        excludedDirectories = normalizePaths(
                excludedDirectories, "Invalid locally excluded directory");
        forcedDirectories = normalizePaths(forcedDirectories, "Invalid forced directory");
    }

    public boolean excludes(ManifestFile file) {
        if (file == null || isForced(file.path())) return false;
        if (matchesPathRule(file.path())) return true;
        return isModJar(file.path()) && file.componentId() != null
                && disabledComponentIds.contains(fold(file.componentId()));
    }

    public boolean excludesPath(String path) {
        return path != null && !isForced(path) && matchesPathRule(path);
    }

    public boolean excludesComponent(String componentId) {
        return componentId != null && disabledComponentIds.contains(fold(componentId));
    }

    public boolean excludesComponentAtPath(String componentId, String path) {
        return path != null && !isForced(path) && excludesComponent(componentId);
    }

    public boolean isForced(String path) {
        return path != null && matchesDirectory(path, forcedDirectories);
    }

    public LocalFileOverrides withForcedDirectories(Collection<String> directories) {
        Set<String> normalized = directories == null
                ? Set.of()
                : normalizePaths(new LinkedHashSet<>(directories), "Invalid forced directory");
        return new LocalFileOverrides(disabledComponentIds, excludedPaths,
                excludedDirectories, normalized);
    }

    public LocalFileOverrides merge(LocalFileOverrides other) {
        if (other == null || other.isEmpty()) return this;
        Set<String> components = union(disabledComponentIds, other.disabledComponentIds);
        Set<String> paths = union(excludedPaths, other.excludedPaths);
        Set<String> directories = union(excludedDirectories, other.excludedDirectories);
        Set<String> forced = union(forcedDirectories, other.forcedDirectories);
        return new LocalFileOverrides(components, paths, directories, forced);
    }

    public boolean isEmpty() {
        return disabledComponentIds.isEmpty() && excludedPaths.isEmpty()
                && excludedDirectories.isEmpty();
    }

    private static Set<String> normalizeComponentIds(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}")) {
                throw new IllegalArgumentException("Invalid disabled mod component ID");
            }
            result.add(fold(value));
        }
        return Set.copyOf(result);
    }

    private static Set<String> normalizePaths(Collection<String> values, String message) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            final String normalized;
            try {
                normalized = PathSafety.normalizeManifestPath(value);
            } catch (ProtocolException e) {
                throw new IllegalArgumentException(message, e);
            }
            result.add(fold(normalized));
        }
        return Set.copyOf(result);
    }

    private boolean matchesPathRule(String path) {
        String folded = fold(path);
        return excludedPaths.contains(folded)
                || matchesDirectory(folded, excludedDirectories);
    }

    private static boolean matchesDirectory(String path, Set<String> directories) {
        String folded = fold(path);
        for (String directory : directories) {
            if (folded.equals(directory) || folded.startsWith(directory + "/")) return true;
        }
        return false;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private static boolean isModJar(String path) {
        String folded = fold(path);
        return folded.startsWith("mods/") && folded.endsWith(".jar");
    }

    private static String fold(String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
