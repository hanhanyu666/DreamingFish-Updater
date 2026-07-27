package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.Hex;
import cn.dreamingfish.updater.protocol.ProtocolConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ObjectStore {
    private static final int BUFFER_SIZE = 128 * 1024;

    private final ManagementPaths paths;

    public ObjectStore(ManagementPaths paths) {
        this.paths = paths;
    }

    public ObjectInfo importFile(Path source) {
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)) {
                throw new ManagementException("Object source is not a safe regular file: " + source);
            }
            long size = Files.size(source);
            String sha256 = CryptoSupport.sha256(source);
            importExpected(source, sha256, size);
            return new ObjectInfo(sha256, size, paths.objectPath(sha256));
        } catch (IOException e) {
            throw new ManagementException("Unable to import object " + source, e);
        }
    }

    ManagementPaths paths() {
        return paths;
    }

    public Path importExpected(Path source, String expectedSha256, long expectedSize) {
        if (!Hex.isSha256(expectedSha256) || expectedSize < 0) {
            throw new ManagementException("Invalid expected object metadata");
        }
        Path target = paths.objectPath(expectedSha256);
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)) {
                throw new ManagementException("Object source is not a safe regular file: " + source);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                verify(target, expectedSha256, expectedSize);
                return target;
            }

            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(paths.temporary(), "object-", ".part");
            boolean moved = false;
            try {
                MessageDigest digest = MessageDigest.getInstance(ProtocolConstants.HASH_ALGORITHM);
                long copied = 0;
                try (InputStream input = Files.newInputStream(source);
                     OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            output.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            copied += read;
                        }
                    }
                }
                String actualHash = Hex.encode(digest.digest());
                if (copied != expectedSize || !actualHash.equals(expectedSha256)) {
                    throw new ManagementException("Source file changed while importing: " + source);
                }
                AtomicFiles.moveReplace(temporary, target);
                moved = true;
                return target;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ManagementException("Unable to import object " + source, e);
        }
    }

    public Path require(String sha256) {
        if (!Hex.isSha256(sha256)) {
            throw new ManagementException("Invalid object hash");
        }
        Path path = paths.objectPath(sha256);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new ManagementException("Missing content object: " + sha256);
        }
        return path;
    }

    public void verify(Path path, String expectedSha256, long expectedSize) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                || Files.size(path) != expectedSize
                || !CryptoSupport.sha256(path).equals(expectedSha256)) {
            throw new ManagementException("Content object is corrupt: " + expectedSha256);
        }
    }

    public record ObjectInfo(String sha256, long size, Path path) {
    }
}
