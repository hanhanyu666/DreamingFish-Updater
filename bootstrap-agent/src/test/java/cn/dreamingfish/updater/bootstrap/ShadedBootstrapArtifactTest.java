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

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs after package and verifies that the minimized agent is self-contained.
 *
 * <p>The child JVM receives only the shaded artifact on its class path. That is
 * deliberately different from normal unit tests, whose class path also contains
 * the original Jackson and Bouncy Castle dependencies.</p>
 */
class ShadedBootstrapArtifactTest {
    private static final String ARTIFACT_VERIFICATION_PROPERTY =
            "dreamingfish.shaded.artifact.verification";
    private static final String ARTIFACT_PATH_PROPERTY = "dreamingfish.shaded.artifact.path";
    private static final String CLASSES_PATH_PROPERTY = "dreamingfish.shaded.classes.path";

    @TempDir
    Path temporary;

    @Test
    void minimizedArtifactKeepsAllAgentClassesAndVerifiesSignedProgramInIsolation() throws Exception {
        assumeTrue(Boolean.getBoolean(ARTIFACT_VERIFICATION_PROPERTY),
                "The shaded-artifact smoke test runs in Maven's verify phase after packaging.");

        Path artifact = Paths.get(requiredProperty(ARTIFACT_PATH_PROPERTY));
        Path classes = Paths.get(requiredProperty(CLASSES_PATH_PROPERTY));
        assertTrue(Files.isRegularFile(artifact), "The shaded bootstrap artifact must exist");
        assertProjectClassesArePresent(artifact, classes);
        assertRequiredRuntimeClassesArePresent(artifact);

        Fixture fixture = createFixture();
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp", artifact.toString(),
                PlayerProgramVerifierMain.class.getName(),
                fixture.playerHome.toString(),
                "demo",
                fixture.publicKey,
                "0.1.0",
                fixture.manifestHash)
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(readAll(input), StandardCharsets.UTF_8);
        }
        assertEquals(0, process.waitFor(),
                "The minimized artifact must parse the manifest and verify the Ed25519 signature: " + output);
    }

    private static void assertProjectClassesArePresent(Path artifact, Path classes) throws Exception {
        Set<String> expected = classEntries(classes);
        Set<String> actual = jarEntries(artifact);
        for (String entry : expected) {
            assertTrue(actual.contains(entry), "Minimization removed an agent class: " + entry);
        }
    }

    private static void assertRequiredRuntimeClassesArePresent(Path artifact) throws Exception {
        Set<String> entries = jarEntries(artifact);
        String[] required = {
                "cn/dreamingfish/updater/bootstrap/BootstrapAgent.class",
                "cn/dreamingfish/updater/bootstrap/PlayerProgramVerifier.class",
                "cn/dreamingfish/updater/bootstrap/PlayerProgramVerifierMain.class",
                "com/fasterxml/jackson/core/JsonFactory.class",
                "com/fasterxml/jackson/core/JsonParser.class",
                "org/bouncycastle/crypto/util/PublicKeyFactory.class",
                "org/bouncycastle/crypto/signers/Ed25519Signer.class",
                "org/bouncycastle/crypto/params/Ed25519PublicKeyParameters.class"
        };
        for (String entry : required) {
            assertTrue(entries.contains(entry), "Minimization removed a required runtime class: " + entry);
        }
    }

    private Fixture createFixture() throws Exception {
        Path playerHome = Files.createDirectories(temporary.resolve("player-home"));
        Path programRoot = Files.createDirectories(playerHome.resolve("app/0.1.0"));
        Path launcher = programRoot.resolve("player.exe");
        Path library = programRoot.resolve("runtime.jar");
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
                playerHome.resolve("state/player-programs").resolve(manifestHash));
        Files.write(manifestDirectory.resolve("manifest.json"), manifest);
        Files.write(manifestDirectory.resolve("manifest.sig"), Base64.getEncoder().encode(signature));
        Files.write(playerHome.resolve("state/active-player.properties"), (
                "schema=1\n"
                        + "version=0.1.0\n"
                        + "launcher=app/0.1.0/player.exe\n"
                        + "programRoot=app/0.1.0\n"
                        + "manifestSha256=" + manifestHash + "\n"
                        + "timeoutSeconds=90\n").getBytes(StandardCharsets.UTF_8));

        String publicKeyText = Base64.getEncoder().encodeToString(
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKey).getEncoded());
        return new Fixture(playerHome, publicKeyText, manifestHash);
    }

    private static Set<String> classEntries(Path classes) throws Exception {
        Set<String> entries = new HashSet<String>();
        try (java.util.stream.Stream<Path> files = Files.walk(classes)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> entries.add(classes.relativize(path)
                            .toString().replace(File.separatorChar, '/')));
        }
        return entries;
    }

    private static Set<String> jarEntries(Path artifact) throws Exception {
        Set<String> entries = new HashSet<String>();
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            java.util.Enumeration<? extends ZipEntry> enumeration = archive.entries();
            while (enumeration.hasMoreElements()) entries.add(enumeration.nextElement().getName());
        }
        return entries;
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", name);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) output.write(buffer, 0, read);
        }
        return output.toByteArray();
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

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing test property: " + name);
        }
        return value;
    }

    private static final class Fixture {
        private final Path playerHome;
        private final String publicKey;
        private final String manifestHash;

        private Fixture(Path playerHome, String publicKey, String manifestHash) {
            this.playerHome = playerHome;
            this.publicKey = publicKey;
            this.manifestHash = manifestHash;
        }
    }
}
