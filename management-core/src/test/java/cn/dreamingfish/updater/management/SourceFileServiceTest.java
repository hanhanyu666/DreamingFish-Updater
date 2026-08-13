package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFileServiceTest {
    @TempDir
    Path temporary;

    @Test
    void importsUploadsArchivesAndRecordsAReleasedManagedFile() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        ProjectRecord project = fixture.createProject();
        Path render = fixture.source.resolve("mods/render.jar");
        Files.createDirectories(render.getParent());
        Files.writeString(render, "render-v1");
        fixture.projects.configure("demo", null, null, null,
                project.rules().withForcedSyncFiles(List.of("mods/render.jar")));
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0", "0.1.13", "First");

        SourceFileService service = new SourceFileService(
                fixture.paths, fixture.database, fixture.json);
        SourceFileService.SourceFileEntry listed = service.list("demo").getFirst();
        assertEquals("mods/render.jar", listed.path());
        assertTrue(listed.forcedByFile());
        assertTrue(listed.published());

        SourceFileService.SourceMutation removed = service.remove(
                "demo", "mods/render.jar", RemovalAction.RELEASE);
        assertFalse(Files.exists(render));
        assertTrue(Files.isRegularFile(removed.archivedPreviousFile()));
        assertEquals("render-v1", Files.readString(removed.archivedPreviousFile()));
        assertEquals(RemovalAction.RELEASE, removed.preview().changes().stream()
                .filter(change -> change.path().equals("mods/render.jar"))
                .findFirst().orElseThrow().removalAction());
        assertTrue(fixture.database.requireProject("demo")
                .rules().forcedSyncFiles().isEmpty());

        Path external = temporary.resolve("new-config.toml");
        Files.writeString(external, "from-server");
        SourceFileService.SourceMutation imported = service.importFile(
                "demo", external, "config", false);
        assertEquals("config/new-config.toml", imported.path());
        assertEquals("from-server", Files.readString(
                fixture.source.resolve("config/new-config.toml")));

        byte[] uploaded = "from-browser".getBytes(StandardCharsets.UTF_8);
        SourceFileService.SourceMutation uploadedWithoutScan = service.upload(
                "demo", "mods/browser.jar", new ByteArrayInputStream(uploaded),
                uploaded.length, false, false);
        assertNull(uploadedWithoutScan.preview());
        assertEquals("from-browser", Files.readString(
                fixture.source.resolve("mods/browser.jar")));

        Files.writeString(external, "replacement");
        SourceFileService.SourceMutation overwritten = service.importFile(
                "demo", external, "config", true);
        assertTrue(Files.isRegularFile(overwritten.archivedPreviousFile()));
        assertEquals("from-server", Files.readString(
                overwritten.archivedPreviousFile()));
        assertEquals("replacement", Files.readString(
                fixture.source.resolve("config/new-config.toml")));
    }

    @Test
    void interruptedUploadRemovesPartialFileAndPreservesExistingTarget() throws Exception {
        ManagementFixture fixture = new ManagementFixture(
                temporary.resolve("interrupted-upload"));
        fixture.createProject();
        SourceFileService service = new SourceFileService(
                fixture.paths, fixture.database, fixture.json);
        byte[] content = "browser connection was interrupted"
                .getBytes(StandardCharsets.UTF_8);

        ManagementException newFileFailure = assertThrows(
                ManagementException.class,
                () -> service.upload(
                        "demo", "mods/interrupted.jar",
                        interrupted(content, 7), content.length, false, false));
        assertTrue(newFileFailure.getMessage().contains("Unable to store"));
        assertFalse(Files.exists(fixture.source.resolve("mods/interrupted.jar")));
        assertNoUploadParts(fixture.source);

        Path existing = fixture.source.resolve("mods/existing.jar");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "original-file");
        assertThrows(ManagementException.class,
                () -> service.upload(
                        "demo", "mods/existing.jar",
                        interrupted(content, 7), content.length, true, false));
        assertEquals("original-file", Files.readString(existing));
        assertNoUploadParts(fixture.source);
    }

    @Test
    void createsAndListsEmptyUploadDirectoriesSafely() throws Exception {
        ManagementFixture fixture = new ManagementFixture(
                temporary.resolve("directories"));
        fixture.createProject();
        SourceFileService service = new SourceFileService(
                fixture.paths, fixture.database, fixture.json);

        assertEquals("resourcepacks/server", service.createDirectory(
                "demo", "resourcepacks/server"));
        assertTrue(Files.isDirectory(
                fixture.source.resolve("resourcepacks/server")));
        assertTrue(service.listDirectories("demo").containsAll(List.of(
                "resourcepacks", "resourcepacks/server")));
        assertThrows(ManagementException.class, () ->
                service.createDirectory("demo", "resourcepacks/server"));
        assertThrows(RuntimeException.class, () ->
                service.createDirectory("demo", "../outside"));
    }

    @Test
    void forcedDirectoryFileCannotBeReleasedFromManagement() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("forced"));
        ProjectRecord project = fixture.createProject();
        Path mod = fixture.source.resolve("mods/required.jar");
        Files.createDirectories(mod.getParent());
        Files.writeString(mod, "required");
        fixture.projects.configure("demo", null, null, null,
                project.rules().withForcedSyncDirectories(List.of("mods")));
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0", "0.1.13", "First");

        SourceFileService service = new SourceFileService(
                fixture.paths, fixture.database, fixture.json);
        ManagementException failure = assertThrows(ManagementException.class,
                () -> service.remove("demo", "mods/required.jar",
                        RemovalAction.RELEASE));
        assertTrue(failure.getMessage().contains("forced sync directory"));
        assertTrue(Files.isRegularFile(mod));
    }

    @Test
    void batchRemovalArchivesAllFilesAndScansOnlyTheFinalState() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("batch"));
        ProjectRecord project = fixture.createProject();
        Path first = fixture.source.resolve("mods/first.jar");
        Path second = fixture.source.resolve("config/second.toml");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        fixture.projects.configure("demo", null, null, null,
                project.rules().withForcedSyncFiles(List.of(
                        "mods/first.jar", "config/second.toml")));
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0", "0.1.13", "First");

        SourceFileService service = new SourceFileService(
                fixture.paths, fixture.database, fixture.json);
        SourceFileService.SourceBatchMutation result = service.removeBatch(
                "demo", List.of(
                        new SourceFileService.SourceRemoval(
                                "mods/first.jar", RemovalAction.DELETE),
                        new SourceFileService.SourceRemoval(
                                "config/second.toml", RemovalAction.DELETE)));

        assertEquals(2, result.removed().size());
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertTrue(result.removed().stream().allMatch(file ->
                Files.isRegularFile(file.archivedPreviousFile())));
        assertEquals(2, result.preview().changes().stream()
                .filter(change -> change.removalAction() == RemovalAction.DELETE)
                .count());
        assertTrue(fixture.database.requireProject("demo")
                .rules().forcedSyncFiles().isEmpty());
    }

    private static InputStream interrupted(byte[] content, int failAfter) {
        return new InputStream() {
            private int offset;

            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                int read = read(single, 0, 1);
                return read < 0 ? -1 : Byte.toUnsignedInt(single[0]);
            }

            @Override
            public int read(byte[] buffer, int start, int length) throws IOException {
                if (offset >= failAfter) {
                    throw new IOException("simulated browser disconnect");
                }
                if (offset >= content.length) return -1;
                int count = Math.min(length,
                        Math.min(failAfter - offset, content.length - offset));
                System.arraycopy(content, offset, buffer, start, count);
                offset += count;
                return count;
            }
        };
    }

    private static void assertNoUploadParts(Path source) throws IOException {
        try (var files = Files.walk(source)) {
            assertFalse(files.anyMatch(path ->
                    path.getFileName().toString().startsWith(".dfs-upload-")
                            && path.getFileName().toString().endsWith(".part")));
        }
    }
}
