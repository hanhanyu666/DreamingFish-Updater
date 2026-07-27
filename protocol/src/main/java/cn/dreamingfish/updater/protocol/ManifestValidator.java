package cn.dreamingfish.updater.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ManifestValidator {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern RELEASE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern CSS_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern COMPONENT_ID = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private ManifestValidator() {
    }

    public static void validateRelease(ReleaseManifest manifest, Set<String> supportedCapabilities) {
        if (manifest.schemaVersion() != ProtocolConstants.RELEASE_SCHEMA_VERSION) {
            throw new ProtocolException("Unsupported release schema version: " + manifest.schemaVersion());
        }
        validateIdentifier(manifest.projectId(), "project ID");
        if (manifest.releaseId() == null || !RELEASE_ID.matcher(manifest.releaseId()).matches()) {
            throw new ProtocolException("Invalid release ID");
        }
        if (manifest.sequence() <= 0) {
            throw new ProtocolException("Release sequence must be positive");
        }
        if (manifest.createdAt() == null || manifest.createdAt().isAfter(Instant.now().plusSeconds(86400))) {
            throw new ProtocolException("Release creation time is invalid");
        }
        requireText(manifest.displayVersion(), "display version", 128);
        SemanticVersion.parse(manifest.minimumPlayerVersion());
        if (!supportedCapabilities.containsAll(manifest.requiredCapabilities())) {
            Set<String> missing = new java.util.TreeSet<>(manifest.requiredCapabilities());
            missing.removeAll(supportedCapabilities);
            throw new ProtocolException("Unsupported required capabilities: " + String.join(", ", missing));
        }
        validateForcedSyncDirectories(manifest);
        validateBranding(manifest.branding());

        List<String> paths = new ArrayList<>();
        String previous = null;
        for (ManifestFile file : manifest.files()) {
            String path = PathSafety.normalizeManifestPath(file.path());
            if (previous != null && Comparator.<String>naturalOrder().compare(previous, path) >= 0) {
                throw new ProtocolException("Manifest files must be sorted by path and unique: " + path);
            }
            previous = path;
            paths.add(path);
            if (!Hex.isSha256(file.sha256())) {
                throw new ProtocolException("Invalid SHA-256 for " + path);
            }
            if (file.size() < 0) {
                throw new ProtocolException("Negative file size for " + path);
            }
            if (file.policy() == null) {
                throw new ProtocolException("Missing file policy for " + path);
            }
            if (file.componentId() != null && !COMPONENT_ID.matcher(file.componentId()).matches()) {
                throw new ProtocolException("Invalid component ID for " + path);
            }
            requireLength(file.displayName(), "component display name", 256);
            if (insideForcedDirectory(path, manifest.forcedSyncDirectories())
                    && file.policy() != FilePolicy.ENFORCED) {
                throw new ProtocolException(
                        "Files in forced sync directories must be enforced: " + path);
            }
        }
        PathSafety.validateDistinctPaths(paths);
    }

    private static void validateForcedSyncDirectories(ReleaseManifest manifest) {
        if (!manifest.forcedSyncDirectories().isEmpty()
                && !manifest.requiredCapabilities().contains(
                ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC)) {
            throw new ProtocolException("Forced directory sync is missing its required capability");
        }
        String previous = null;
        List<String> paths = new ArrayList<>();
        for (String directory : manifest.forcedSyncDirectories()) {
            String normalized = PathSafety.normalizeManifestPath(directory);
            if (normalized.indexOf('/') >= 0) {
                throw new ProtocolException("Forced sync directories must be top-level: " + directory);
            }
            if (normalized.equalsIgnoreCase(".dreamingfish-bootstrap")) {
                throw new ProtocolException("The bootstrap directory cannot be force-synced");
            }
            if (previous != null && previous.compareTo(normalized) >= 0) {
                throw new ProtocolException("Forced sync directories must be sorted and unique: " + directory);
            }
            previous = normalized;
            paths.add(normalized);
        }
        PathSafety.validateDistinctPaths(paths);
    }

    private static boolean insideForcedDirectory(String path, List<String> directories) {
        String folded = path.toLowerCase(Locale.ROOT);
        for (String directory : directories) {
            String root = directory.toLowerCase(Locale.ROOT);
            if (folded.startsWith(root + "/")) return true;
        }
        return false;
    }

    public static PublicKey validateBinding(ProjectBinding binding) {
        if (binding.schemaVersion() != ProtocolConstants.BINDING_SCHEMA_VERSION) {
            throw new ProtocolException("Unsupported project binding schema version: " + binding.schemaVersion());
        }
        validateIdentifier(binding.projectId(), "project ID");
        try {
            URI uri = new URI(binding.baseUrl());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new ProtocolException("Project base URL must be an absolute HTTP(S) URL without user info");
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new ProtocolException("Project base URL cannot contain a query or fragment");
            }
        } catch (URISyntaxException | NullPointerException e) {
            throw new ProtocolException("Invalid project base URL", e);
        }
        requireText(binding.playerHome(), "player home", 4096);
        validateBranding(binding.fallbackBranding());
        return CryptoSupport.decodePublicKey(binding.publicKey());
    }

    public static void validatePlayerProgram(PlayerProgramManifest manifest, Set<String> supportedCapabilities) {
        if (manifest.schemaVersion() != ProtocolConstants.PLAYER_PROGRAM_SCHEMA_VERSION) {
            throw new ProtocolException("Unsupported player program schema version: " + manifest.schemaVersion());
        }
        validateIdentifier(manifest.projectId(), "player program project ID");
        validateIdentifier(manifest.platform(), "player program platform");
        SemanticVersion.parse(manifest.version());
        if (manifest.createdAt() == null || manifest.createdAt().isAfter(Instant.now().plusSeconds(86400))) {
            throw new ProtocolException("Player program creation time is invalid");
        }
        SemanticVersion.parse(manifest.minimumBootstrapVersion());
        PathSafety.normalizeManifestPath(manifest.launchPath());
        if (!supportedCapabilities.containsAll(manifest.requiredCapabilities())) {
            throw new ProtocolException("Player program requires unsupported capabilities");
        }
        List<String> paths = new ArrayList<>();
        String previous = null;
        for (PlayerProgramFile file : manifest.files()) {
            String path = PathSafety.normalizeManifestPath(file.path());
            if (previous != null && previous.compareTo(path) >= 0) {
                throw new ProtocolException("Player program files must be sorted and unique");
            }
            previous = path;
            paths.add(path);
            if (!Hex.isSha256(file.sha256()) || file.size() < 0) {
                throw new ProtocolException("Invalid player program file: " + path);
            }
        }
        PathSafety.validateDistinctPaths(paths);
        if (manifest.files().stream().noneMatch(file -> file.path().equals(manifest.launchPath()))) {
            throw new ProtocolException("Player program launcher is not listed in the manifest");
        }
    }

    private static void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new ProtocolException("Invalid " + label);
        }
    }

    private static void validateBranding(Branding branding) {
        requireText(branding.productName(), "branding product name", 128);
        requireLength(branding.subtitle(), "branding subtitle", 512);
        requireLength(branding.serverAddress(), "server address", 255);
        if (branding.coverObject() != null && !Hex.isSha256(branding.coverObject())) {
            throw new ProtocolException("Invalid branding cover object hash");
        }
        validateColor(branding.accentColor(), "accent color");
        validateColor(branding.secondaryAccentColor(), "secondary accent color");
    }

    private static void validateColor(String value, String label) {
        if (value == null || !CSS_COLOR.matcher(value).matches()) {
            throw new ProtocolException("Invalid " + label);
        }
    }

    private static void requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException("Missing " + label);
        }
        requireLength(value, label, maxLength);
    }

    private static void requireLength(String value, String label, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new ProtocolException(label + " is too long");
        }
    }
}
