package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishServiceTest {
    @TempDir
    Path temporary;

    @Test
    void carriesTitleBarBrandingThroughBindingCoverAndPublishedManifest()
            throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        ProjectRecord project = fixture.createProject();
        Branding old = project.branding();
        Branding custom = new Branding(
                old.productName(), old.subtitle(), old.serverAddress(), null,
                old.accentColor(), old.secondaryAccentColor(),
                "星河服", "StarRiver");
        project = fixture.projects.configure(
                project.id(), null, null, custom, null);

        Path cover = temporary.resolve("cover.png");
        Files.writeString(cover, "cover-content");
        project = fixture.projects.setCover(project.id(), cover);
        assertEquals("星河服", project.branding().brandName());
        assertEquals("StarRiver", project.branding().brandEnglishName());
        assertEquals("星河服", fixture.projects.bindingFor(
                project, "DreamingFishUpdater", null)
                .fallbackBranding().brandName());

        Files.writeString(fixture.source.resolve("options.txt"), "music:1");
        fixture.scanner.createPreview(project.id());
        StoredRelease release = fixture.publisher.publish(
                project.id(), "1.0.0", "0.1.0", "Brand test");
        Branding published = fixture.database.readManifest(release).branding();
        assertEquals("星河服", published.brandName());
        assertEquals("StarRiver", published.brandEnglishName());
    }

    @Test
    void publishesSignedImmutableReleasesAndRollsBackAsANewRelease() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        ProjectRecord project = fixture.createProject();
        Files.createDirectories(fixture.source.resolve("mods"));
        Files.writeString(fixture.source.resolve("mods/example.jar"), "version-one");
        Files.writeString(fixture.source.resolve("options.txt"), "music:1");

        PublishPreview firstPreview = fixture.scanner.createPreview("demo");
        assertEquals(2, firstPreview.changes().size());
        assertEquals(FilePolicy.DEFAULT,
                firstPreview.files().stream().filter(file -> file.path().equals("options.txt")).findFirst().orElseThrow().policy());
        StoredRelease first = fixture.publisher.publish("demo", "1.0.0", "0.1.0", "First release");
        assertEquals(1, first.sequence());

        byte[] manifestBytes = Files.readAllBytes(first.manifestPath());
        assertTrue(CryptoSupport.verify(
                manifestBytes,
                Base64.getDecoder().decode(first.signature()),
                CryptoSupport.decodePublicKey(project.publicKey())
        ));
        ReleaseManifest firstManifest = fixture.database.readManifest(first);
        assertEquals(2, firstManifest.files().size());
        for (var file : firstManifest.files()) {
            fixture.objects.verify(fixture.objects.require(file.sha256()), file.sha256(), file.size());
        }

        Files.writeString(fixture.source.resolve("mods/example.jar"), "version-two");
        fixture.scanner.createPreview("demo");
        StoredRelease second = fixture.publisher.publish("demo", "2.0.0", "0.1.0", "Second release");
        assertEquals(2, second.sequence());
        assertNotEquals(first.releaseId(), second.releaseId());

        StoredRelease rollback = fixture.publisher.rollback("demo", first.releaseId(), "2.0.1", "Restore v1 content");
        assertEquals(3, rollback.sequence());
        assertEquals(firstManifest.files(), fixture.database.readManifest(rollback).files());
        assertEquals(3, fixture.database.listReleases("demo").size());
    }

    @Test
    void refusesToPublishWhenSourceChangedAfterPreview() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        Files.createDirectories(fixture.source.resolve("config"));
        Path config = fixture.source.resolve("config/server.toml");
        Files.writeString(config, "enabled=true", StandardCharsets.UTF_8);
        fixture.scanner.createPreview("demo");
        Files.writeString(config, "enabled=false", StandardCharsets.UTF_8);

        ManagementException error = assertThrows(ManagementException.class,
                () -> fixture.publisher.publish("demo", "1.0.0", "0.1.0", "Changed"));
        assertTrue(error.getMessage().contains("changed"));
        assertTrue(fixture.database.listReleases("demo").isEmpty());
    }

    @Test
    void refusesSourceDirectoryInsideManagementData() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        assertThrows(ManagementException.class, () -> fixture.projects.create(
                "unsafe", "Unsafe", fixture.paths.objects(), "http://127.0.0.1:8080",
                null, null
        ));
    }

    @Test
    void refusesToScanAMissingForcedDirectoryButAllowsAnExplicitEmptyDirectory() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        ProjectRecord project = fixture.createProject();
        fixture.projects.configure("demo", null, null, null,
                project.rules().withForcedSyncDirectories(java.util.List.of("mods")));

        assertThrows(ManagementException.class,
                () -> fixture.scanner.createPreview("demo"));

        Files.createDirectories(fixture.source.resolve("mods"));
        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.4", "Empty forced mods");
        ReleaseManifest manifest = fixture.database.readManifest(release);
        assertEquals(java.util.List.of("mods"), manifest.forcedSyncDirectories());
        assertTrue(manifest.requiredCapabilities().contains(
                cn.dreamingfish.updater.protocol.ProtocolConstants
                        .CAPABILITY_FORCED_DIRECTORY_SYNC));
    }

    @Test
    void automaticallyPublishesStableModMetadataFromTheJar() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        Path jar = fixture.source.resolve("mods/render-helper.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            output.write("""
                    modLoader="javafml"
                    loaderVersion="[47,)"
                    license="MIT"
                    [[mods]]
                    modId="render_helper"
                    displayName="渲染兼容助手"
                    version="1.0"
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        fixture.scanner.createPreview("demo");
        StoredRelease stored = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.7", "metadata");
        var file = fixture.database.readManifest(stored).files().getFirst();

        assertEquals("render_helper", file.componentId());
        assertEquals("渲染兼容助手", file.displayName());
    }

    @Test
    void requiresAnExplicitRemovalDecisionAndCarriesReleasedPathsForward()
            throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        Path legacy = fixture.source.resolve("mods/legacy-renderer.jar");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish(
                "demo", "1.0.0", "0.1.0", "initial");

        Files.delete(legacy);
        PublishPreview removed = fixture.scanner.createPreview("demo");
        PreviewChange change = removed.changes().stream()
                .filter(item -> item.kind() == ChangeKind.REMOVED)
                .findFirst().orElseThrow();
        assertEquals(null, change.removalAction());
        assertThrows(ManagementException.class, () ->
                fixture.publisher.publish(
                        "demo", "2.0.0", "0.1.13", "undecided"));

        fixture.scanner.decideRemovals("demo", java.util.List.of(
                new RemovalDecision(
                        "mods/legacy-renderer.jar", RemovalAction.RELEASE)));
        StoredRelease second = fixture.publisher.publish(
                "demo", "2.0.0", "0.1.13", "release ownership");
        ReleaseManifest secondManifest = fixture.database.readManifest(second);
        assertEquals(java.util.List.of("mods/legacy-renderer.jar"),
                secondManifest.releasedPaths());
        assertTrue(secondManifest.requiredCapabilities().contains(
                cn.dreamingfish.updater.protocol.ProtocolConstants
                        .CAPABILITY_RELEASED_PATHS));

        fixture.scanner.createPreview("demo");
        StoredRelease third = fixture.publisher.publish(
                "demo", "3.0.0", "0.1.13", "carry release");
        assertEquals(java.util.List.of("mods/legacy-renderer.jar"),
                fixture.database.readManifest(third).releasedPaths());

        Files.writeString(legacy, "managed-again");
        fixture.scanner.createPreview("demo");
        StoredRelease fourth = fixture.publisher.publish(
                "demo", "4.0.0", "0.1.13", "manage again");
        assertTrue(fixture.database.readManifest(fourth)
                .releasedPaths().isEmpty());
    }

    @Test
    void publishesAndValidatesIndividualForcedSyncFiles() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        ProjectRecord project = fixture.createProject();
        Path required = fixture.source.resolve("mods/server-required.jar");
        Path optional = fixture.source.resolve("mods/optional-map.jar");
        Files.createDirectories(required.getParent());
        Files.writeString(required, "required");
        Files.writeString(optional, "optional");
        fixture.projects.configure(
                "demo", null, null, null,
                project.rules().withForcedSyncFiles(
                        java.util.List.of("mods/server-required.jar")));

        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish(
                "demo", "1.0.0", "0.1.13", "forced file");
        ReleaseManifest manifest = fixture.database.readManifest(release);
        assertEquals(java.util.List.of("mods/server-required.jar"),
                manifest.forcedSyncFiles());
        assertTrue(manifest.requiredCapabilities().contains(
                cn.dreamingfish.updater.protocol.ProtocolConstants
                        .CAPABILITY_FORCED_FILE_SYNC));
        assertFalse(manifest.forcedSyncFiles().contains(
                "mods/optional-map.jar"));

        Files.delete(required);
        assertThrows(ManagementException.class,
                () -> fixture.scanner.createPreview("demo"));
    }
}
