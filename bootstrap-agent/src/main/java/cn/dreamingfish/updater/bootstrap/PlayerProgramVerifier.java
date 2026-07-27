package cn.dreamingfish.updater.bootstrap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.util.PublicKeyFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Verifies a project-signed player program before it is allowed to issue launch permission. */
final class PlayerProgramVerifier {
    private static final int PLAYER_MANIFEST_SCHEMA = 1;
    private static final long MAX_MANIFEST_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_FILES = 100_000;
    private static final Set<String> WINDOWS_RESERVED = reservedNames();

    void verify(ActivePlayerConfig config, BootstrapBinding binding) throws BootstrapException {
        if (binding.projectId() == null || !binding.projectId().matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new BootstrapException("Project binding does not contain a valid project identity");
        }
        if (binding.publicKey() == null || binding.publicKey().length() > 4096) {
            throw new BootstrapException("Project binding does not contain a usable public key");
        }
        String manifestHash = config.manifestSha256();
        if (manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")) {
            throw new BootstrapException("Player program has no signed verification manifest");
        }
        if (config.programRoot() == null) {
            throw new BootstrapException("Player program root is missing from the active configuration");
        }

        Path manifestDirectory = config.playerHome().resolve("state/player-programs").resolve(manifestHash);
        Path manifestPath = manifestDirectory.resolve("manifest.json");
        Path signaturePath = manifestDirectory.resolve("manifest.sig");
        byte[] manifestBytes = readLimitedRegularFile(manifestPath, MAX_MANIFEST_BYTES,
                "Player program verification manifest is missing or too large");
        if (!manifestHash.equals(sha256(manifestBytes))) {
            throw new BootstrapException("Player program verification manifest was modified");
        }
        byte[] signatureText = readLimitedRegularFile(signaturePath, 1024,
                "Player program signature is missing or too large");
        verifySignature(manifestBytes, new String(signatureText, StandardCharsets.US_ASCII).trim(),
                binding.publicKey());

        ProgramManifest manifest = parseManifest(manifestBytes);
        if (manifest.schemaVersion != PLAYER_MANIFEST_SCHEMA
                || !binding.projectId().equals(manifest.projectId)
                || !config.version().equals(manifest.version)) {
            throw new BootstrapException("Player program manifest identity does not match this instance");
        }
        verifyProgramDirectory(config, manifest);
    }

    private void verifyProgramDirectory(ActivePlayerConfig config, ProgramManifest manifest)
            throws BootstrapException {
        Path root = config.programRoot();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new BootstrapException("Player program directory is missing or is a symbolic link");
        }
        if (!config.launcher().startsWith(root)) {
            throw new BootstrapException("Player program launcher is outside its signed directory");
        }
        String launcher = normalizeRelative(root.relativize(config.launcher()).toString().replace('\\', '/'));
        if (!fold(launcher).equals(fold(manifest.launchPath))) {
            throw new BootstrapException("Player program launcher differs from its signed manifest");
        }

        Map<String, ProgramFile> expected = new HashMap<String, ProgramFile>();
        String previousPath = null;
        boolean launcherListed = false;
        for (ProgramFile file : manifest.files) {
            String normalized = normalizeRelative(file.path);
            if (previousPath != null && previousPath.compareTo(normalized) >= 0) {
                throw new BootstrapException("Player program manifest files are not sorted and unique");
            }
            previousPath = normalized;
            if (!file.sha256.matches("[0-9a-f]{64}") || file.size < 0) {
                throw new BootstrapException("Player program manifest contains invalid file metadata");
            }
            String key = fold(normalized);
            if (expected.put(key, file) != null) {
                throw new BootstrapException("Player program manifest contains a path collision");
            }
            if (key.equals(fold(manifest.launchPath))) launcherListed = true;
            Path installed = resolveInside(root, normalized);
            if (!Files.isRegularFile(installed, LinkOption.NOFOLLOW_LINKS)) {
                throw new BootstrapException("Player program file is missing: " + normalized);
            }
            try {
                if (Files.size(installed) != file.size || !file.sha256.equals(sha256(installed))) {
                    throw new BootstrapException("Player program file was modified: " + normalized);
                }
            } catch (IOException e) {
                throw new BootstrapException("Unable to verify player program file: " + normalized, e);
            }
        }
        if (!launcherListed) {
            throw new BootstrapException("Player program launcher is absent from its signed manifest");
        }

        Set<String> actual = new HashSet<String>();
        try (Stream<Path> stream = Files.walk(root)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(root)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path)) {
                    throw new BootstrapException("Player program contains a symbolic link: " + relative);
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    String key = fold(normalizeRelative(relative));
                    if (!actual.add(key) || !expected.containsKey(key)) {
                        throw new BootstrapException("Player program contains an unsigned file: " + relative);
                    }
                } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new BootstrapException("Player program contains an unsupported entry: " + relative);
                }
            }
        } catch (BootstrapException e) {
            throw e;
        } catch (IOException e) {
            throw new BootstrapException("Unable to enumerate player program files", e);
        }
        if (!actual.equals(expected.keySet())) {
            throw new BootstrapException("Player program directory is incomplete");
        }
    }

    private ProgramManifest parseManifest(byte[] bytes) throws BootstrapException {
        try {
            JsonFactory factory = new JsonFactory();
            factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonParser parser = factory.createParser(bytes);
            try {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new BootstrapException("Player program manifest must be a JSON object");
                }
                int schemaVersion = -1;
                String projectId = null;
                String version = null;
                String launchPath = null;
                List<ProgramFile> files = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = parser.getCurrentName();
                    JsonToken value = parser.nextToken();
                    if ("schemaVersion".equals(field) && value.isNumeric()) {
                        schemaVersion = parser.getIntValue();
                    } else if ("projectId".equals(field) && value == JsonToken.VALUE_STRING) {
                        projectId = parser.getValueAsString();
                    } else if ("version".equals(field) && value == JsonToken.VALUE_STRING) {
                        version = parser.getValueAsString();
                    } else if ("launchPath".equals(field) && value == JsonToken.VALUE_STRING) {
                        launchPath = normalizeRelative(parser.getValueAsString());
                    } else if ("files".equals(field) && value == JsonToken.START_ARRAY) {
                        files = parseFiles(parser);
                    } else {
                        parser.skipChildren();
                    }
                }
                if (parser.nextToken() != null) {
                    throw new BootstrapException("Player program manifest has trailing JSON content");
                }
                if (projectId == null || version == null || launchPath == null || files == null) {
                    throw new BootstrapException("Player program manifest is missing required fields");
                }
                return new ProgramManifest(schemaVersion, projectId, version, launchPath, files);
            } finally {
                parser.close();
            }
        } catch (BootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new BootstrapException("Unable to parse player program verification manifest", e);
        }
    }

    private List<ProgramFile> parseFiles(JsonParser parser) throws IOException, BootstrapException {
        List<ProgramFile> files = new ArrayList<ProgramFile>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
                throw new BootstrapException("Player program file entry must be an object");
            }
            String path = null;
            String hash = null;
            long size = -1;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.getCurrentName();
                JsonToken value = parser.nextToken();
                if ("path".equals(field) && value == JsonToken.VALUE_STRING) {
                    path = parser.getValueAsString();
                } else if ("sha256".equals(field) && value == JsonToken.VALUE_STRING) {
                    hash = parser.getValueAsString();
                } else if ("size".equals(field) && value.isNumeric()) {
                    size = parser.getLongValue();
                } else {
                    parser.skipChildren();
                }
            }
            if (path == null || hash == null || size < 0) {
                throw new BootstrapException("Player program file entry is incomplete");
            }
            files.add(new ProgramFile(path, hash, size));
            if (files.size() > MAX_FILES) {
                throw new BootstrapException("Player program manifest contains too many files");
            }
        }
        return Collections.unmodifiableList(files);
    }

    private void verifySignature(byte[] manifest, String signatureText, String publicKeyText)
            throws BootstrapException {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyText);
            byte[] signature = Base64.getDecoder().decode(signatureText);
            if (signature.length != 64) {
                throw new BootstrapException("Player program signature has the wrong size");
            }
            AsymmetricKeyParameter parameter = PublicKeyFactory.createKey(publicKeyBytes);
            if (!(parameter instanceof Ed25519PublicKeyParameters)) {
                throw new BootstrapException("Project binding public key is not Ed25519");
            }
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, parameter);
            verifier.update(manifest, 0, manifest.length);
            if (!verifier.verifySignature(signature)) {
                throw new BootstrapException("Player program signature is invalid");
            }
        } catch (BootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new BootstrapException("Unable to verify the player program signature", e);
        }
    }

    private static byte[] readLimitedRegularFile(Path path, long maximum, String message)
            throws BootstrapException {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                    || Files.size(path) > maximum) {
                throw new BootstrapException(message + ": " + path);
            }
            return Files.readAllBytes(path);
        } catch (BootstrapException e) {
            throw e;
        } catch (IOException e) {
            throw new BootstrapException(message + ": " + path, e);
        }
    }

    private static Path resolveInside(Path root, String relative) throws BootstrapException {
        String normalized = normalizeRelative(relative);
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path candidate = absoluteRoot.resolve(normalized.replace('/', java.io.File.separatorChar)).normalize();
        if (!candidate.startsWith(absoluteRoot)) {
            throw new BootstrapException("Player program path escapes its directory: " + relative);
        }
        Path current = absoluteRoot;
        for (Path segment : absoluteRoot.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new BootstrapException("Player program path traverses a symbolic link: " + relative);
            }
        }
        return candidate;
    }

    private static String normalizeRelative(String value) throws BootstrapException {
        if (value == null || value.length() == 0 || value.length() > 32767
                || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.indexOf("//") >= 0) {
            throw new BootstrapException("Player program manifest contains an unsafe path");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.length() == 0 || ".".equals(segment) || "..".equals(segment)
                    || segment.endsWith(" ") || segment.endsWith(".")) {
                throw new BootstrapException("Player program manifest contains an unsafe path: " + value);
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character < 32 || "<>:\"|?*".indexOf(character) >= 0) {
                    throw new BootstrapException("Player program manifest contains an unsafe path: " + value);
                }
            }
            String base = segment;
            int dot = segment.indexOf('.');
            if (dot >= 0) base = segment.substring(0, dot);
            if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
                throw new BootstrapException("Player program manifest uses a reserved path: " + value);
            }
        }
        return value;
    }

    private static String sha256(Path path) throws IOException, BootstrapException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            java.io.InputStream input = Files.newInputStream(path);
            try {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            } finally {
                input.close();
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new BootstrapException("SHA-256 is unavailable", e);
        }
    }

    private static String sha256(byte[] bytes) throws BootstrapException {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new BootstrapException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = alphabet[value >>> 4];
            output[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(output);
    }

    private static String fold(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static Set<String> reservedNames() {
        Set<String> names = new HashSet<String>();
        Collections.addAll(names, "CON", "PRN", "AUX", "NUL");
        for (int number = 1; number <= 9; number++) {
            names.add("COM" + number);
            names.add("LPT" + number);
        }
        return Collections.unmodifiableSet(names);
    }

    private static final class ProgramManifest {
        private final int schemaVersion;
        private final String projectId;
        private final String version;
        private final String launchPath;
        private final List<ProgramFile> files;

        private ProgramManifest(int schemaVersion, String projectId, String version,
                                String launchPath, List<ProgramFile> files) {
            this.schemaVersion = schemaVersion;
            this.projectId = projectId;
            this.version = version;
            this.launchPath = launchPath;
            this.files = files;
        }
    }

    private static final class ProgramFile {
        private final String path;
        private final String sha256;
        private final long size;

        private ProgramFile(String path, String sha256, long size) {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
        }
    }
}
