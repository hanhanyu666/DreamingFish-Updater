package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.engine.ArchiveCatalog;
import cn.dreamingfish.updater.engine.ArchiveReason;
import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.engine.UpdateEngine;
import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.Withdrawal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SimplifiedMaintenanceTest {
    @TempDir Path temporary;

    @Test void joiningFromOldPacksSkippingReleasesAndReinstallingUseTheSamePersistentRemoval() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        var project = fixture.createProject();
        for (String id : List.of("a", "b", "c")) jar(fixture.source.resolve("mods/" + id + ".jar"), id, "1");
        publish(fixture, "1.0");
        Path d1 = jar(fixture.source.resolve("mods/d-1.jar"), "d", "1");
        byte[] firstD = Files.readAllBytes(d1);
        jar(fixture.source.resolve("mods/e.jar"), "e", "1");
        StoredRelease two = publish(fixture, "2.0");
        Files.delete(d1);
        StoredRelease three = publish(fixture, "3.0");
        assertTrue(fixture.database.readManifest(three).withdrawals().stream().anyMatch(Withdrawal::removal));
        Path d2 = jar(fixture.source.resolve("mods/d-2.jar"), "d", "2");
        byte[] secondD = Files.readAllBytes(d2);
        StoredRelease four = publish(fixture, "4.0");
        assertTrue(fixture.database.readManifest(four).withdrawals().isEmpty(), "re-provision ends ordinary removal");
        Files.delete(d2);
        jar(fixture.source.resolve("mods/f.jar"), "f", "1");
        StoredRelease five = publish(fixture, "5.0");
        var target = fixture.database.readManifest(five);
        assertTrue(target.withdrawals().getFirst().items().stream().anyMatch(item -> item.sha256().equals(CryptoSupport.sha256(firstD))));

        try (PublicFileServer server = new PublicFileServer(fixture.database, fixture.objects, new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            ProjectBinding binding = new ProjectBinding(1, "demo", "http://127.0.0.1:" + server.address().getPort(),
                    project.publicKey(), "DreamingFishUpdater", null, project.branding());
            UpdateEngine engine = new UpdateEngine();
            for (StoredRelease baseline : List.of(two, three, four)) {
                Path instance = Files.createDirectories(temporary.resolve("from-" + baseline.displayVersion()));
                Path home = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
                new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json).prepare("demo", baseline.releaseId(), instance, home);
                LocalFileOverrides exemption = new LocalFileOverrides(Set.of(), Set.of(), Set.of(), Set.of("d"), java.util.Map.of(), Set.of());
                var request = new UpdateRequest(instance, home, binding, "0.2.0", ProtocolConstants.RELEASE_CAPABILITIES,
                        null, null, null, null, exemption);
                var updated = engine.update(request, null);
                assertFalse(Files.exists(instance.resolve("mods/d-1.jar")));
                assertFalse(Files.exists(instance.resolve("mods/d-2.jar")));
                assertTrue(Files.exists(instance.resolve("mods/f.jar")));
                assertEquals(five.releaseId(), updated.release().releaseId());
                // Both renamed known content and a never-published version of the removed mod are covered.
                Files.write(instance.resolve("mods/renamed.jar"), secondD);
                jar(instance.resolve("mods/player-d-9.jar"), "d", "9");
                var repeated = engine.update(request, null);
                assertFalse(Files.exists(instance.resolve("mods/renamed.jar")));
                assertFalse(Files.exists(instance.resolve("mods/player-d-9.jar")));
                assertEquals(2, repeated.archived().size());
                assertTrue(repeated.archived().stream().allMatch(file -> file.reason() == ArchiveReason.OWNER_REMOVED));
                ArchiveCatalog backups = new ArchiveCatalog(instance, home);
                var archive = backups.list().stream().filter(item -> item.directory().equals(repeated.archiveDirectory())).findFirst().orElseThrow();
                assertFalse(backups.check(archive.id(), "mods/renamed.jar", target, exemption).allowed());
                // A different valid mod placed at a formerly official filename must not be retired as D.
                jar(instance.resolve("mods/d-2.jar"), "personal", "1");
                engine.update(request, null);
                assertTrue(Files.exists(instance.resolve("mods/d-2.jar")));
            }
        }
    }

    @Test void initialFilesAreRemovedPersistentlyAndStoppingRemovalReleasesOldBaselines() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        Files.writeString(fixture.source.resolve("options.txt"), "guiScale:2");
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        policies.setPreset("demo", "options.txt", false, MaintenancePreset.INITIAL);
        publish(fixture, "1.0");
        Files.delete(fixture.source.resolve("options.txt"));
        var removed = fixture.database.readManifest(publish(fixture, "2.0"));
        Withdrawal removal = removed.withdrawals().getFirst();
        assertTrue(removal.removal());
        policies.revokeWithdrawal("demo", removal.id());
        var released = fixture.database.readManifest(publish(fixture, "3.0"));
        assertTrue(released.withdrawals().isEmpty());
        assertEquals(List.of("options.txt"), released.releasedPaths());
        assertTrue(fixture.database.requireProject("demo").rules().withdrawals().isEmpty());
    }

    @Test void currentProblemVersionCanBeRemovedAndPublishedInOneWorkflow() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        fixture.createProject();
        Path bad = jar(fixture.source.resolve("mods/d.jar"), "d", "1");
        String hash = CryptoSupport.sha256(bad);
        publish(fixture, "4.0");
        ProjectPolicyService policies = new ProjectPolicyService(fixture.paths, fixture.database);
        policies.withdraw("demo", "导致崩溃", List.of(new ProjectPolicyService.VersionReference("mods/d.jar", hash)));
        assertFalse(Files.exists(bad));
        var fixed = fixture.database.readManifest(publish(fixture, "5.0"));
        assertTrue(fixed.files().isEmpty());
        assertEquals(Withdrawal.Kind.VERSION, fixed.withdrawals().getFirst().kind());
        assertEquals(List.of("mods/d.jar"), fixed.releasedPaths());
    }

    private static StoredRelease publish(ManagementFixture fixture, String version) {
        fixture.scanner.createPreview("demo");
        return fixture.publisher.publish("demo", version, "0.2.0", version);
    }

    @Test void legacyDeletionsMigrateOnceAndDoNotReappearAfterAnExplicitCancellation() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary);
        var project = fixture.createProject();
        Path d = jar(fixture.source.resolve("mods/d.jar"), "d", "4");
        var file = new cn.dreamingfish.updater.protocol.ManifestFile("mods/d.jar", CryptoSupport.sha256(d), Files.size(d),
                cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, false, "d", "d", MaintenancePreset.SYNC, null, "4");
        fixture.objects.importFile(d);
        commitLegacy(fixture, project, "old4", 1, List.of(file));
        Files.delete(d);
        commitLegacy(fixture, fixture.database.requireProject("demo"), "old5", 2, List.of());
        var rules = fixture.database.requireProject("demo").rules();
        var legacy = new ProjectRules(rules.rules(), List.of(), List.of(), rules.presets(), rules.cleanupDirectories(),
                rules.optionalGroups(), rules.withdrawals(), rules.corrections(), false);
        fixture.database.updateProject("demo", project.displayName(), project.sourceDirectory(), project.publicBaseUrl(), project.branding(), legacy);
        var preview = fixture.scanner.createPreview("demo");
        assertTrue(preview.rules().simplified());
        Withdrawal pending = preview.rules().withdrawals().getFirst();
        assertTrue(pending.removal());
        assertTrue(preview.policyChanges().stream().anyMatch(change -> change.kind().equals("WITHDRAWAL")));
        new ProjectPolicyService(fixture.paths, fixture.database).revokeWithdrawal("demo", pending.id());
        assertTrue(fixture.scanner.createPreview("demo").rules().withdrawals().isEmpty(), "migration must not undo cancellation");
    }

    private static void commitLegacy(ManagementFixture fixture, ProjectRecord project, String id, long sequence,
                                     List<cn.dreamingfish.updater.protocol.ManifestFile> files) throws Exception {
        var manifest = new cn.dreamingfish.updater.protocol.ReleaseManifest(1, "demo", id, sequence, Instant.now(), id,
                "0.2.0", "legacy", Set.of(ProtocolConstants.CAPABILITY_MAINTENANCE_POLICY), List.of(), List.of(), List.of(),
                project.branding(), files, List.of(), List.of(), List.of(), List.of(), List.of());
        byte[] bytes = fixture.json.writePretty(manifest);
        String signature = java.util.Base64.getEncoder().encodeToString(CryptoSupport.sign(bytes, new ProjectKeyStore(fixture.paths).load(project)));
        Path directory = fixture.paths.manifestDirectory("demo", id);
        Files.createDirectories(directory);
        Files.write(directory.resolve("manifest.json"), bytes);
        Files.writeString(directory.resolve("manifest.sig"), signature);
        fixture.database.commitRelease(manifest, signature, CryptoSupport.sha256(bytes), directory.resolve("manifest.json"));
    }

    static Path jar(Path path, String id, String version) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + id
                    + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
