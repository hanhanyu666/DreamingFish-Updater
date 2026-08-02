package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestValidatorTest {
    @Test
    void validatesSupportedRelease() {
        assertDoesNotThrow(() -> ManifestValidator.validateRelease(JsonCodecTest.sampleManifest(), Set.of()));
    }

    @Test
    void rejectsOverlongTitleBarBrandNames() {
        ReleaseManifest original = JsonCodecTest.sampleManifest();
        Branding invalid = new Branding(
                original.branding().productName(),
                original.branding().subtitle(),
                original.branding().serverAddress(),
                original.branding().coverObject(),
                original.branding().accentColor(),
                original.branding().secondaryAccentColor(),
                "中".repeat(33),
                "English".repeat(8));
        ReleaseManifest manifest = new ReleaseManifest(
                original.schemaVersion(), original.projectId(),
                original.releaseId(), original.sequence(), original.createdAt(),
                original.displayVersion(), original.minimumPlayerVersion(),
                original.changelog(), original.requiredCapabilities(), invalid,
                original.files());

        assertThrows(ProtocolException.class,
                () -> ManifestValidator.validateRelease(manifest, Set.of()));
    }

    @Test
    void rejectsUnknownRequiredCapability() {
        ReleaseManifest original = JsonCodecTest.sampleManifest();
        ReleaseManifest incompatible = new ReleaseManifest(
                original.schemaVersion(), original.projectId(), original.releaseId(), original.sequence(),
                original.createdAt(), original.displayVersion(), original.minimumPlayerVersion(), original.changelog(),
                Set.of("future-feature"), original.branding(), original.files()
        );
        assertThrows(ProtocolException.class,
                () -> ManifestValidator.validateRelease(incompatible, Set.of()));
    }

    @Test
    void rejectsUnsortedAndCaseCollidingFiles() {
        ReleaseManifest manifest = new ReleaseManifest(
                1, "dreamhaven", "release-1", 1, Instant.now(), "1", "0.1.0", "", Set.of(), Branding.empty(),
                List.of(
                        new ManifestFile("mods/a.jar", "a".repeat(64), 1, FilePolicy.ENFORCED, false),
                        new ManifestFile("Mods/A.jar", "b".repeat(64), 1, FilePolicy.ENFORCED, false)
                )
        );
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateRelease(manifest, Set.of()));
    }

    @Test
    void validatesProjectBindingAndPinnedKey() {
        KeyPair keys = CryptoSupport.generateEd25519KeyPair();
        ProjectBinding binding = new ProjectBinding(
                1, "dreamhaven", "http://127.0.0.1:8080/", CryptoSupport.encodePublicKey(keys.getPublic()),
                "DreamingFishUpdater", "cover.png", Branding.empty()
        );
        assertDoesNotThrow(() -> ManifestValidator.validateBinding(binding));
    }

    @Test
    void rejectsCredentialBearingBaseUrl() {
        KeyPair keys = CryptoSupport.generateEd25519KeyPair();
        ProjectBinding binding = new ProjectBinding(
                1, "dreamhaven", "http://user:secret@example.test/", CryptoSupport.encodePublicKey(keys.getPublic()),
                "DreamingFishUpdater", null, Branding.empty()
        );
        assertThrows(ProtocolException.class, () -> ManifestValidator.validateBinding(binding));
    }

    @Test
    void requiresExplicitCapabilityAndEnforcedFilesForForcedDirectorySync() {
        ManifestFile mod = new ManifestFile(
                "mods/example.jar", "a".repeat(64), 1, FilePolicy.ENFORCED, false);
        ReleaseManifest forced = new ReleaseManifest(
                1, "dreamhaven", "release-1", 1, Instant.now(), "1", "0.1.4", "",
                Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC),
                List.of("mods"), Branding.empty(), List.of(mod));
        assertDoesNotThrow(() -> ManifestValidator.validateRelease(forced,
                Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC)));
        assertThrows(ProtocolException.class,
                () -> ManifestValidator.validateRelease(forced, Set.of()));

        ReleaseManifest missingCapability = new ReleaseManifest(
                1, "dreamhaven", "release-1", 1, Instant.now(), "1", "0.1.4", "",
                Set.of(), List.of("mods"), Branding.empty(), List.of(mod));
        assertThrows(ProtocolException.class,
                () -> ManifestValidator.validateRelease(missingCapability,
                        Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC)));

        ReleaseManifest defaultFile = new ReleaseManifest(
                1, "dreamhaven", "release-1", 1, Instant.now(), "1", "0.1.4", "",
                Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC),
                List.of("mods"), Branding.empty(), List.of(new ManifestFile(
                "mods/example.jar", "a".repeat(64), 1, FilePolicy.DEFAULT, false)));
        assertThrows(ProtocolException.class,
                () -> ManifestValidator.validateRelease(defaultFile,
                        Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC)));
    }

    @Test
    void acceptsLegacyReleaseJsonWithoutForcedDirectoryField() {
        String json = new JsonCodec().writeString(JsonCodecTest.sampleManifest());
        String legacy = json.replace("\"forcedSyncDirectories\":[],", "");
        ReleaseManifest decoded = new JsonCodec().read(
                legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8), ReleaseManifest.class);
        assertDoesNotThrow(() -> ManifestValidator.validateRelease(decoded, Set.of()));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), decoded.forcedSyncDirectories());
    }

    @Test
    void validatesReleasedPathsAndIndividualForcedFilesWithCapabilities() {
        ManifestFile required = new ManifestFile(
                "mods/required.jar", "a".repeat(64), 1,
                FilePolicy.ENFORCED, false);
        ReleaseManifest manifest = new ReleaseManifest(
                1, "dreamhaven", "release-2", 2, Instant.now(),
                "2", "0.1.13", "",
                Set.of(
                        ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
                        ProtocolConstants.CAPABILITY_RELEASED_PATHS),
                List.of(), List.of("mods/required.jar"),
                List.of("config/legacy.toml"), Branding.empty(),
                List.of(required));
        Set<String> supported = Set.of(
                ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
                ProtocolConstants.CAPABILITY_RELEASED_PATHS);

        assertDoesNotThrow(() ->
                ManifestValidator.validateRelease(manifest, supported));
        assertThrows(ProtocolException.class, () ->
                ManifestValidator.validateRelease(manifest, Set.of()));

        ReleaseManifest collision = new ReleaseManifest(
                1, "dreamhaven", "release-3", 3, Instant.now(),
                "3", "0.1.13", "",
                Set.of(ProtocolConstants.CAPABILITY_RELEASED_PATHS),
                List.of(), List.of(), List.of("mods/required.jar"),
                Branding.empty(), List.of(required));
        assertThrows(ProtocolException.class, () ->
                ManifestValidator.validateRelease(collision,
                        Set.of(ProtocolConstants.CAPABILITY_RELEASED_PATHS)));
    }
}
