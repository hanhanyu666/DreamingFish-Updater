package cn.dreamingfish.updater.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BindingReaderTest {
    @TempDir
    Path temporary;

    @Test
    void resolvesAPlayerHomeRelativeToTheInstance() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path binding = instance.resolve("project-binding.json");
        Files.write(binding, ("{\"schemaVersion\":1,\"playerHome\":\"DreamingFishUpdater\","
                + "\"projectId\":\"demo\"}").getBytes(StandardCharsets.UTF_8));

        assertEquals(instance.resolve("DreamingFishUpdater").toAbsolutePath().normalize(),
                new BindingReader().readPlayerHome(binding, instance));
    }

    @Test
    void rejectsDuplicatePlayerHomeFields() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("duplicate-instance"));
        Path binding = instance.resolve("project-binding.json");
        Files.write(binding, "{\"playerHome\":\"one\",\"playerHome\":\"two\"}"
                .getBytes(StandardCharsets.UTF_8));

        assertThrows(BootstrapException.class,
                () -> new BindingReader().readPlayerHome(binding, instance));
    }

    @Test
    void rejectsASymbolicLinkBindingWhenSupported() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("linked-instance"));
        Path actual = instance.resolve("actual-binding.json");
        Files.write(actual, ("{\"playerHome\":\"DreamingFishUpdater\","
                + "\"projectId\":\"demo\"}").getBytes(StandardCharsets.UTF_8));
        Path binding = instance.resolve("project-binding.json");
        try {
            Files.createSymbolicLink(binding, actual);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }

        assertThrows(BootstrapException.class,
                () -> new BindingReader().readPlayerHome(binding, instance));
    }
}
