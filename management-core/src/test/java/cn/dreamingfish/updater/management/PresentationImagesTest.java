package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PresentationImagesTest {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
    @TempDir Path temporary;

    @Test void publishesOnlyReferencedObjectsAndRetainsTheirIdentityAcrossDraftEdits() throws Exception {
        var fixture = new ManagementFixture(temporary);
        fixture.createProject();
        Path source = Files.write(temporary.resolve("upload.png"), PNG);
        fixture.projects.addPresentationImage("demo", "hero.png", source);
        fixture.projects.addPresentationImage("demo", "unused.png", Files.write(temporary.resolve("unused.png"), new byte[]{(byte)137,80,78,71,13,10,26,10,42}));
        ProjectRecord draft = showcase(fixture, "hero.png");
        assertEquals(2, draft.branding().presentationImages().size());
        assertThrows(ManagementException.class, () -> fixture.projects.removePresentationImage("demo", "hero.png"));
        Files.writeString(fixture.source.resolve("options.txt"), "game data");
        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish("demo", "1.0", "0.2.2", "展示图片");
        Branding published = fixture.database.readManifest(release).branding();
        assertEquals(List.of("hero.png"), published.presentationImages().stream().map(PlayerPresentationImage::fileName).toList());
        PlayerPresentationImage original = published.presentationImages().getFirst();
        assertEquals(CryptoSupport.sha256(PNG), original.sha256());
        Path exported = temporary.resolve("static");
        new StaticDistributionService(fixture.paths, fixture.database, fixture.json).exportProject("demo", exported);
        assertArrayEquals(PNG, Files.readAllBytes(exported.resolve("v1/objects/sha256/" + original.sha256())));
        String unusedHash = fixture.database.requireProject("demo").branding().presentationImages().stream()
                .filter(image -> image.fileName().equals("unused.png")).findFirst().orElseThrow().sha256();
        assertFalse(Files.exists(exported.resolve("v1/objects/sha256/" + unusedHash)));
        var edited = fixture.projects.addPresentationImage("demo", "hero.png", Files.write(temporary.resolve("edited.png"), new byte[]{(byte)137,80,78,71,13,10,26,10,1}));
        assertNotEquals(original.sha256(), edited.branding().presentationImages().stream().filter(image -> image.fileName().equals("hero.png")).findFirst().orElseThrow().sha256());
        assertEquals(original, fixture.database.readManifest(release).branding().presentationImages().getFirst());
        assertArrayEquals(PNG, Files.readAllBytes(fixture.objects.require(original.sha256())));
    }

    @Test void refusesMissingAndCorruptImageObjectsBeforeSigning() throws Exception {
        var fixture = new ManagementFixture(temporary);
        fixture.createProject();
        showcase(fixture, "missing.png");
        fixture.scanner.createPreview("demo");
        assertTrue(assertThrows(ManagementException.class, () -> fixture.publisher.publish("demo", "1.0", "0.2.2", "")).getMessage().contains("尚未上传"));
        var project = fixture.projects.addPresentationImage("demo", "missing.png", Files.write(temporary.resolve("image.png"), PNG));
        var image = project.branding().presentationImages().getFirst();
        Files.writeString(fixture.objects.require(image.sha256()), "corrupt");
        fixture.scanner.createPreview("demo");
        assertThrows(ManagementException.class, () -> fixture.publisher.publish("demo", "1.0", "0.2.2", ""));
        assertTrue(fixture.database.listReleases("demo").isEmpty());
    }

    @Test void enforcesUploadLimitsMimeAndSafeNamesAndReportsSettingsChanges() throws Exception {
        var fixture = new ManagementFixture(temporary);
        ProjectRecord before = fixture.createProject();
        Path source = Files.write(temporary.resolve("source.png"), PNG);
        assertThrows(ProtocolException.class, () -> fixture.projects.addPresentationImage("demo", "../escape.png", source));
        assertThrows(ManagementException.class, () -> fixture.projects.addPresentationImage("demo", "wrong.jpg", source));
        assertThrows(ManagementException.class, () -> fixture.projects.addPresentationImage("demo", "empty.png", Files.write(temporary.resolve("empty"), new byte[0])));
        try (var output = new java.io.RandomAccessFile(temporary.resolve("oversize").toFile(), "rw")) { output.setLength(ShowcaseRules.MAX_IMAGE_BYTES + 1); }
        assertThrows(ManagementException.class, () -> fixture.projects.addPresentationImage("demo", "large.png", temporary.resolve("oversize")));
        ProjectRecord after = fixture.projects.addPresentationImage("demo", "safe.png", source);
        assertTrue(ProjectSettingsSnapshot.of(before).changesTo(ProjectSettingsSnapshot.of(after)).stream().anyMatch(change -> change.setting().equals("展示图片")));
        assertTrue(fixture.projects.removePresentationImage("demo", "safe.png").branding().presentationImages().isEmpty());
    }

    @Test void backupRestoresDraftAndPublishedImageObjects() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("original"));
        fixture.createProject();
        var draft = fixture.projects.addPresentationImage("demo", "hero.png", Files.write(temporary.resolve("image.png"), PNG));
        showcase(fixture, "hero.png");
        Files.writeString(fixture.source.resolve("options.txt"), "game data");
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0", "0.2.2", "");
        Path archive = temporary.resolve("backup.dfs-backup");
        new BackupService(fixture.paths, fixture.database, fixture.json).create(archive, "image-test-password".toCharArray());
        var paths = ManagementPaths.at(temporary.resolve("restored"));
        var database = new ManagementDatabase(paths, fixture.json);
        new BackupService(paths, database, fixture.json).restore(archive, "image-test-password".toCharArray(), false);
        var restored = database.requireProject("demo").branding().presentationImages().getFirst();
        assertEquals(draft.branding().presentationImages().getFirst(), restored);
        assertArrayEquals(PNG, Files.readAllBytes(new ObjectStore(paths).require(restored.sha256())));
    }

    @Test void pageCoverAloneIsPublishedExportedAndProtectedFromRemoval() throws Exception {
        var fixture = new ManagementFixture(temporary);
        fixture.createProject();
        fixture.projects.addPresentationImage("demo", "guide.png", Files.write(temporary.resolve("guide.png"), PNG));
        ProjectRecord draft = pageShowcase(fixture, "guide.png");
        assertThrows(ManagementException.class, () -> fixture.projects.removePresentationImage("demo", "guide.png"));
        Files.writeString(fixture.source.resolve("options.txt"), "game data");
        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish("demo", "1.0", "0.2.2", "介绍页封面");
        Branding published = fixture.database.readManifest(release).branding();
        assertEquals(List.of("guide.png"), published.presentationImages().stream().map(PlayerPresentationImage::fileName).toList());
        assertEquals(draft.branding().contentPages(), published.contentPages());
        Branding presentation = new PlayerPresentation(1, "demo", published).branding();
        assertNull(presentation.presentationImages());
        assertEquals(published.contentPages(), presentation.contentPages());
        assertEquals(List.of("生存", "社区"), presentation.contentPages().getFirst().tags());
        Path exported = temporary.resolve("static");
        new StaticDistributionService(fixture.paths, fixture.database, fixture.json).exportProject("demo", exported);
        assertArrayEquals(PNG, Files.readAllBytes(exported.resolve("v1/objects/sha256/" + published.presentationImages().getFirst().sha256())));
    }

    @Test void missingPageCoverCannotBeSilentlyOmittedFromTheSignedRelease() throws Exception {
        var fixture = new ManagementFixture(temporary);
        fixture.createProject();
        pageShowcase(fixture, "missing-cover.png");
        fixture.scanner.createPreview("demo");
        var error = assertThrows(ManagementException.class, () -> fixture.publisher.publish("demo", "1.0", "0.2.2", ""));
        assertTrue(error.getMessage().contains("missing-cover.png"), error.getMessage());
        assertTrue(fixture.database.listReleases("demo").isEmpty());
    }

    private ProjectRecord pageShowcase(ManagementFixture fixture, String imageFile) {
        var page = new PlayerContentPage("guide", "介绍", false, "ABOUT", "服务器介绍", "欢迎", "", List.of(),
                "BLOCKS", null, List.of(), imageFile, "https://example.com/fallback.png", List.of("生存", "社区"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.valueToTree(fixture.database.requireProject("demo").branding());
        node.set("contentPages", mapper.valueToTree(List.of(page)));
        return fixture.projects.configure("demo", null, null, fixture.json.read(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), Branding.class), null);
    }

    private ProjectRecord showcase(ManagementFixture fixture, String imageFile) {
        Branding original = fixture.database.requireProject("demo").branding();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.valueToTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("spotlights", mapper.valueToTree(List.of(Map.of("id", "welcome", "title", "欢迎", "imageFile", imageFile))));
        return fixture.projects.configure("demo", null, null, fixture.json.read(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), Branding.class), null);
    }
}
