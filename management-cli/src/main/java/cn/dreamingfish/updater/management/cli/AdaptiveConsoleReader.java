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
    private final Charset nativeCharset;
    private char[] pending = new char[0];
    private int pendingOffset;
    private boolean endOfInput;

    AdaptiveConsoleReader(InputStream input) {
        this(input, nativeCharset());
    }

    AdaptiveConsoleReader(InputStream input, Charset nativeCharset) {
        this.input = Objects.requireNonNull(input, "input");
        this.nativeCharset = Objects.requireNonNull(
                nativeCharset, "nativeCharset");
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
            return new String(bytes, nativeCharset);
        }
    }

    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException ignored) {
                // Fall through to the JVM default on unusual runtimes.
            }
        }
        return Charset.defaultCharset();
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
