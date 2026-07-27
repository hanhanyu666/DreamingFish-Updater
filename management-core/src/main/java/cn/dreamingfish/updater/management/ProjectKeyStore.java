package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Set;

final class ProjectKeyStore {
    private final ManagementPaths paths;

    ProjectKeyStore(ManagementPaths paths) {
        this.paths = paths;
    }

    KeyMaterial create(String projectId) {
        if (projectId == null || !projectId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new ManagementException("Invalid project ID");
        }
        Path privateKeyFile = paths.keys().resolve(projectId + ".pk8");
        if (Files.exists(privateKeyFile)) {
            throw new ManagementException("A private key file already exists for project " + projectId);
        }
        KeyPair keyPair = CryptoSupport.generateEd25519KeyPair();
        try {
            AtomicFiles.write(privateKeyFile,
                    CryptoSupport.encodePrivateKey(keyPair.getPrivate()).getBytes(StandardCharsets.US_ASCII));
            restrictPermissions(privateKeyFile);
        } catch (IOException e) {
            throw new ManagementException("Unable to store the project private key", e);
        }
        return new KeyMaterial(
                privateKeyFile,
                CryptoSupport.encodePublicKey(keyPair.getPublic()),
                keyPair.getPrivate()
        );
    }

    PrivateKey load(ProjectRecord project) {
        try {
            String encoded = Files.readString(project.privateKeyFile(), StandardCharsets.US_ASCII).trim();
            return CryptoSupport.decodePrivateKey(encoded);
        } catch (IOException e) {
            throw new ManagementException("Unable to read the private key for " + project.id(), e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs inherit from the management data directory.
        }
    }

    record KeyMaterial(Path privateKeyFile, String publicKey, PrivateKey privateKey) {
    }
}
