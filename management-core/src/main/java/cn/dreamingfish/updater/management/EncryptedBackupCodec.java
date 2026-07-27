package cn.dreamingfish.updater.management;

import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

final class EncryptedBackupCodec {
    private static final byte[] MAGIC = "DFSBACKUP1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final int MEMORY_KIB = 64 * 1024;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 32;
    private static final int HEADER_LENGTH = MAGIC.length + Integer.BYTES * 4 + SALT_LENGTH + IV_LENGTH;

    private final SecureRandom random = new SecureRandom();

    void encrypt(Path plaintext, Path encrypted, char[] password) {
        requirePassword(password);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(iv);
        byte[] header = header(salt, iv);
        byte[] key = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(encrypted,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
                 InputStream input = new BufferedInputStream(Files.newInputStream(plaintext))) {
                raw.write(header);
                try (CipherOutputStream output = new CipherOutputStream(raw, cipher)) {
                    input.transferTo(output);
                }
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new ManagementException("Unable to encrypt management backup", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    void decrypt(Path encrypted, Path plaintext, char[] password) {
        requirePassword(password);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(encrypted))) {
            byte[] header = file.readNBytes(HEADER_LENGTH);
            if (header.length != HEADER_LENGTH) {
                throw new ManagementException("Backup archive is truncated");
            }
            Header parsed = parseHeader(header);
            byte[] key = derive(password, parsed.salt(), parsed.memoryKiB(),
                    parsed.iterations(), parsed.parallelism());
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, parsed.iv()));
                cipher.updateAAD(header);
                try (CipherInputStream input = new CipherInputStream(file, cipher);
                     OutputStream output = new BufferedOutputStream(Files.newOutputStream(plaintext,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                    input.transferTo(output);
                } catch (IOException e) {
                    Files.deleteIfExists(plaintext);
                    throw new ManagementException("Wrong backup password or damaged archive", e);
                }
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new ManagementException("Unable to initialize backup decryption", e);
        } catch (IOException e) {
            throw new ManagementException("Unable to decrypt management backup", e);
        }
    }

    private byte[] header(byte[] salt, byte[] iv) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_LENGTH);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeInt(MEMORY_KIB);
                output.writeInt(ITERATIONS);
                output.writeInt(PARALLELISM);
                output.write(salt);
                output.write(iv);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private Header parseHeader(byte[] header) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(header))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new ManagementException("Not a DreamingFish management backup");
            }
            int version = input.readInt();
            int memory = input.readInt();
            int iterations = input.readInt();
            int parallelism = input.readInt();
            if (version != FORMAT_VERSION || memory < 8192 || memory > 1024 * 1024
                    || iterations < 1 || iterations > 20 || parallelism < 1 || parallelism > 32) {
                throw new ManagementException("Unsupported or unsafe backup encryption parameters");
            }
            byte[] salt = input.readNBytes(SALT_LENGTH);
            byte[] iv = input.readNBytes(IV_LENGTH);
            return new Header(memory, iterations, parallelism, salt, iv);
        } catch (IOException e) {
            throw new ManagementException("Invalid backup header", e);
        }
    }

    private byte[] derive(char[] password, byte[] salt, int memory, int iterations, int parallelism) {
        byte[] passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password);
        byte[] key = new byte[KEY_LENGTH];
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(memory)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            generator.generateBytes(passwordBytes, key);
            return key;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 10) {
            throw new ManagementException("Backup password must contain at least 10 characters");
        }
    }

    private record Header(int memoryKiB, int iterations, int parallelism, byte[] salt, byte[] iv) {
    }
}
