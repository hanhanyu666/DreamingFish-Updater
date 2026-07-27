package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoSupportTest {
    @Test
    void signsAndVerifiesPayloads() {
        KeyPair keys = CryptoSupport.generateEd25519KeyPair();
        byte[] payload = "trusted release".getBytes(StandardCharsets.UTF_8);
        byte[] signature = CryptoSupport.sign(payload, keys.getPrivate());

        assertTrue(CryptoSupport.verify(payload, signature, keys.getPublic()));
        assertTrue(CryptoSupport.verify(
                payload,
                signature,
                CryptoSupport.decodePublicKey(CryptoSupport.encodePublicKey(keys.getPublic()))
        ));
        assertFalse(CryptoSupport.verify(
                "tampered release".getBytes(StandardCharsets.UTF_8),
                signature,
                keys.getPublic()
        ));
    }
}
