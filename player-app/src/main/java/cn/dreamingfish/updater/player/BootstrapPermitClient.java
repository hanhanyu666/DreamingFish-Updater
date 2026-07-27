package cn.dreamingfish.updater.player;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

final class BootstrapPermitClient {
    private final PlayerArguments arguments;
    private final AtomicBoolean completed = new AtomicBoolean();
    private Socket sessionSocket;
    private BufferedReader sessionReader;
    private BufferedWriter sessionWriter;

    BootstrapPermitClient(PlayerArguments arguments) {
        this.arguments = arguments;
    }

    boolean isConnectedLaunch() {
        return !arguments.preview();
    }

    synchronized void ready() throws IOException {
        if (arguments.preview() || completed.get() || sessionSocket != null) return;
        Socket socket = connect();
        try {
            socket.setSoTimeout(10_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.US_ASCII));
            write(writer, "DFS1 READY " + arguments.bootstrapToken());
            if (!"DFS1 READY".equals(reader.readLine())) {
                throw new PermitException("Minecraft 启动许可通道未接受玩家端连接");
            }
            socket.setSoTimeout(0);
            sessionSocket = socket;
            sessionReader = reader;
            sessionWriter = writer;
        } catch (PermitException e) {
            close(socket);
            throw e;
        } catch (IOException e) {
            close(socket);
            throw new PermitException("无法连接 Minecraft 启动许可通道，请返回启动器重新启动游戏", e);
        }
    }

    synchronized void allow() throws IOException {
        if (arguments.preview() || !completed.compareAndSet(false, true)) return;
        try {
            if (sessionSocket != null) {
                write(sessionWriter, "DFS1 ALLOW " + arguments.bootstrapToken());
                sessionSocket.setSoTimeout(10_000);
                if (!"DFS1 LOCKED".equals(sessionReader.readLine())) {
                    throw new PermitException("Minecraft 启动许可通道未确认游戏锁");
                }
            } else {
                try (Socket socket = connect()) {
                    write(socket, "DFS1 ALLOW " + arguments.bootstrapToken());
                    socket.setSoTimeout(10_000);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII))) {
                        if (!"DFS1 LOCKED".equals(reader.readLine())) {
                            throw new PermitException("Minecraft 启动许可通道未确认游戏锁");
                        }
                    }
                }
            }
        } catch (IOException e) {
            completed.set(false);
            if (e instanceof PermitException) throw e;
            throw new PermitException("Minecraft 启动许可通道已关闭，请返回启动器重新启动游戏", e);
        } finally {
            closeSession();
        }
    }

    synchronized void deny(String reason) {
        if (arguments.preview() || !completed.compareAndSet(false, true)) return;
        String safeReason = reason == null || reason.isBlank()
                ? "Player updater denied launch"
                : reason.substring(0, Math.min(reason.length(), 1024));
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(safeReason.getBytes(StandardCharsets.UTF_8));
        try {
            String line = "DFS1 DENY " + arguments.bootstrapToken() + " " + encoded;
            if (sessionSocket != null) {
                write(sessionWriter, line);
            } else {
                try (Socket socket = connect()) {
                    write(socket, line);
                }
            }
        } catch (IOException ignored) {
        } finally {
            closeSession();
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ipv4Loopback(),
                arguments.bootstrapPort()), 5000);
        return socket;
    }

    private static InetAddress ipv4Loopback() throws IOException {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    private static void write(Socket socket, String line) throws IOException {
        write(new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.US_ASCII)), line);
    }

    private static void write(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private void closeSession() {
        close(sessionSocket);
        sessionSocket = null;
        sessionReader = null;
        sessionWriter = null;
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    static final class PermitException extends IOException {
        PermitException(String message) {
            super(message);
        }

        PermitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
