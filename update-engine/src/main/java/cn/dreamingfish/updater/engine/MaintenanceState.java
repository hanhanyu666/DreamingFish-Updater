package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ManagedPaths;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-instance maintenance memory committed together with the verified
 * installation: which "首次提供" files were already initialized and which
 * one-time corrections were already applied.
 */
public record MaintenanceState(int schemaVersion, String projectId,
                               List<String> initializedPaths,
                               List<String> appliedCorrections) {
    public static final int SCHEMA_VERSION = 1;

    public MaintenanceState {
        initializedPaths = sortedUnique(initializedPaths, true);
        appliedCorrections = sortedUnique(appliedCorrections, false);
    }

    public static MaintenanceState empty(String projectId) {
        return new MaintenanceState(SCHEMA_VERSION, projectId, List.of(), List.of());
    }

    public boolean initialized(String path) {
        return initializedPaths.contains(ManagedPaths.fold(path));
    }

    public boolean correctionApplied(String id) {
        return appliedCorrections.contains(id);
    }

    public MaintenanceState with(Collection<String> newlyInitialized,
                                 Collection<String> newlyApplied) {
        if (newlyInitialized.isEmpty() && newlyApplied.isEmpty()) return this;
        Set<String> paths = new TreeSet<>(initializedPaths);
        newlyInitialized.forEach(path -> paths.add(ManagedPaths.fold(path)));
        Set<String> corrections = new TreeSet<>(appliedCorrections);
        corrections.addAll(newlyApplied);
        return new MaintenanceState(SCHEMA_VERSION, projectId, List.copyOf(paths),
                List.copyOf(corrections));
    }

    private static List<String> sortedUnique(List<String> values, boolean fold) {
        if (values == null || values.isEmpty()) return List.of();
        Set<String> result = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            result.add(fold ? ManagedPaths.fold(value) : value);
        }
        return List.copyOf(result);
    }
}
