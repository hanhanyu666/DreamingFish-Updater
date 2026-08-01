package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSidecarProtocolTest {
    private static final ObjectMapper TREES = new ObjectMapper();
    private static final JsonCodec JSON = new JsonCodec();

    @Test
    void previewSidecarCanBeClosedThroughItsCommandChannel() throws Exception {
        Process process = new ProcessBuilder(
                javaExecutable(),
                "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                PlayerSidecarMain.class.getName(),
                "--preview")
                .start();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write("{\"command\":\"close\"}\n");
            writer.flush();
            assertTrue(process.waitFor(3, TimeUnit.SECONDS),
                    "preview sidecar did not process the close command");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void confirmationRepliesRemainReadableWhileControllerCommandWaits() throws Exception {
        CompletableFuture<Boolean> confirmation = new CompletableFuture<>();
        List<String> handled = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean eofCalled = new AtomicBoolean();
        List<RuntimeException> commandErrors = new ArrayList<>();
        String input = String.join("\n",
                "{\"command\":\"close\"}",
                "not-json",
                "{\"command\":\"confirm\",\"id\":42,\"accepted\":true}",
                "{\"command\":\"boom\"}",
                "{\"command\":\"retry\"}") + "\n";

        PlayerSidecarMain.runCommandLoop(
                new BufferedReader(new StringReader(input)),
                (id, accepted) -> {
                    if (id == 42) confirmation.complete(accepted);
                },
                command -> {
                    if ("close".equals(command.path("command").asText())) {
                        try {
                            assertTrue(confirmation.get(1, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            throw new AssertionError("confirmation reply was blocked by the command", e);
                        }
                    }
                    if ("boom".equals(command.path("command").asText())) {
                        throw new IllegalStateException("expected command failure");
                    }
                    handled.add(command.path("command").asText());
                },
                commandErrors::add,
                () -> eofCalled.set(true));

        assertEquals(List.of("close", "retry"), handled);
        assertEquals(1, commandErrors.size());
        assertTrue(eofCalled.get());
    }

    @Test
    void previewEmitsTheFullProtocolSequence() throws Exception {
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                javaExecutable(),
                "-Dfile.encoding=UTF-8",
                "-cp", classpath,
                PlayerSidecarMain.class.getName(),
                "--preview")
                .redirectErrorStream(false)
                .start();
        List<JsonNode> messages = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    messages.add(TREES.readTree(line));
                }
            } catch (Exception ignored) {
            }
        }, "sidecar-protocol-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && messages.stream().noneMatch(
                    message -> "launch-kept-open".equals(message.path("type").asText()))) {
                Thread.sleep(50);
            }
            List<JsonNode> snapshot = new ArrayList<>(messages);
            assertTrue(snapshot.size() >= 9,
                    "preview should emit identity, branding, background, logs, ready, progress x3, mods, files, result, history, countdown");
            assertEquals("identity", snapshot.get(0).path("type").asText());
            assertEquals("ready", findType(snapshot, "ready").path("type").asText());
            assertEquals("progress", findType(snapshot, "progress").path("type").asText());
            JsonNode result = findType(snapshot, "result");
            assertEquals("result", result.path("type").asText());
            assertEquals("UPDATED", result.path("result").path("outcome").asText());
            assertEquals("1.20.1-r12", result.path("result").path("displayVersion").asText());
            JsonNode keptOpen = findType(snapshot, "launch-kept-open");
            assertEquals("launch-kept-open", keptOpen.path("type").asText());
        } finally {
            process.destroyForcibly();
        }
    }

    private static JsonNode findType(List<JsonNode> messages, String type) {
        return messages.stream()
                .filter(message -> type.equals(message.path("type").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing message type " + type));
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
