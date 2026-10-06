package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SignedDocumentTest {
    @Test
    void preservesTheExactPayloadBytesAndSignatureInOneDocument() {
        byte[] payload = "{\r\n  \"标题\": \"原始字节\"\r\n}\n".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new SignedDocument(payload, "YWJjZA==").encode();
        SignedDocument decoded = SignedDocument.decode(encoded, payload.length);
        assertArrayEquals(payload, decoded.payload());
        assertEquals("YWJjZA==", decoded.signature());
        assertThrows(ProtocolException.class, () -> SignedDocument.decode(encoded, payload.length - 1));
    }

    @Test
    void rejectsAmbiguousHeadersAndUnknownVersions() {
        assertThrows(ProtocolException.class, () -> new SignedDocument(new byte[0], "YWJj\nZA==").encode());
        assertThrows(ProtocolException.class, () -> SignedDocument.decode(
                "DFS-SIGNED-2\nYWJjZA==\n{}".getBytes(StandardCharsets.US_ASCII), 20));
        assertThrows(ProtocolException.class, () -> SignedDocument.decode(
                "DFS-SIGNED-1\nYWJjZA==".getBytes(StandardCharsets.US_ASCII), 20));
    }
}
