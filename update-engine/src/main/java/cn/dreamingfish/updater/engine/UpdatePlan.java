package cn.dreamingfish.updater.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record UpdatePlan(
        SignedRelease release,
        List<FileOperation> operations,
        Map<String, Long> requiredObjects,
        List<Path> unmanagedMods
) {
    UpdatePlan {
        operations = List.copyOf(operations);
        requiredObjects = Map.copyOf(requiredObjects);
        unmanagedMods = List.copyOf(unmanagedMods);
    }

    int installCount() {
        return (int) operations.stream().filter(operation -> operation.kind() == OperationKind.INSTALL).count();
    }

    int deleteCount() {
        return (int) operations.stream().filter(operation -> operation.kind() == OperationKind.DELETE).count();
    }

    int archiveCount() {
        return (int) operations.stream().filter(operation -> operation.kind() == OperationKind.ARCHIVE).count();
    }
}
