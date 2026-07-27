package cn.dreamingfish.updater.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class PermitGate implements Closeable {
    private static final int MAX_LINE_BYTES = 4096;
    private static final long DEFAULT_HANDOFF_GRACE_MILLIS = 15_000;
    private final ServerSocket server;
    private final String token;
    private final long handoffGraceMillis;

    PermitGate() throws BootstrapException {
        this(randomToken(), DEFAULT_HANDOFF_GRACE_MILLIS);
    }

    PermitGate(long handoffGraceMillis) throws BootstrapException {
        this(randomToken(), handoffGraceMillis);
    }

    PermitGate(String token) throws BootstrapException {
        this(token, DEFAULT_HANDOFF_GRACE_MILLIS);
    }

    PermitGate(String token, long handoffGraceMillis) throws BootstrapException {
        this.token = token;
        this.handoffGraceMillis = handoffGraceMillis;
        try {
            server = new ServerSocket();
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(ipv4Loopback(), 0), 8);
            server.setSoTimeout(500);
        } catch (IOException e) {
            throw new BootstrapException("Unable to open local launch-permission channel", e);
        }
    }

    int port() {
        return server.getLocalPort();
    }

    String token() {
        return token;
    }

    PermitDecision await(Process playerProcess, long timeoutMillis, Runnable beforeAcknowledge)
            throws BootstrapException {
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        long processExitObserved = -1;
        while (System.nanoTime() < deadline) {
            if (!playerProcess.isAlive()) {
                if (processExitObserved < 0) processExitObserved = System.nanoTime();
                long elapsedMillis = (System.nanoTime() - processExitObserved) / 1000000L;
                if (elapsedMillis >= handoffGraceMillis) throw new PlayerUpdaterExitedException();
            }
            Socket socket;
            try {
                socket = server.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                throw new BootstrapException("Launch-permission channel failed", e);
            }
            try {
                if (!socket.getInetAddress().isLoopbackAddress()) continue;
                socket.setSoTimeout(5000);
                String line = readAsciiLine(socket.getInputStream());
                if (isReady(line)) {
                    writeAsciiLine(socket.getOutputStream(), "DFS1 READY");
                    return awaitReadySession(socket, deadline, beforeAcknowledge);
                }
                PermitDecision decision = parse(line);
                if (decision == null) continue;
                return acknowledge(socket, decision, beforeAcknowledge);
            } catch (IOException e) {
                // Ignore malformed or abandoned local connections and keep waiting.
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw new BootstrapException("Timed out waiting for the player updater");
    }

    private PermitDecision awaitReadySession(Socket socket, long deadline,
                                             Runnable beforeAcknowledge)
            throws BootstrapException {
        try {
            socket.setSoTimeout(500);
            while (System.nanoTime() < deadline) {
                String line;
                try {
                    line = readAsciiLine(socket.getInputStream());
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (line == null) throw new PlayerUpdaterExitedException();
                PermitDecision decision = parse(line);
                if (decision != null) return acknowledge(socket, decision, beforeAcknowledge);
            }
            throw new BootstrapException("Timed out waiting for the player updater");
        } catch (PlayerUpdaterExitedException e) {
            throw e;
        } catch (IOException e) {
            throw new PlayerUpdaterExitedException();
        }
    }

    private PermitDecision acknowledge(Socket socket, PermitDecision decision,
                                       Runnable beforeAcknowledge) {
        if (decision.allowed()) {
            beforeAcknowledge.run();
            try {
                writeAsciiLine(socket.getOutputStream(), "DFS1 LOCKED");
            } catch (IOException ignored) {
                // Permission is committed once the game run lock is held.
            }
        }
        return decision;
    }

    private boolean isReady(String line) {
        if (line == null) return false;
        String[] parts = line.split(" ", 4);
        return parts.length == 3 && "DFS1".equals(parts[0]) && "READY".equals(parts[1])
                && tokenMatches(parts[2]);
    }

    private PermitDecision parse(String line) throws BootstrapException {
        if (line == null) return null;
        String[] parts = line.split(" ", 4);
        if (parts.length < 3 || !"DFS1".equals(parts[0])) return null;
        if (!tokenMatches(parts[2])) return null;
        if ("ALLOW".equals(parts[1]) && parts.length == 3) {
            return PermitDecision.allow();
        }
        if ("DENY".equals(parts[1])) {
            String reason = "Player updater denied launch";
            if (parts.length == 4) {
                try {
                    byte[] decoded = Base64.getUrlDecoder().decode(parts[3]);
                    if (decoded.length <= 2048) reason = new String(decoded, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return PermitDecision.deny(reason);
        }
        return null;
    }

    private boolean tokenMatches(String candidate) {
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeAsciiLine(OutputStream output, String line) throws IOException {
        output.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= MAX_LINE_BYTES) {
            int value = input.read();
            if (value < 0) return null;
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).trim();
            if (value != '\r') bytes.write(value);
        }
        return null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static InetAddress ipv4Loopback() throws IOException {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }
}
