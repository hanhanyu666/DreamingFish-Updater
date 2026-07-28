package cn.dreamingfish.updater.management.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsConsoleEncodingTest {
    @Test
    void usesGbKForAChineseWindowsConsole() {
        Charset selected = WindowsConsoleEncoding.selectOutputCharset(
                true, "Windows 10", "UTF-8", 936);

        assertEquals(Charset.forName("GBK"), selected);
    }

    @Test
    void usesUtf8ForAWindowsUtf8Console() {
        Charset selected = WindowsConsoleEncoding.selectOutputCharset(
                true, "Windows Server 2022", "GBK", 65001);

        assertEquals(StandardCharsets.UTF_8, selected);
    }

    @Test
    void keepsRedirectedAndNonWindowsOutputUtf8() {
        assertEquals(StandardCharsets.UTF_8,
                WindowsConsoleEncoding.selectOutputCharset(
                        false, "Windows 10", "GBK", 936));
        assertEquals(StandardCharsets.UTF_8,
                WindowsConsoleEncoding.selectOutputCharset(
                        true, "Linux", "UTF-8", 936));
    }

    @Test
    void readsLocalizedChcpOutputWithoutDecodingItsText() {
        byte[] output = "Active code page: 936\r\n"
                .getBytes(StandardCharsets.US_ASCII);

        assertEquals(936, WindowsConsoleEncoding.parseCodePage(output));
    }
}
