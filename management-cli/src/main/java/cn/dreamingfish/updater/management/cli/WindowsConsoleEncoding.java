package cn.dreamingfish.updater.management.cli;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WindowsConsoleEncoding {
    private static final Pattern CODE_PAGE = Pattern.compile(
            "(?<!\\d)(\\d{3,5})(?!\\d)");

    private WindowsConsoleEncoding() {
    }

    static Charset outputCharset() {
        return selectOutputCharset(
                System.console() != null,
                System.getProperty("os.name", ""),
                System.getProperty("native.encoding"),
                readConsoleCodePage());
    }

    static Charset selectOutputCharset(
            boolean consoleAttached,
            String osName,
            String nativeEncoding,
            Integer consoleCodePage) {
        if (!consoleAttached || osName == null
                || !osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return StandardCharsets.UTF_8;
        }
        Charset codePage = charsetForCodePage(consoleCodePage);
        if (codePage != null) return codePage;
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (RuntimeException ignored) {
                // Fall back to UTF-8 if the JVM reports an invalid charset.
            }
        }
        return StandardCharsets.UTF_8;
    }

    static Integer parseCodePage(byte[] output) {
        if (output == null || output.length == 0) return null;
        Matcher matcher = CODE_PAGE.matcher(
                new String(output, StandardCharsets.ISO_8859_1));
        Integer result = null;
        while (matcher.find()) {
            try {
                result = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // Keep looking for another code page number.
            }
        }
        return result;
    }

    private static Charset charsetForCodePage(Integer codePage) {
        if (codePage == null) return null;
        if (codePage == 65001) return StandardCharsets.UTF_8;
        if (codePage == 936) return Charset.forName("GBK");
        if (codePage == 54936) return Charset.forName("GB18030");
        try {
            return Charset.forName("cp" + codePage);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer readConsoleCodePage() {
        if (!System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")
                || System.console() == null) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(
                    "cmd.exe", "/d", "/c", "chcp")
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            return exit == 0 ? parseCodePage(output) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException ignored) {
            return null;
        }
    }
}
