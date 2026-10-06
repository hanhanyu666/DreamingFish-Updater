package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.OptionalGroup;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Player-owned choices. These values never leave the player's computer and
 * never contain server facts: whether a file is locked or belongs to an
 * optional group always comes from the signed release via {@link MaintenanceModel}.
 *
 * @param disabledComponentIds mods the player disabled (moved out of the game)
 * @param excludedPaths        files the player manages personally, plus disabled mods without metadata
 * @param excludedDirectories  directories the player manages personally
 * @param selfManagedComponentIds mods the player manages personally, by mod ID
 * @param groupChoices         explicit optional group switches; absent groups follow their default
 * @param resetRequests        default configuration files the player asked to restore
 */
public record LocalFileOverrides(
        Set<String> disabledComponentIds,
        Set<String> excludedPaths,
        Set<String> excludedDirectories,
        Set<String> selfManagedComponentIds,
        Map<String, Boolean> groupChoices,
        Set<String> resetRequests,
        Map<String, Set<String>> storedModHashes
) {
    public static final LocalFileOverrides NONE = new LocalFileOverrides(
            Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Set.of());

    public LocalFileOverrides(Set<String> disabledComponentIds, Set<String> excludedPaths) {
        this(disabledComponentIds, excludedPaths, Set.of(), Set.of(), Map.of(), Set.of());
    }

    public LocalFileOverrides(Set<String> disabledComponentIds, Set<String> excludedPaths,
                              Set<String> excludedDirectories) {
        this(disabledComponentIds, excludedPaths, excludedDirectories, Set.of(), Map.of(), Set.of());
    }

    public LocalFileOverrides(Set<String> disabledComponentIds, Set<String> excludedPaths,
                              Set<String> excludedDirectories, Set<String> selfManagedComponentIds,
                              Map<String, Boolean> groupChoices, Set<String> resetRequests) {
        this(disabledComponentIds, excludedPaths, excludedDirectories, selfManagedComponentIds,
                groupChoices, resetRequests, Map.of());
    }

    public LocalFileOverrides {
        disabledComponentIds = normalizeComponentIds(disabledComponentIds);
        excludedPaths = normalizePaths(excludedPaths, "Invalid locally excluded path");
        excludedDirectories = normalizePaths(
                excludedDirectories, "Invalid locally excluded directory");
        selfManagedComponentIds = normalizeComponentIds(selfManagedComponentIds);
        groupChoices = normalizeGroups(groupChoices);
        resetRequests = normalizePaths(resetRequests, "Invalid default restore request");
        Map<String, Set<String>> stored = new LinkedHashMap<>();
        if (storedModHashes != null) {
            for (var entry : storedModHashes.entrySet()) {
                normalizeComponentIds(Set.of(entry.getKey()));
                Set<String> hashes = Set.copyOf(entry.getValue());
                if (hashes.stream().anyMatch(hash -> !cn.dreamingfish.updater.protocol.Hex.isSha256(hash))) {
                    throw new IllegalArgumentException("Invalid stored mod content hash");
                }
                stored.put(fold(entry.getKey()), hashes);
            }
        }
        storedModHashes = Map.copyOf(stored);
    }

    /**
     * Whether the player's choices keep this published file out of maintenance.
     * Required files ignore every player choice; files of a switched-off
     * optional group are excluded as well.
     */
    public boolean excludes(ManifestFile file, MaintenanceModel model) {
        if (file == null || model.locked(file.path())) return false;
        if (excludes(file)) return true;
        return model.groupOf(file.path()).map(group -> !groupEnabled(group)).orElse(false);
    }

    /** Player choices only, without the release's locks and optional groups. */
    public boolean excludes(ManifestFile file) {
        if (file == null) return false;
        if (matchesPathRule(file.path())) return true;
        return ManagedPaths.isModJar(file.path()) && file.componentId() != null
                && (disabledComponentIds.contains(fold(file.componentId()))
                || selfManagedComponentIds.contains(fold(file.componentId())));
    }

    /** Whether the player manages this file personally (as opposed to disabling it). */
    public boolean selfManages(ManifestFile file) {
        if (file == null) return false;
        if (matchesPathRule(file.path())) return true;
        return file.componentId() != null
                && selfManagedComponentIds.contains(fold(file.componentId()));
    }

    public boolean excludesPath(String path) {
        return path != null && matchesPathRule(path);
    }

    public boolean excludesComponent(String componentId) {
        return componentId != null && (disabledComponentIds.contains(fold(componentId))
                || selfManagedComponentIds.contains(fold(componentId)));
    }

    public boolean selfManagesComponent(String componentId) {
        return componentId != null && selfManagedComponentIds.contains(fold(componentId));
    }

    public boolean groupEnabled(OptionalGroup group) {
        Boolean choice = groupChoices.get(group.id());
        return choice == null ? group.defaultInstall() : choice;
    }

    public boolean resetRequested(String path) {
        return path != null && resetRequests.contains(fold(path));
    }

    public LocalFileOverrides merge(LocalFileOverrides other) {
        if (other == null) return this;
        Map<String, Boolean> groups = new LinkedHashMap<>(groupChoices);
        groups.putAll(other.groupChoices);
        Map<String, Set<String>> stored = new LinkedHashMap<>(storedModHashes);
        other.storedModHashes.forEach((id, hashes) -> stored.merge(id, hashes, LocalFileOverrides::union));
        return new LocalFileOverrides(
                union(disabledComponentIds, other.disabledComponentIds),
                union(excludedPaths, other.excludedPaths),
                union(excludedDirectories, other.excludedDirectories),
                union(selfManagedComponentIds, other.selfManagedComponentIds),
                groups,
                union(resetRequests, other.resetRequests), stored);
    }

    public LocalFileOverrides withGroupChoices(Map<String, Boolean> choices) {
        return new LocalFileOverrides(disabledComponentIds, excludedPaths, excludedDirectories,
                selfManagedComponentIds, choices, resetRequests, storedModHashes);
    }

    public LocalFileOverrides withResetRequests(Collection<String> paths) {
        return new LocalFileOverrides(disabledComponentIds, excludedPaths, excludedDirectories,
                selfManagedComponentIds, groupChoices,
                paths == null ? Set.of() : new LinkedHashSet<>(paths), storedModHashes);
    }

    public LocalFileOverrides withStoredModHashes(Map<String, Set<String>> hashes) {
        return new LocalFileOverrides(disabledComponentIds, excludedPaths, excludedDirectories,
                selfManagedComponentIds, groupChoices, resetRequests, hashes);
    }

    public boolean storedProblemVersion(String componentId, Collection<String> hashes) {
        if (componentId == null) return false;
        return storedModHashes.getOrDefault(fold(componentId), Set.of()).stream().anyMatch(hashes::contains);
    }

    public boolean isEmpty() {
        return disabledComponentIds.isEmpty() && excludedPaths.isEmpty()
                && excludedDirectories.isEmpty() && selfManagedComponentIds.isEmpty()
                && groupChoices.isEmpty() && resetRequests.isEmpty();
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

    private static Map<String, Boolean> normalizeGroups(Map<String, Boolean> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z0-9][a-z0-9._-]{0,63}")
                    || entry.getValue() == null) {
                throw new IllegalArgumentException("Invalid optional group choice");
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(result);
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
        if (excludedPaths.contains(folded)) return true;
        for (String directory : excludedDirectories) {
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

    private static String fold(String value) {
        return ManagedPaths.fold(value);
    }
}
