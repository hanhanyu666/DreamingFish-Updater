package cn.dreamingfish.updater.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the large file-hash pass after Java 8 has entered its normally JIT-compiled main phase. */
final class ExternalProgramVerifier implements BootstrapRuntime.ProgramVerifier {
    private static final long VERIFY_TIMEOUT_SECONDS = 120;

    @Override
    public void verify(ActivePlayerConfig config, BootstrapBinding binding)
            throws BootstrapException {
        Path java = javaExecutable();
        Path agent = agentJar();
        List<String> command = new ArrayList<String>();
        command.add(java.toString());
        command.add("-cp");
        command.add(agent.toString());
        command.add(PlayerProgramVerifierMain.class.getName());
        command.add(config.playerHome().toString());
        command.add(binding.projectId());
        command.add(binding.publicKey());
        command.add(config.version());
        command.add(config.manifestSha256());

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(config.playerHome().toFile())
                    .inheritIO()
                    .start();
        } catch (Exception e) {
            throw new BootstrapException("Unable to start player program verification", e);
        }
        try {
            if (!process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                throw new BootstrapException("Player program verification timed out");
            }
            if (process.exitValue() != 0) {
                throw new BootstrapException("Player program verification failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BootstrapException("Player program verification was interrupted", e);
        }
    }

    private static Path javaExecutable() throws BootstrapException {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", executable)
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(java)) {
            throw new BootstrapException("Unable to locate the Minecraft Java runtime: " + java);
        }
        return java;
    }

    private static Path agentJar() throws BootstrapException {
        try {
            Path agent = Paths.get(BootstrapAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(agent) || Files.isSymbolicLink(agent)) {
                throw new BootstrapException("Bootstrap Agent is not a regular JAR file");
            }
            return agent;
        } catch (BootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new BootstrapException("Unable to locate the Bootstrap Agent JAR", e);
        }
    }
}
