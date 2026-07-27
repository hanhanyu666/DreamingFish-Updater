package cn.dreamingfish.updater.player;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BootstrapPermitClientTest {
    @Test
    void waitsForTheBootstrapRunMarkerAcknowledgement() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(ipv4Loopback(), 0));
            String token = "t".repeat(43);
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.US_ASCII))) {
                    String ready = reader.readLine();
                    socket.getOutputStream().write("DFS1 READY\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    String allow = reader.readLine();
                    socket.getOutputStream().write("DFS1 LOCKED\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return ready + "\n" + allow;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            PlayerArguments arguments = new PlayerArguments(false, server.getLocalPort(), token,
                    Path.of("instance"), Path.of("binding"), null, null, null, null);

            BootstrapPermitClient client = new BootstrapPermitClient(arguments);
            client.ready();
            client.allow();

            assertEquals("DFS1 READY " + token + "\nDFS1 ALLOW " + token, received.get());
        }
    }

    private static InetAddress ipv4Loopback() throws Exception {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }
}
