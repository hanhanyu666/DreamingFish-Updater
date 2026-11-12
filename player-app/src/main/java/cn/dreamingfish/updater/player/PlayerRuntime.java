package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.UpdateErrorCode;
import cn.dreamingfish.updater.engine.UpdateException;

/** Version and launch-policy metadata shared by the Java sidecar and tests. */
public final class PlayerRuntime {
    public static final String VERSION = "0.3.5";
    public static final String BOOTSTRAP_AGENT_VERSION = "0.1.2";

    private PlayerRuntime() {
    }

    /** Read the installed Agent's build identity, never infer it from this player's version. */
    static String installedBootstrapVersion(java.nio.file.Path instance) {
        java.nio.file.Path agent = instance.resolve(".dreamingfish-bootstrap/bootstrap-agent.jar");
        try {
            cn.dreamingfish.updater.protocol.PathSafety.assertSafePathTree(agent);
            try (var jar = new java.util.jar.JarFile(agent.toFile())) {
                var entry = jar.getJarEntry("META-INF/dreamingfish/build/bootstrap-agent.json");
                if (entry == null || entry.getSize() > 64 * 1024) return "0.0.0";
                try (var input = jar.getInputStream(entry)) {
                    var identity = new cn.dreamingfish.updater.protocol.JsonCodec().read(input.readNBytes(64 * 1024 + 1), com.fasterxml.jackson.databind.JsonNode.class);
                    if (!"bootstrap-agent".equals(identity.path("component").asText())) return "0.0.0";
                    String version = identity.path("versions").path("bootstrap").asText();
                    cn.dreamingfish.updater.protocol.SemanticVersion.parse(version);
                    return version;
                }
            }
        } catch (Exception ignored) { return "0.0.0"; }
    }

    static boolean allowsUnverifiedOfflineLaunch(Throwable failure) {
        return failure instanceof UpdateException update
                && update.code() == UpdateErrorCode.NETWORK_UNAVAILABLE;
    }

    static boolean allowsLocalContentOverride(Throwable failure) {
        return failure instanceof UpdateException update
                && update.code() == UpdateErrorCode.LOCAL_CONTENT_CHANGED;
    }
}
