package cn.dreamingfish.updater.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Planned changes plus what the plan deliberately left alone, so the player
 * can be told why.
 *
 * @param keptModifiedPaths      default configurations kept because the player changed them
 * @param skippedSelfManagedPaths updates not applied because the player manages the file personally
 * @param resetPaths             default configurations restored at the player's request
 * @param nextState              maintenance memory to commit with this plan
 */
record UpdatePlan(
        SignedRelease release,
        List<FileOperation> operations,
        Map<String, Long> requiredObjects,
        List<Path> unmanagedMods,
        List<Path> releasedPaths,
        List<Path> keptModifiedPaths,
        List<Path> skippedSelfManagedPaths,
        List<Path> resetPaths,
        MaintenanceState nextState,
        boolean stateChanged
) {
    UpdatePlan {
        operations = List.copyOf(operations);
        requiredObjects = Map.copyOf(requiredObjects);
        unmanagedMods = List.copyOf(unmanagedMods);
        releasedPaths = List.copyOf(releasedPaths);
        keptModifiedPaths = List.copyOf(keptModifiedPaths);
        skippedSelfManagedPaths = List.copyOf(skippedSelfManagedPaths);
        resetPaths = List.copyOf(resetPaths);
    }

    int installCount() {
        return (int) operations.stream().filter(operation -> operation.kind() == OperationKind.INSTALL).count();
    }

    int deleteCount() {
        return (int) operations.stream().filter(operation -> operation.kind() == OperationKind.DELETE).count();
    }

    int archiveCount() {
        return (int) operations.stream().filter(FileOperation::archivesLocalCopy).count();
    }

    List<Path> paths(OperationKind kind) {
        return operations.stream()
                .filter(operation -> operation.kind() == kind)
                .map(operation -> Path.of(operation.path()))
                .toList();
    }

    List<Path> paths(ArchiveReason reason) {
        return operations.stream()
                .filter(FileOperation::archivesLocalCopy)
                .filter(operation -> operation.reason() == reason)
                .map(operation -> Path.of(operation.path()))
                .toList();
    }

    /** Whether applying the plan changes nothing on disk or in local maintenance memory. */
    boolean idle() {
        return operations.isEmpty() && !stateChanged;
    }
}
