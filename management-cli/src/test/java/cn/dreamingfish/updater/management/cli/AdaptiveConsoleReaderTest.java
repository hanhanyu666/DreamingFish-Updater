package cn.dreamingfish.updater.management.cli;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveConsoleReaderTest {
    @Test
    void readsUtf8ConsoleInput() throws Exception {
        assertReads("删除信雅互联残留文件", StandardCharsets.UTF_8);
    }

    @Test
    void fallsBackToTheWindowsNativeCodePage() throws Exception {
        assertReads("删除信雅互联残留文件", Charset.forName("GBK"));
    }

    private static void assertReads(String expected, Charset encoding)
            throws Exception {
        byte[] bytes = (expected + "\r\n").getBytes(encoding);
        try (BufferedReader reader = new BufferedReader(
                new AdaptiveConsoleReader(
                        new ByteArrayInputStream(bytes),
                        Charset.forName("GBK")))) {
            assertEquals(expected, reader.readLine());
        }
    }
}
