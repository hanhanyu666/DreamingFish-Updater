package cn.dreamingfish.updater.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record PlayerProgramManifest(
        int schemaVersion,
        String projectId,
        String platform,
        String version,
        Instant createdAt,
        String launchPath,
        String minimumBootstrapVersion,
        Set<String> requiredCapabilities,
        List<PlayerProgramFile> files
) {
    public PlayerProgramManifest {
        requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        files = files == null ? List.of() : List.copyOf(files);
    }
}
