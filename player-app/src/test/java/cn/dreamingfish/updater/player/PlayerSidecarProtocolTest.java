package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSidecarProtocolTest {
    private static final ObjectMapper TREES = new ObjectMapper();
    private static final JsonCodec JSON = new JsonCodec();

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
