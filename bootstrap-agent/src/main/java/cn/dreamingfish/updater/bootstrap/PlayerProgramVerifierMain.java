package cn.dreamingfish.updater.bootstrap;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Isolated entry point used by the bootstrap Agent for JIT-compiled program verification. */
public final class PlayerProgramVerifierMain {
    private PlayerProgramVerifierMain() {
    }

    public static void main(String[] arguments) {
        try {
            if (arguments.length != 5) {
                throw new BootstrapException("Invalid player program verification arguments");
            }
            verify(Paths.get(arguments[0]), arguments[1], arguments[2],
                    arguments[3], arguments[4]);
        } catch (Exception e) {
            System.err.println("DreamingFish player program verification failed: " + e.getMessage());
            System.exit(2);
        }
    }

    static void verify(Path playerHome, String projectId, String publicKey,
                       String version, String manifestSha256) throws BootstrapException {
        Path normalizedHome = playerHome.toAbsolutePath().normalize();
        ActivePlayerConfig config = ActivePlayerConfig.load(normalizedHome);
        while (config != null && (!version.equals(config.version())
                || !manifestSha256.equals(config.manifestSha256()))) {
            config = config.fallback();
        }
        if (config == null) {
            throw new BootstrapException("The requested player program is no longer configured");
        }
        new PlayerProgramVerifier().verify(config,
                new BootstrapBinding(normalizedHome, projectId, publicKey));
    }
}
