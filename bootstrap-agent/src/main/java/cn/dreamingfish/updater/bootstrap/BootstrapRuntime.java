package cn.dreamingfish.updater.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BootstrapRuntime {
    private final PlayerProcessStarter processStarter;
    private final ProgramVerifier programVerifier;
    private final long handoffGraceMillis;

    BootstrapRuntime() {
        this(new PlayerProcessStarter() {
            @Override
            public Process start(List<String> command, Path workingDirectory) throws IOException {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(workingDirectory.toFile());
                builder.inheritIO();
                return builder.start();
            }
        }, new ExternalProgramVerifier(), 15_000);
    }

    BootstrapRuntime(PlayerProcessStarter processStarter) {
        this(processStarter, new ProgramVerifier() {
            @Override
            public void verify(ActivePlayerConfig config, BootstrapBinding binding) {
                // Tests of process fallback inject a starter; integrity has dedicated tests.
            }
        }, 50);
    }

    BootstrapRuntime(PlayerProcessStarter processStarter, ProgramVerifier programVerifier) {
        this(processStarter, programVerifier, 50);
    }

    private BootstrapRuntime(PlayerProcessStarter processStarter, ProgramVerifier programVerifier,
                             long handoffGraceMillis) {
        this.processStarter = processStarter;
        this.programVerifier = programVerifier;
        this.handoffGraceMillis = handoffGraceMillis;
    }

    GameRunLock run(Path instanceRoot) throws BootstrapException {
        Path normalizedInstance = instanceRoot.toAbsolutePath().normalize();
        Path bindingFile = normalizedInstance.resolve(".dreamingfish-bootstrap/project-binding.json");
        BootstrapBinding binding = new BindingReader().read(bindingFile, normalizedInstance);
        Path playerHome = binding.playerHome();
        PermitGate gate = new PermitGate(handoffGraceMillis);
        MinecraftLaunchContext launchContext = MinecraftLaunchContext.capture();
        final GameRunLock[] acquired = new GameRunLock[1];
        try {
            ActivePlayerConfig config = ActivePlayerConfig.load(playerHome);
            Set<String> attempted = new HashSet<String>();
            BootstrapException lastFailure = null;
            for (int attempt = 0; attempt < 3 && config != null; attempt++) {
                if (!attempted.add(config.identity())) break;
                try {
                    programVerifier.verify(config, binding);
                } catch (BootstrapException e) {
                    lastFailure = e;
                    config = config.fallback();
                    continue;
                }
                Process process;
                try {
                    process = processStarter.start(config.command(
                            gate.port(), gate.token(), normalizedInstance, bindingFile, launchContext),
                            normalizedInstance);
                } catch (IOException e) {
                    lastFailure = new BootstrapException("Unable to start player updater " + config.identity(), e);
                    config = config.fallback();
                    continue;
                }
                try {
                    PermitDecision decision = gate.await(process, config.timeoutMillis(), new Runnable() {
                        @Override
                        public void run() {
                            try {
                                acquired[0] = GameRunLock.acquire(normalizedInstance);
                            } catch (BootstrapException e) {
                                throw new GameLockRuntimeException(e);
                            }
                        }
                    });
                    if (!decision.allowed()) {
                        throw new BootstrapException(decision.reason());
                    }
                    if (acquired[0] == null) {
                        throw new BootstrapException("Minecraft run marker was not acquired");
                    }
                    return acquired[0];
                } catch (PlayerUpdaterExitedException e) {
                    lastFailure = e;
                    ActivePlayerConfig reloaded = ActivePlayerConfig.load(playerHome);
                    if (!attempted.contains(reloaded.identity())) config = reloaded;
                    else config = config.fallback();
                }
            }
            throw lastFailure == null
                    ? new BootstrapException("No usable player updater program is configured")
                    : lastFailure;
        } catch (GameLockRuntimeException e) {
            throw e.cause;
        } finally {
            gate.close();
        }
    }

    interface PlayerProcessStarter {
        Process start(List<String> command, Path workingDirectory) throws IOException;
    }

    interface ProgramVerifier {
        void verify(ActivePlayerConfig config, BootstrapBinding binding) throws BootstrapException;
    }

    private static final class GameLockRuntimeException extends RuntimeException {
        private final BootstrapException cause;

        private GameLockRuntimeException(BootstrapException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
