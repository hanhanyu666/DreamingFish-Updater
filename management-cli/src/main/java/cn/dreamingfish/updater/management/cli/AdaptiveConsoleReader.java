package cn.dreamingfish.updater.management.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads Windows console lines as UTF-8, with the native code page as a fallback.
 *
 * <p>Older Windows consoles can still emit GBK bytes after {@code chcp 65001}.
 * Decoding one complete line lets the CLI keep UTF-8 as its preferred boundary
 * without replacing valid Chinese input with U+FFFD.</p>
 */
final class AdaptiveConsoleReader extends Reader {
    private final InputStream input;
    private final List<Charset> fallbackCharsets;
    private char[] pending = new char[0];
    private int pendingOffset;
    private boolean endOfInput;

    AdaptiveConsoleReader(InputStream input) {
        this(input, fallbackCharsets().toArray(Charset[]::new));
    }

    AdaptiveConsoleReader(InputStream input, Charset... fallbackCharsets) {
        this.input = Objects.requireNonNull(input, "input");
        this.fallbackCharsets = List.of(
                Objects.requireNonNull(fallbackCharsets, "fallbackCharsets"));
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) return 0;
        if (pendingOffset >= pending.length && !readNextLine()) return -1;

        int count = Math.min(length, pending.length - pendingOffset);
        System.arraycopy(pending, pendingOffset, buffer, offset, count);
        pendingOffset += count;
        return count;
    }

    private boolean readNextLine() throws IOException {
        if (endOfInput) return false;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean terminated = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                endOfInput = true;
                break;
            }
            if (value == '\n') {
                terminated = true;
                break;
            }
            if (value != '\r') bytes.write(value);
        }
        if (bytes.size() == 0 && !terminated) return false;

        String line = decode(bytes.toByteArray());
        pending = (terminated ? line + "\n" : line).toCharArray();
        pendingOffset = 0;
        return true;
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            for (Charset charset : fallbackCharsets) {
                if (charset.equals(StandardCharsets.UTF_8)) continue;
                try {
                    return charset.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
                } catch (CharacterCodingException candidateRejected) {
                    // Try the next Windows console encoding candidate.
                }
            }
            Charset fallback = fallbackCharsets.isEmpty()
                    ? Charset.defaultCharset() : fallbackCharsets.getFirst();
            return new String(bytes, fallback);
        }
    }

    private static List<Charset> fallbackCharsets() {
        List<Charset> result = new ArrayList<>();
        addConfigured(result, System.getProperty("native.encoding"));
        addConfigured(result, System.getProperty("sun.jnu.encoding"));
        if (System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            addConfigured(result, "GB18030");
        }
        if (result.isEmpty()) result.add(Charset.defaultCharset());
        return List.copyOf(result);
    }

    private static void addConfigured(List<Charset> result, String name) {
        if (name == null || name.isBlank()) return;
        try {
            Charset charset = Charset.forName(name);
            if (!result.contains(charset)) result.add(charset);
        } catch (RuntimeException ignored) {
            // Ignore invalid JVM encoding properties and keep other candidates.
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
