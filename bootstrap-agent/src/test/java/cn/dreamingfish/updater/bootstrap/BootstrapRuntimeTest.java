package cn.dreamingfish.updater.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapRuntimeTest {
    @TempDir
    Path temporary;

    @Test
    void fallsBackWhenTheNewPlayerProgramExitsBeforeGrantingPermission() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path bootstrap = Files.createDirectories(instance.resolve(".dreamingfish-bootstrap"));
        Files.write(bootstrap.resolve("project-binding.json"),
                "{\"playerHome\":\"DreamingFishUpdater\"}".getBytes(StandardCharsets.UTF_8));
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        Path current = playerHome.resolve("app/0.2.0/player.exe");
        Path fallback = playerHome.resolve("app/0.1.0/player.exe");
        Files.createDirectories(current.getParent());
        Files.createDirectories(fallback.getParent());
        Files.write(current, new byte[]{1});
        Files.write(fallback, new byte[]{1});
        Path state = Files.createDirectories(playerHome.resolve("state"));
        Files.write(state.resolve("active-player.properties"), (
                "schema=1\n"
                        + "version=0.2.0\n"
                        + "launcher=app/0.2.0/player.exe\n"
                        + "fallbackVersion=0.1.0\n"
                        + "fallbackLauncher=app/0.1.0/player.exe\n"
                        + "timeoutSeconds=30\n").getBytes(StandardCharsets.UTF_8));

        FailingThenPermittingStarter starter = new FailingThenPermittingStarter();
        GameRunLock lock = new BootstrapRuntime(starter).run(instance);
        try {
            assertTrue(starter.permissionAcknowledged.await(5, TimeUnit.SECONDS));
            assertEquals(2, starter.launchers.size());
            assertEquals(current.toAbsolutePath().normalize().toString(), starter.launchers.get(0));
            assertEquals(fallback.toAbsolutePath().normalize().toString(), starter.launchers.get(1));
        } finally {
            lock.close();
        }
    }

    private static final class FailingThenPermittingStarter
            implements BootstrapRuntime.PlayerProcessStarter {
        private final List<String> launchers = new ArrayList<String>();
        private final CountDownLatch permissionAcknowledged = new CountDownLatch(1);

        @Override
        public Process start(List<String> command, Path workingDirectory) {
            launchers.add(command.get(0));
            if (launchers.size() == 1) return new DeadProcess();
            final int port = Integer.parseInt(valueAfter(command, "--bootstrap-port"));
            final String token = valueAfter(command, "--bootstrap-token");
            Thread sender = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket socket = new Socket(ipv4Loopback(), port);
                        try {
                            socket.getOutputStream().write(
                                    ("DFS1 ALLOW " + token + "\n").getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                            ByteArrayOutputStream response = new ByteArrayOutputStream();
                            int value;
                            while ((value = socket.getInputStream().read()) >= 0 && value != '\n') {
                                if (value != '\r') response.write(value);
                            }
                            if ("DFS1 LOCKED".equals(
                                    new String(response.toByteArray(), StandardCharsets.US_ASCII))) {
                                permissionAcknowledged.countDown();
                            }
                        } finally {
                            socket.close();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "bootstrap-runtime-test-permit");
            sender.setDaemon(true);
            sender.start();
            return new AliveProcess();
        }

        private static InetAddress ipv4Loopback() throws Exception {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        }

        private static String valueAfter(List<String> command, String option) {
            return command.get(command.indexOf(option) + 1);
        }
    }

    private abstract static class TestProcess extends Process {
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }

    private static final class DeadProcess extends TestProcess {
        @Override
        public int exitValue() {
            return 1;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    private static final class AliveProcess extends TestProcess {
        @Override
        public int exitValue() {
            throw new IllegalThreadStateException();
        }

        @Override
        public boolean isAlive() {
            return true;
        }
    }
}
