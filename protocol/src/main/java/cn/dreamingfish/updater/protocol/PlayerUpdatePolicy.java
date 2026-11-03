package cn.dreamingfish.updater.protocol;

import java.time.Instant;
import java.util.Map;

/** Project authorization, independent of the official release catalogue. */
public record PlayerUpdatePolicy(int schemaVersion, String projectId, long revision,
                                 boolean enabled, Map<String, Target> targets, Instant updatedAt) {
    public PlayerUpdatePolicy {
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    public void validate(String expectedProject) {
        if (schemaVersion != 1 || !expectedProject.equals(projectId) || revision < 0
                || updatedAt == null || targets.size() > 16) {
            throw new ProtocolException("Invalid player update policy identity");
        }
        targets.forEach((platform, target) -> {
            if (!platform.matches("[a-z0-9][a-z0-9._-]{0,63}") || target == null
                    || target.manifestSha256() == null || !target.manifestSha256().matches("[0-9a-f]{64}")) {
                throw new ProtocolException("Invalid approved player program");
            }
            SemanticVersion.parse(target.version());
        });
    }

    public record Target(String version, String manifestSha256) {}
}
