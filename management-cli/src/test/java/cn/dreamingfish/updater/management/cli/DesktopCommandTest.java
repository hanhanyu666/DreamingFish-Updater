package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.protocol.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DesktopCommandTest {
    @TempDir Path temporary;

    @Test
    void desktopUsesAnAvailableLoopbackPortWithoutChangingSavedWebSettings() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("中文管理目录"));
        Path settings = home.resolve("management-settings.json");
        try (ServerSocket occupied = new ServerSocket(0)) {
            new ManagementSettingsStore(settings).save(new ManagementSettings(3,
                    home.resolve("data").toString(), "", "127.0.0.1", 8080, occupied.getLocalPort(), "0.0.0.0"));
            byte[] before = Files.readAllBytes(settings);
            StringWriter output = new StringWriter(), errors = new StringWriter();
            int result = ManagementCli.execute(new String[]{"desktop"}, new StringReader("{\"command\":\"shutdown\"}\n"),
                    settings, new PrintWriter(output, true), new PrintWriter(errors, true));
            assertEquals(0, result, errors.toString());
            String[] lines = output.toString().strip().split("\\R");
            Map<?, ?> ready = new JsonCodec().read(lines[0].getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.class);
            assertEquals("ready", ready.get("type"));
            assertEquals("0.2.3", ready.get("version"));
            int port = ((Number)ready.get("port")).intValue();
            assertNotEquals(occupied.getLocalPort(), port);
            assertEquals("http://127.0.0.1:" + port + "/", ready.get("url"));
            assertTrue(ready.get("home").toString().contains("中文管理目录"));
            assertTrue(lines[1].contains("stopped"));
            assertArrayEquals(before, Files.readAllBytes(settings));
            assertThrows(java.io.IOException.class, () -> new Socket("127.0.0.1", port));
        }
    }

    @Test
    void closedOwnerInputStopsTheServiceAndReleasesItsDirectoryLock() throws Exception {
        Path settings = temporary.resolve("management-settings.json");
        for (int i = 0; i < 2; i++) {
            StringWriter output = new StringWriter();
            int result = ManagementCli.execute(new String[]{"desktop"}, new StringReader(""), settings,
                    new PrintWriter(output, true), new PrintWriter(new StringWriter(), true));
            assertEquals(0, result);
            assertTrue(output.toString().contains("ready"));
        }
    }

    @Test
    void controlProtocolAcceptsJsonWhitespaceAndRejectsInvalidCommands() {
        StringWriter output = new StringWriter(), errors = new StringWriter();
        int result = ManagementCli.execute(new String[]{"desktop"},
                new StringReader("not-json\n{\"command\":\"erase\"}\n{ \"command\" : \"shutdown\" }\n"),
                temporary.resolve("management-settings.json"), new PrintWriter(output, true), new PrintWriter(errors, true));
        assertEquals(0, result, errors.toString());
        String[] lines = output.toString().strip().split("\\R");
        assertEquals(4, lines.length);
        assertTrue(lines[1].contains("error"));
        assertTrue(lines[2].contains("error"));
        assertTrue(lines[3].contains("stopped"));
    }

    @Test
    void anotherDesktopOwnerCannotStartOnTheSameDirectory() throws Exception {
        try (FileChannel channel = FileChannel.open(temporary.resolve("desktop-backend.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            StringWriter errors = new StringWriter();
            int result = ManagementCli.execute(new String[]{"desktop"}, new StringReader(""),
                    temporary.resolve("management-settings.json"), new PrintWriter(new StringWriter(), true), new PrintWriter(errors, true));
            assertNotEquals(0, result);
            assertTrue(errors.toString().contains("另一个桌面管理端"));
        }
    }
}
