package cn.dreamingfish.updater.management;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record ManagementPaths(
        Path root,
        Path database,
        Path objects,
        Path manifests,
        Path playerPrograms,
        Path keys,
        Path previews,
        Path locks,
        Path temporary
) {
    public static ManagementPaths at(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        return new ManagementPaths(
                normalized,
                normalized.resolve("management.db"),
                normalized.resolve("objects/sha256"),
                normalized.resolve("manifests"),
                normalized.resolve("player-programs"),
                normalized.resolve("keys"),
                normalized.resolve("previews"),
                normalized.resolve("locks"),
                normalized.resolve("temp")
        );
    }

    public void initialize() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(objects);
        Files.createDirectories(manifests);
        Files.createDirectories(playerPrograms);
        Files.createDirectories(keys);
        Files.createDirectories(previews);
        Files.createDirectories(locks);
        Files.createDirectories(temporary);
    }

    public Path objectPath(String sha256) {
        return objects.resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    public Path manifestDirectory(String projectId, String releaseId) {
        return manifests.resolve(projectId).resolve(releaseId);
    }

    public Path playerProgramDirectory(String projectId, String platform, String version) {
        return playerPrograms.resolve(projectId).resolve(platform).resolve("versions").resolve(version);
    }

    public Path playerProgramLatest(String projectId, String platform) {
        return playerPrograms.resolve(projectId).resolve(platform).resolve("latest");
    }
}
