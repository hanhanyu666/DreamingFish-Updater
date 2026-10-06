package cn.dreamingfish.updater.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ManifestValidator {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern RELEASE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern CSS_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern COMPONENT_ID = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern NEWS_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern MUSIC_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

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
        Map<String, ManifestFile> filesByFoldedPath = new HashMap<>();
        String previous = null;
        for (ManifestFile file : manifest.files()) {
            String path = PathSafety.normalizeManifestPath(file.path());
            if (previous != null && Comparator.<String>naturalOrder().compare(previous, path) >= 0) {
                throw new ProtocolException("Manifest files must be sorted by path and unique: " + path);
            }
            previous = path;
            paths.add(path);
            filesByFoldedPath.put(path.toLowerCase(Locale.ROOT), file);
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
            requirePlainText(file.version(), "component version", 128);
            if (insideForcedDirectory(path, manifest.forcedSyncDirectories())
                    && file.policy() != FilePolicy.ENFORCED) {
                throw new ProtocolException(
                        "Files in forced sync directories must be enforced: " + path);
            }
        }
        PathSafety.validateDistinctPaths(paths);
        validateForcedSyncFiles(manifest, filesByFoldedPath);
        validateMaintenancePolicy(manifest, filesByFoldedPath);
        validateReleasedPaths(manifest, paths);
    }

    private static final Pattern GROUP_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern DIRECTIVE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_DIRECTIVE_ITEMS = 2_000;

    private static void validateMaintenancePolicy(
            ReleaseManifest manifest, Map<String, ManifestFile> filesByFoldedPath) {
        boolean usesPolicyFields = manifest.files().stream().anyMatch(file ->
                file.preset() != null || file.optionalGroup() != null)
                || !manifest.cleanupDirectories().isEmpty()
                || !manifest.optionalGroups().isEmpty()
                || !manifest.retainedSelfManagedPaths().isEmpty()
                || !manifest.withdrawals().isEmpty()
                || !manifest.corrections().isEmpty();
        if (!manifest.usesMaintenancePolicy()) {
            if (manifest.requiredCapabilities().contains(ProtocolConstants.CAPABILITY_SIMPLIFIED_MAINTENANCE)) {
                throw new ProtocolException("Simplified maintenance requires maintenance-policy-v2");
            }
            if (usesPolicyFields) {
                throw new ProtocolException("Maintenance policy fields are missing their required capability");
            }
            return;
        }
        if (!manifest.forcedSyncDirectories().isEmpty() || !manifest.forcedSyncFiles().isEmpty()) {
            throw new ProtocolException("Maintenance policy releases describe forced content with presets");
        }
        if (manifest.requiredCapabilities().contains(ProtocolConstants.CAPABILITY_SIMPLIFIED_MAINTENANCE)) {
            for (ManifestFile file : manifest.files()) {
                if (file.preset() == MaintenancePreset.DEFAULT_CONFIG) {
                    throw new ProtocolException("New simplified releases use INITIAL instead of DEFAULT_CONFIG");
                }
                if (file.optionalGroup() != null && file.preset() != MaintenancePreset.SYNC) {
                    throw new ProtocolException("Optional packages require ordinary sync: " + file.path());
                }
                if (manifest.cleanupDirectories().stream().anyMatch(dir -> ManagedPaths.isBelow(file.path(), dir))
                        && file.preset() != MaintenancePreset.REQUIRED) {
                    throw new ProtocolException("A fully forced directory cannot contain softer or optional content: " + file.path());
                }
            }
        }
        validateCleanupDirectories(manifest.cleanupDirectories());

        Map<String, OptionalGroup> groups = new HashMap<>();
        for (OptionalGroup group : manifest.optionalGroups()) {
            if (group == null || group.id() == null || !GROUP_ID.matcher(group.id()).matches()
                    || groups.putIfAbsent(group.id(), group) != null) {
                throw new ProtocolException("Invalid or duplicate optional group ID");
            }
            requireText(group.title(), "optional group title", 60);
            requirePlainText(group.title(), "optional group title", 60);
            requireLength(group.description(), "optional group description", 300);
        }
        java.util.Set<String> usedGroups = new java.util.HashSet<>();
        for (ManifestFile file : manifest.files()) {
            if (file.policy() == FilePolicy.LEGACY_MISSING_ONLY) {
                throw new ProtocolException("Maintenance policy releases cannot use the DEFAULT token: "
                        + file.path());
            }
            if (file.preset() == null) {
                throw new ProtocolException("Missing maintenance preset for " + file.path());
            }
            if (file.optionalGroup() != null) {
                if (!groups.containsKey(file.optionalGroup())) {
                    throw new ProtocolException("Unknown optional group for " + file.path());
                }
                if (file.preset() == MaintenancePreset.REQUIRED) {
                    throw new ProtocolException("Required files cannot be optional: " + file.path());
                }
                usedGroups.add(file.optionalGroup());
            }
        }
        for (String group : groups.keySet()) {
            if (!usedGroups.contains(group)) {
                throw new ProtocolException("Optional group has no files: " + group);
            }
        }

        String previous = null;
        List<String> retained = new ArrayList<>();
        for (String path : manifest.retainedSelfManagedPaths()) {
            String normalized = PathSafety.normalizeManifestPath(path);
            if (previous != null && previous.compareTo(normalized) >= 0) {
                throw new ProtocolException("Retained self-managed paths must be sorted and unique: " + path);
            }
            previous = normalized;
            if (filesByFoldedPath.containsKey(normalized.toLowerCase(Locale.ROOT))) {
                throw new ProtocolException("A published path cannot also be retained: " + path);
            }
            if (manifest.releasedPaths().stream().anyMatch(released -> released.equalsIgnoreCase(normalized))) {
                throw new ProtocolException("A released path cannot also be retained: " + path);
            }
            if (insideForcedDirectory(normalized, manifest.cleanupDirectories())) {
                throw new ProtocolException("A cleanup directory cannot retain removed files: " + path);
            }
            retained.add(normalized);
        }
        PathSafety.validateDistinctPaths(retained);

        validateWithdrawals(manifest);
        validateCorrections(manifest, filesByFoldedPath);
    }

    private static void validateCleanupDirectories(List<String> directories) {
        String previous = null;
        List<String> paths = new ArrayList<>();
        for (String directory : directories) {
            String normalized = PathSafety.normalizeManifestPath(directory);
            if (normalized.equalsIgnoreCase(".dreamingfish-bootstrap")
                    || normalized.toLowerCase(Locale.ROOT).startsWith(".dreamingfish-bootstrap/")) {
                throw new ProtocolException("The bootstrap directory cannot be cleaned");
            }
            if (previous != null && previous.compareTo(normalized) >= 0) {
                throw new ProtocolException("Cleanup directories must be sorted and unique: " + directory);
            }
            previous = normalized;
            paths.add(normalized);
        }
        PathSafety.validateDistinctPaths(paths);
    }

    private static void validateWithdrawals(ReleaseManifest manifest) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        int items = 0;
        MaintenanceModel model = MaintenanceModel.of(manifest);
        for (Withdrawal withdrawal : manifest.withdrawals()) {
            if (withdrawal == null || withdrawal.id() == null
                    || !DIRECTIVE_ID.matcher(withdrawal.id()).matches() || !ids.add(withdrawal.id())) {
                throw new ProtocolException("Invalid or duplicate withdrawal ID");
            }
            if (withdrawal.createdAt() == null) {
                throw new ProtocolException("Withdrawal creation time is missing: " + withdrawal.id());
            }
            if (withdrawal.removal() && !model.simplified()) {
                throw new ProtocolException("Persistent removals require simplified-maintenance-v1");
            }
            requireLength(withdrawal.reason(), "withdrawal reason", 500);
            if (withdrawal.items().isEmpty()) {
                throw new ProtocolException("Withdrawal has no file versions: " + withdrawal.id());
            }
            for (WithdrawalItem item : withdrawal.items()) {
                items++;
                if (item == null || !Hex.isSha256(item.sha256()) || item.size() < 0) {
                    throw new ProtocolException("Invalid withdrawn file version in " + withdrawal.id());
                }
                PathSafety.normalizeManifestPath(item.path());
                if (item.componentId() != null && !COMPONENT_ID.matcher(item.componentId()).matches()) {
                    throw new ProtocolException("Invalid withdrawn mod ID in " + withdrawal.id());
                }
                requirePlainText(item.version(), "withdrawn version", 128);
            }
        }
        if (items > MAX_DIRECTIVE_ITEMS) {
            throw new ProtocolException("Too many withdrawn file versions");
        }
        for (ManifestFile file : manifest.files()) {
            if (model.withdrawalFor(file.path(), file.sha256(), file.componentId(), file.version()).isPresent()) {
                throw new ProtocolException("A withdrawn version is still published: " + file.path());
            }
        }
    }

    private static void validateCorrections(ReleaseManifest manifest,
                                            Map<String, ManifestFile> filesByFoldedPath) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (Correction correction : manifest.corrections()) {
            if (correction == null || correction.id() == null
                    || !DIRECTIVE_ID.matcher(correction.id()).matches() || !ids.add(correction.id())) {
                throw new ProtocolException("Invalid or duplicate correction ID");
            }
            if (correction.createdAt() == null || correction.mode() == null) {
                throw new ProtocolException("Correction is incomplete: " + correction.id());
            }
            requireLength(correction.reason(), "correction reason", 500);
            String normalized = PathSafety.normalizeManifestPath(correction.path());
            ManifestFile file = filesByFoldedPath.get(normalized.toLowerCase(Locale.ROOT));
            if (file == null || !file.path().equals(normalized)) {
                throw new ProtocolException("Correction target is not published: " + correction.path());
            }
            if (!paths.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new ProtocolException("Only one correction can target a file: " + correction.path());
            }
            if (correction.mode() == CorrectionMode.KNOWN_BAD) {
                if (correction.badSha256().isEmpty() || correction.badSha256().size() > 200) {
                    throw new ProtocolException("Known-bad correction needs bad versions: " + correction.id());
                }
                for (String bad : correction.badSha256()) {
                    if (!Hex.isSha256(bad) || bad.equals(file.sha256())) {
                        throw new ProtocolException("Invalid known-bad version in " + correction.id());
                    }
                }
            } else if (!correction.badSha256().isEmpty()) {
                throw new ProtocolException("One-time corrections do not list bad versions: " + correction.id());
            }
        }
    }

    private static void requirePlainText(String value, String label, int maxLength) {
        if (value == null) return;
        requireLength(value, label, maxLength);
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new ProtocolException(label + " contains control characters");
        }
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

    private static void validateForcedSyncFiles(
            ReleaseManifest manifest, Map<String, ManifestFile> filesByFoldedPath) {
        if (!manifest.forcedSyncFiles().isEmpty()
                && !manifest.requiredCapabilities().contains(
                ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC)) {
            throw new ProtocolException("Forced file sync is missing its required capability");
        }
        String previous = null;
        List<String> paths = new ArrayList<>();
        for (String forcedPath : manifest.forcedSyncFiles()) {
            String normalized = PathSafety.normalizeManifestPath(forcedPath);
            if (previous != null && previous.compareTo(normalized) >= 0) {
                throw new ProtocolException(
                        "Forced sync files must be sorted and unique: " + forcedPath);
            }
            previous = normalized;
            paths.add(normalized);
            ManifestFile file = filesByFoldedPath.get(normalized.toLowerCase(Locale.ROOT));
            if (file == null || !file.path().equals(normalized)) {
                throw new ProtocolException(
                        "Forced sync file is not present in the manifest: " + forcedPath);
            }
            if (file.policy() != FilePolicy.ENFORCED) {
                throw new ProtocolException(
                        "Forced sync files must be enforced: " + forcedPath);
            }
        }
        PathSafety.validateDistinctPaths(paths);
    }

    private static void validateReleasedPaths(
            ReleaseManifest manifest, List<String> managedPaths) {
        if (!manifest.releasedPaths().isEmpty()
                && !manifest.requiredCapabilities().contains(
                ProtocolConstants.CAPABILITY_RELEASED_PATHS)) {
            throw new ProtocolException("Released paths are missing their required capability");
        }
        String previous = null;
        List<String> released = new ArrayList<>();
        for (String releasedPath : manifest.releasedPaths()) {
            String normalized = PathSafety.normalizeManifestPath(releasedPath);
            if (previous != null && previous.compareTo(normalized) >= 0) {
                throw new ProtocolException(
                        "Released paths must be sorted and unique: " + releasedPath);
            }
            previous = normalized;
            if (insideForcedDirectory(normalized, manifest.forcedSyncDirectories())
                    || insideForcedDirectory(normalized, manifest.cleanupDirectories())
                    || manifest.forcedSyncFiles().stream()
                    .anyMatch(path -> path.equalsIgnoreCase(normalized))) {
                throw new ProtocolException(
                        "A forced sync path cannot be released: " + releasedPath);
            }
            released.add(normalized);
        }
        PathSafety.validateDistinctPaths(released);
        List<String> allOwnedPaths = new ArrayList<>(managedPaths);
        allOwnedPaths.addAll(released);
        PathSafety.validateDistinctPaths(allOwnedPaths);
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

    public static void validatePlayerPresentation(PlayerPresentation presentation) {
        if (presentation.schemaVersion() != ProtocolConstants.PLAYER_PRESENTATION_SCHEMA_VERSION) {
            throw new ProtocolException("Unsupported player presentation schema version: "
                    + presentation.schemaVersion());
        }
        validateIdentifier(presentation.projectId(), "player presentation project ID");
        validateBranding(presentation.branding());
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
        requireText(branding.brandName(), "branding name", 32);
        requireText(branding.brandEnglishName(), "English branding name", 48);
        requireText(branding.welcomeText(), "welcome text", 48);
        if (branding.coverObject() != null && !Hex.isSha256(branding.coverObject())) {
            throw new ProtocolException("Invalid branding cover object hash");
        }
        validateColor(branding.accentColor(), "accent color");
        validateColor(branding.secondaryAccentColor(), "secondary accent color");
        validateColor(branding.topBarColor(), "top bar color");
        validateColor(branding.cardColor(), "card color");
        validateColor(branding.titleColor(), "title color");
        if (branding.topBarOpacity() == null
                || !Double.isFinite(branding.topBarOpacity())
                || branding.topBarOpacity() < 0.0d
                || branding.topBarOpacity() > 1.0d) {
            throw new ProtocolException("Invalid top bar opacity");
        }
        validateNews(branding.newsArticles());
        validateCustomPage(branding.customPage());
        validateContentPages(branding.contentPages());
        validateMusic(branding.musicTracks());
    }

    private static void validateMusic(List<PlayerMusicTrack> tracks) {
        // null preserves compatibility with manifests created before music was
        // supported; an empty list explicitly means that no managed music is
        // published.
        if (tracks == null) return;
        if (tracks.size() > 100) {
            throw new ProtocolException("Too many player music tracks");
        }
        Set<String> ids = new java.util.HashSet<>();
        Set<String> paths = new java.util.HashSet<>();
        for (PlayerMusicTrack track : tracks) {
            if (track == null || track.id() == null
                    || !MUSIC_ID.matcher(track.id()).matches()
                    || !ids.add(track.id())) {
                throw new ProtocolException("Invalid or duplicate player music track ID");
            }
            requireText(track.title(), "player music title", 120);
            String fileName = PathSafety.normalizeManifestPath(track.fileName());
            if (fileName.indexOf('/') >= 0
                    || !fileName.toLowerCase(Locale.ROOT).endsWith(".mp3")
                    || !paths.add(fileName.toLowerCase(Locale.ROOT))) {
                throw new ProtocolException("Player music file names must be unique MP3 file names");
            }
            if (!Hex.isSha256(track.sha256()) || track.size() < 0
                    || track.size() > 20L * 1024 * 1024) {
                throw new ProtocolException("Invalid player music object metadata");
            }
        }
    }

    private static void validateContentPages(List<PlayerContentPage> pages) {
        // null means a pre-page-management project; the player converts the
        // legacy news/custom fields at runtime. An empty list is intentional.
        if (pages == null) return;
        if (pages.size() > 12) {
            throw new ProtocolException("Too many player content pages");
        }
        Set<String> ids = new java.util.HashSet<>();
        for (PlayerContentPage page : pages) {
            if (page == null || page.id() == null
                    || !NEWS_ID.matcher(page.id()).matches()
                    || !ids.add(page.id())) {
                throw new ProtocolException("Invalid or duplicate player content page ID");
            }
            requireText(page.navigationLabel(), "player page navigation label", 12);
            requireLength(page.eyebrow(), "player page eyebrow", 48);
            requireText(page.title(), "player page title", 120);
            requireLength(page.lead(), "player page lead", 300);
            requireLength(page.markdown(), "player page Markdown", 131_072);
            // Both bodies are retained so changing the page type in the
            // management UI is a reversible presentation choice. The player
            // only renders the body selected by announcementPage.
            validateNews(page.articles() == null ? List.of() : page.articles());
        }
    }

    private static void validateNews(List<PlayerNewsArticle> articles) {
        // A missing field belongs to a legacy project and keeps using the
        // player program's bundled news. An explicit empty list means no news.
        if (articles == null) return;
        if (articles.size() > 50) {
            throw new ProtocolException("Too many player news articles");
        }
        Set<String> ids = new java.util.HashSet<>();
        for (PlayerNewsArticle article : articles) {
            if (article == null || article.id() == null
                    || !NEWS_ID.matcher(article.id()).matches()
                    || !ids.add(article.id())) {
                throw new ProtocolException("Invalid or duplicate player news article ID");
            }
            requireText(article.title(), "player news title", 120);
            requireLength(article.summary(), "player news summary", 300);
            if (article.publishedOn() == null
                    || !ISO_DATE.matcher(article.publishedOn()).matches()) {
                throw new ProtocolException("Invalid player news publication date");
            }
            requireLength(article.markdown(), "player news Markdown", 131_072);
            validateOptionalWebUrl(article.coverUrl(), "player news cover URL");
        }
    }

    private static void validateCustomPage(PlayerCustomPage page) {
        if (page == null) return;
        requireLength(page.navigationLabel(), "custom page navigation label", 12);
        requireLength(page.eyebrow(), "custom page eyebrow", 48);
        requireLength(page.title(), "custom page title", 120);
        requireLength(page.lead(), "custom page lead", 300);
        requireLength(page.markdown(), "custom page Markdown", 131_072);
        if (page.enabled()) {
            requireText(page.navigationLabel(), "custom page navigation label", 12);
            requireText(page.title(), "custom page title", 120);
        }
    }

    private static void validateOptionalWebUrl(String value, String label) {
        if (value == null || value.isBlank()) return;
        requireLength(value, label, 2_048);
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new ProtocolException("Invalid " + label);
            }
        } catch (URISyntaxException e) {
            throw new ProtocolException("Invalid " + label, e);
        }
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
