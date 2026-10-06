package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.engine.UpdateEngine;
import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Isolated regression probes for the repaired behavior and the remaining withdrawal gap. */
public final class ManagementAuditProbe {
    private static final Set<String> CAPABILITIES = Set.of(
            ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC,
            ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
            ProtocolConstants.CAPABILITY_RELEASED_PATHS);

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        releasedPathsOnRollback(Files.createTempDirectory(output, "rollback-"));
        policyChangeAfterPreview(Files.createTempDirectory(output, "preview-"));
    }

    private static void releasedPathsOnRollback(Path root) throws Exception {
        ManagementFixture fixture = new ManagementFixture(root.resolve("management"));
        ProjectRecord project = fixture.createProject();
        fixture.scanner.createPreview(project.id());
        StoredRelease empty = fixture.publisher.publish(project.id(), "1.0.0", "0.1.0", "Empty");

        Path sourceMod = fixture.source.resolve("mods/retired.jar");
        Files.createDirectories(sourceMod.getParent());
        Files.writeString(sourceMod, "owned by the player after release management");
        fixture.scanner.createPreview(project.id());
        StoredRelease managed = fixture.publisher.publish(project.id(), "2.0.0", "0.1.0", "Managed");
        Path oldInstance = prepare(fixture, managed, root.resolve("old-instance"));
        Path releasedInstance = prepare(fixture, managed, root.resolve("released-instance"));

        Files.delete(sourceMod);
        fixture.scanner.createPreview(project.id());
        fixture.scanner.decideRemovals(project.id(),
                List.of(new RemovalDecision("mods/retired.jar", RemovalAction.RELEASE)));
        StoredRelease released = fixture.publisher.publish(project.id(), "3.0.0", "0.1.0", "Release ownership");

        PublishPreview afterRelease = fixture.scanner.createPreview(project.id());
        boolean deleteDecisionAccepted;
        try {
            fixture.scanner.decideRemovals(project.id(),
                    List.of(new RemovalDecision("mods/retired.jar", RemovalAction.DELETE)));
            deleteDecisionAccepted = true;
        } catch (ManagementException expected) {
            deleteDecisionAccepted = false;
        }
        System.out.println("DELETE_AFTER_RELEASE previewChanges=" + afterRelease.changes().size()
                + " deleteDecisionAccepted=" + deleteDecisionAccepted);

        try (PublicFileServer server = new PublicFileServer(fixture.database, fixture.objects,
                new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            ProjectBinding binding = binding(project, server.address().getPort());
            UpdateEngine engine = new UpdateEngine();
            engine.update(request(releasedInstance, binding), null);
            StoredRelease rollback = fixture.publisher.rollback(project.id(), empty.releaseId(),
                    "4.0.0", "Rollback to the empty release");
            engine.update(request(oldInstance, binding), null);
            engine.update(request(releasedInstance, binding), null);
            if (!Files.exists(oldInstance.resolve("mods/retired.jar"))
                    || !Files.exists(releasedInstance.resolve("mods/retired.jar"))) {
                throw new AssertionError("Rollback lost a released player copy");
            }
            System.out.println("RELEASE_ROLLBACK releasedPathsBefore="
                    + fixture.database.readManifest(released).releasedPaths()
                    + " releasedPathsAfter=" + fixture.database.readManifest(rollback).releasedPaths()
                    + " oldBaselineFileExists=" + Files.exists(oldInstance.resolve("mods/retired.jar"))
                    + " releasedBaselineFileExists=" + Files.exists(releasedInstance.resolve("mods/retired.jar")));
        }
    }

    private static void policyChangeAfterPreview(Path root) throws Exception {
        ManagementFixture fixture = new ManagementFixture(root.resolve("management"));
        ProjectRecord project = fixture.createProject();
        Path official = fixture.source.resolve("mods/official.jar");
        Files.createDirectories(official.getParent());
        Files.writeString(official, "official");
        fixture.scanner.createPreview(project.id());
        StoredRelease first = fixture.publisher.publish(project.id(), "1.0.0", "0.1.0", "Ordinary sync");
        Path instance = prepare(fixture, first, root.resolve("instance"));
        Files.writeString(instance.resolve("mods/custom.jar"), "player custom mod");

        PublishPreview oldPreview = fixture.scanner.createPreview(project.id());
        fixture.projects.configure(project.id(), null, null, null,
                project.rules().withForcedSyncDirectories(List.of("mods")));
        boolean rejected = false;
        try { fixture.publisher.publish(project.id(), "2.0.0", "0.1.0", "Stale confirmation",
                oldPreview.previewId(), oldPreview.confirmationDigest()); }
        catch (ManagementException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Changed rules accepted an old preview");
        PublishPreview current = fixture.scanner.createPreview(project.id());
        StoredRelease second = fixture.publisher.publish(project.id(), "2.0.0", "0.1.0", "Forced sync");
        try (PublicFileServer server = new PublicFileServer(fixture.database, fixture.objects,
                new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            UpdateResult result = new UpdateEngine().update(
                    request(instance, binding(project, server.address().getPort())), null);
            System.out.println("POLICY_PREVIEW oldPreviewChanges=" + oldPreview.changes().size()
                    + " oldPreviewRejected=" + rejected + " policyChangeCount=" + current.policyChanges().size()
                    + " publishedForcedDirectories="
                    + fixture.database.readManifest(second).forcedSyncDirectories()
                    + " customModArchived=" + result.archivedFiles().contains(Path.of("mods/custom.jar")));
        }
    }

    private static Path prepare(ManagementFixture fixture, StoredRelease release, Path instance)
            throws Exception {
        Files.createDirectories(instance);
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        new BundledReleasePreparer(fixture.paths, fixture.database, fixture.json)
                .prepare("demo", release.releaseId(), instance, playerHome);
        return instance;
    }

    private static ProjectBinding binding(ProjectRecord project, int port) {
        return new ProjectBinding(ProtocolConstants.BINDING_SCHEMA_VERSION, project.id(),
                "http://127.0.0.1:" + port, project.publicKey(), "DreamingFishUpdater", null,
                project.branding());
    }

    private static UpdateRequest request(Path instance, ProjectBinding binding) {
        return UpdateRequest.defaults(instance, instance.resolve("DreamingFishUpdater"),
                binding, "0.1.40", CAPABILITIES);
    }
}
