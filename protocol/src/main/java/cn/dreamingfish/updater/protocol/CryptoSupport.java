package cn.dreamingfish.updater.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class CryptoSupport {
    private static final int BUFFER_SIZE = 128 * 1024;

    private CryptoSupport() {
    }

    public static KeyPair generateEd25519KeyPair() {
        try {
            return KeyPairGenerator.getInstance(ProtocolConstants.SIGNATURE_ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("Ed25519 is unavailable", e);
        }
    }

    public static byte[] sign(byte[] payload, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(ProtocolConstants.SIGNATURE_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(payload);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("Unable to sign payload", e);
        }
    }

    public static boolean verify(byte[] payload, byte[] signature, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance(ProtocolConstants.SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("Unable to verify payload", e);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance(ProtocolConstants.SIGNATURE_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(bytes));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ProtocolException("Invalid Ed25519 public key", e);
        }
    }

    public static PrivateKey decodePrivateKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance(ProtocolConstants.SIGNATURE_ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ProtocolException("Invalid Ed25519 private key", e);
        }
    }

    public static String sha256(byte[] bytes) {
        MessageDigest digest = newDigest();
        return Hex.encode(digest.digest(bytes));
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return Hex.encode(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ProtocolConstants.HASH_ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("SHA-256 is unavailable", e);
        }
    }
}
