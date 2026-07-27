package cn.dreamingfish.updater.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermitGateTest {
    @Test
    void ignoresWrongTokensAndAcknowledgesPermissionAfterLocking() throws Exception {
        PermitGate gate = new PermitGate("correct-token");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean locked = new AtomicBoolean();
        try {
            Future<PermitDecision> result = executor.submit(() -> gate.await(
                    new AliveProcess(), 5000, () -> locked.set(true)));

            send(gate.port(), "DFS1 ALLOW wrong-token\n", false);
            String response = send(gate.port(), "DFS1 ALLOW correct-token\n", true);

            assertEquals("DFS1 LOCKED", response);
            assertTrue(locked.get());
            assertTrue(result.get().allowed());
        } finally {
            gate.close();
            executor.shutdownNow();
        }
    }

    @Test
    void carriesAPlayerVisibleDenialReason() throws Exception {
        PermitGate gate = new PermitGate("deny-token");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean locked = new AtomicBoolean();
        try {
            Future<PermitDecision> result = executor.submit(() -> gate.await(
                    new AliveProcess(), 5000, () -> locked.set(true)));
            String reason = "The signed release is invalid";
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(reason.getBytes(StandardCharsets.UTF_8));
            send(gate.port(), "DFS1 DENY deny-token " + encoded + "\n", false);

            PermitDecision decision = result.get();
            assertFalse(decision.allowed());
            assertFalse(locked.get());
            assertEquals(reason, decision.reason());
        } finally {
            gate.close();
            executor.shutdownNow();
        }
    }

    @Test
    void followsAReadySessionAfterTheNativeLauncherHandsOff() throws Exception {
        PermitGate gate = new PermitGate("handoff-token", 5000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean locked = new AtomicBoolean();
        try {
            Future<PermitDecision> result = executor.submit(() -> gate.await(
                    new DeadProcess(), 5000, () -> locked.set(true)));
            Socket socket = new Socket(ipv4Loopback(), gate.port());
            try {
                socket.getOutputStream().write(
                        "DFS1 READY handoff-token\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertEquals("DFS1 READY", readLine(socket));
                socket.getOutputStream().write(
                        "DFS1 ALLOW handoff-token\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertEquals("DFS1 LOCKED", readLine(socket));
            } finally {
                socket.close();
            }

            assertTrue(result.get().allowed());
            assertTrue(locked.get());
        } finally {
            gate.close();
            executor.shutdownNow();
        }
    }

    private static String send(int port, String line, boolean readResponse) throws IOException {
        Socket socket = new Socket(ipv4Loopback(), port);
        try {
            socket.getOutputStream().write(line.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            if (!readResponse) return "";
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) >= 0 && value != '\n') {
                if (value != '\r') response.write(value);
            }
            return new String(response.toByteArray(), StandardCharsets.US_ASCII);
        } finally {
            socket.close();
        }
    }

    private static String readLine(Socket socket) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int value;
        while ((value = socket.getInputStream().read()) >= 0 && value != '\n') {
            if (value != '\r') response.write(value);
        }
        return new String(response.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static InetAddress ipv4Loopback() throws IOException {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    private static final class AliveProcess extends Process {
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
        public int exitValue() {
            throw new IllegalThreadStateException();
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean isAlive() {
            return true;
        }
    }

    private static final class DeadProcess extends Process {
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
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
