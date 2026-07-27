package cn.dreamingfish.updater.bootstrap;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerProgramVerifierTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesAProjectSignedProgramAndRejectsEveryChangedByte() throws Exception {
        Fixture fixture = createFixture();
        assertDoesNotThrow(() -> fixture.verifier.verify(fixture.config, fixture.binding));
        assertDoesNotThrow(() -> PlayerProgramVerifierMain.verify(
                fixture.config.playerHome(), fixture.binding.projectId(), fixture.binding.publicKey(),
                fixture.config.version(), fixture.config.manifestSha256()));

        Files.write(fixture.launcher, "changed".getBytes(StandardCharsets.UTF_8));
        assertThrows(BootstrapException.class,
                () -> fixture.verifier.verify(fixture.config, fixture.binding));
    }

    @Test
    void rejectsUnsignedFilesAndChangedManifestBytes() throws Exception {
        Fixture extra = createFixture();
        Files.write(extra.programRoot.resolve("unsigned.dll"), new byte[]{1});
        assertThrows(BootstrapException.class,
                () -> extra.verifier.verify(extra.config, extra.binding));

        Fixture changedManifest = createFixture();
        Files.write(changedManifest.manifestPath,
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(BootstrapException.class,
                () -> changedManifest.verifier.verify(changedManifest.config, changedManifest.binding));
    }

    private Fixture createFixture() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home-" + System.nanoTime()));
        Path root = Files.createDirectories(home.resolve("app/0.1.0"));
        Path launcher = root.resolve("player.exe");
        Path library = root.resolve("runtime.jar");
        Files.write(launcher, "trusted-launcher".getBytes(StandardCharsets.UTF_8));
        Files.write(library, "trusted-runtime".getBytes(StandardCharsets.UTF_8));

        String manifestJson = "{"
                + "\"schemaVersion\":1,"
                + "\"projectId\":\"demo\","
                + "\"version\":\"0.1.0\","
                + "\"launchPath\":\"player.exe\","
                + "\"files\":["
                + fileJson("player.exe", launcher)
                + "," + fileJson("runtime.jar", library)
                + "]}";
        byte[] manifest = manifestJson.getBytes(StandardCharsets.UTF_8);
        String manifestHash = hash(manifest);

        Ed25519KeyPairGenerator generator = new Ed25519KeyPairGenerator();
        generator.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        Ed25519PrivateKeyParameters privateKey = (Ed25519PrivateKeyParameters) pair.getPrivate();
        Ed25519PublicKeyParameters publicKey = (Ed25519PublicKeyParameters) pair.getPublic();
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(manifest, 0, manifest.length);
        byte[] signature = signer.generateSignature();

        Path manifestDirectory = Files.createDirectories(
                home.resolve("state/player-programs").resolve(manifestHash));
        Path manifestPath = manifestDirectory.resolve("manifest.json");
        Files.write(manifestPath, manifest);
        Files.write(manifestDirectory.resolve("manifest.sig"),
                Base64.getEncoder().encode(signature));
        Files.write(home.resolve("state/active-player.properties"), (
                "schema=1\n"
                        + "version=0.1.0\n"
                        + "launcher=app/0.1.0/player.exe\n"
                        + "programRoot=app/0.1.0\n"
                        + "manifestSha256=" + manifestHash + "\n"
                        + "timeoutSeconds=90\n").getBytes(StandardCharsets.UTF_8));

        String encodedPublicKey = Base64.getEncoder().encodeToString(
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKey).getEncoded());
        BootstrapBinding binding = new BootstrapBinding(home, "demo", encodedPublicKey);
        return new Fixture(new PlayerProgramVerifier(), ActivePlayerConfig.load(home), binding,
                root, launcher, manifestPath);
    }

    private static String fileJson(String path, Path file) throws Exception {
        return "{\"path\":\"" + path + "\",\"sha256\":\"" + hash(Files.readAllBytes(file))
                + "\",\"size\":" + Files.size(file) + ",\"executable\":false}";
    }

    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static final class Fixture {
        private final PlayerProgramVerifier verifier;
        private final ActivePlayerConfig config;
        private final BootstrapBinding binding;
        private final Path programRoot;
        private final Path launcher;
        private final Path manifestPath;

        private Fixture(PlayerProgramVerifier verifier, ActivePlayerConfig config,
                        BootstrapBinding binding, Path programRoot, Path launcher,
                        Path manifestPath) {
            this.verifier = verifier;
            this.config = config;
            this.binding = binding;
            this.programRoot = programRoot;
            this.launcher = launcher;
            this.manifestPath = manifestPath;
        }
    }
}
