package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ProtocolException;

import java.nio.file.Path;
import java.util.Locale;

final class ProtectedPathPolicy {
    private static final String BOOTSTRAP = ".dreamingfish-bootstrap";

    private ProtectedPathPolicy() {
    }

    static void validate(EnginePaths paths, String manifestPath) {
        String folded = manifestPath.toLowerCase(Locale.ROOT);
        if (isInside(folded, BOOTSTRAP)) {
            throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                    "Release attempts to manage protected bootstrap path: " + manifestPath);
        }

        Path instance = paths.instanceRoot();
        Path playerHome = paths.playerHome();
        if (playerHome.startsWith(instance) && !playerHome.equals(instance)) {
            String relative = instance.relativize(playerHome).toString()
                    .replace('\\', '/').toLowerCase(Locale.ROOT);
            if (isInside(folded, relative)) {
                throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                        "Release attempts to manage the player updater directory: " + manifestPath);
            }
        }
    }

    private static boolean isInside(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
