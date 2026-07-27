package cn.dreamingfish.updater.bootstrap;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BootstrapAgent {
    private static volatile GameRunLock gameRunLock;

    private BootstrapAgent() {
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        try {
            Path instanceRoot = locateInstanceRoot();
            gameRunLock = new BootstrapRuntime().run(instanceRoot);
            final GameRunLock heldLock = gameRunLock;
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    heldLock.close();
                }
            }, "dreamingfish-game-lock-release"));
        } catch (Exception e) {
            System.err.println("DreamingFish updater blocked Minecraft startup: " + e.getMessage());
            throw new IllegalStateException("DreamingFish updater did not grant launch permission", e);
        }
    }

    public static void agentmain(String agentArguments, Instrumentation instrumentation) {
        throw new UnsupportedOperationException("DreamingFish bootstrap must be loaded with -javaagent at startup");
    }

    private static Path locateInstanceRoot() throws BootstrapException {
        try {
            Path location = Paths.get(BootstrapAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path bootstrapDirectory = location.getParent();
            if (bootstrapDirectory == null
                    || !".dreamingfish-bootstrap".equals(bootstrapDirectory.getFileName().toString())) {
                throw new BootstrapException(
                        "bootstrap-agent.jar must remain in <instance>/.dreamingfish-bootstrap");
            }
            Path instance = bootstrapDirectory.getParent();
            if (instance == null) {
                throw new BootstrapException("Unable to locate the Minecraft instance directory");
            }
            return instance;
        } catch (BootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new BootstrapException("Unable to locate bootstrap-agent.jar", e);
        }
    }
}
