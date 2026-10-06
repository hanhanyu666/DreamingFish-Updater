package cn.dreamingfish.updater.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One atomic static file containing the exact signed bytes and their signature. */
public record SignedDocument(byte[] payload, String signature) {
    private static final byte[] MAGIC = "DFS-SIGNED-1\n".getBytes(StandardCharsets.US_ASCII);
    public static final int MAX_OVERHEAD = 1024;

    public byte[] encode() {
        if (signature == null || !signature.matches("[A-Za-z0-9+/=]{1,512}")) {
            throw new ProtocolException("Invalid signed document signature");
        }
        byte[] header = ("DFS-SIGNED-1\n" + signature + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] result = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, result, header.length, payload.length);
        return result;
    }

    public static SignedDocument decode(byte[] bytes, int maximumPayload) {
        if (bytes.length > maximumPayload + MAX_OVERHEAD || bytes.length < MAGIC.length + 2) {
            throw new ProtocolException("Invalid signed document size");
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (bytes[index] != MAGIC[index]) throw new ProtocolException("Unsupported signed document format");
        }
        int end = MAGIC.length;
        while (end < bytes.length && end - MAGIC.length <= 512 && bytes[end] != '\n') end++;
        if (end == bytes.length || end - MAGIC.length > 512) throw new ProtocolException("Missing signed document signature");
        String signature = new String(bytes, MAGIC.length, end - MAGIC.length, StandardCharsets.US_ASCII);
        if (!signature.matches("[A-Za-z0-9+/=]{1,512}")) throw new ProtocolException("Malformed signed document signature");
        byte[] payload = Arrays.copyOfRange(bytes, end + 1, bytes.length);
        if (payload.length > maximumPayload) throw new ProtocolException("Signed document payload exceeds its limit");
        return new SignedDocument(payload, signature);
    }
}
