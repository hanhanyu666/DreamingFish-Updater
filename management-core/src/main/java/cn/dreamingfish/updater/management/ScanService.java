package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ModMetadata;
import cn.dreamingfish.updater.protocol.ModMetadataReader;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class ScanService {
    public static final int PREVIEW_SCHEMA_VERSION = 1;

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;

    public ScanService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.json = json;
    }

    public PublishPreview createPreview(String projectId) {
        ProjectRecord project = database.requireProject(projectId);
        List<ScannedFile> files = scan(project);
        StoredRelease latest = database.latestRelease(projectId).orElse(null);
        List<ManifestFile> previousFiles = latest == null
                ? List.of()
                : database.readManifest(latest).files();
        List<PreviewChange> changes = differences(previousFiles, files);
        long total = files.stream().mapToLong(ScannedFile::size).sum();
        long download = changes.stream().mapToLong(PreviewChange::downloadSize).sum();
        PublishPreview preview = new PublishPreview(
                PREVIEW_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                projectId,
                latest == null ? null : latest.releaseId(),
                Instant.now(),
                files,
                changes,
                total,
                download
        );
        save(preview);
        return preview;
    }

    public PublishPreview load(String projectId) {
        Path path = previewPath(projectId);
        if (!Files.isRegularFile(path)) {
            throw new ManagementException("No publish preview exists for project " + projectId + "; run scan first");
        }
        try {
            PublishPreview preview = json.read(path, PublishPreview.class);
            if (preview.schemaVersion() != PREVIEW_SCHEMA_VERSION || !preview.projectId().equals(projectId)) {
                throw new ManagementException("Publish preview is incompatible or belongs to another project");
            }
            return preview;
        } catch (IOException e) {
            throw new ManagementException("Unable to read publish preview for " + projectId, e);
        }
    }

    public void remove(String projectId) {
        try {
            Files.deleteIfExists(previewPath(projectId));
        } catch (IOException e) {
            throw new ManagementException("Unable to remove the completed publish preview", e);
        }
    }

    List<ScannedFile> scan(ProjectRecord project) {
        validateForcedSyncDirectories(project);
        RuleSet rules = new RuleSet(project.rules());
        List<ScannedFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(project.sourceDirectory())) {
            stream.filter(path -> !path.equals(project.sourceDirectory())).forEach(path -> {
                String relative = project.sourceDirectory().relativize(path).toString().replace('\\', '/');
                relative = PathSafety.normalizeManifestPath(relative);
                final String managedPath = relative;
                RuleSet.Decision decision = rules.decide(relative);
                if (decision.excluded()) {
                    return;
                }
                if (project.rules().forcedSyncDirectories().stream()
                        .anyMatch(directory -> insideDirectory(managedPath, directory))) {
                    decision = RuleSet.Decision.managedDecision(
                            cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED);
                }
                if (Files.isSymbolicLink(path)) {
                    throw new ManagementException("Managed source path cannot be a symbolic link: " + relative);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                files.add(hashStable(path, relative, decision));
            });
        } catch (IOException e) {
            throw new ManagementException("Unable to scan standard modpack directory " + project.sourceDirectory(), e);
        } catch (java.io.UncheckedIOException e) {
            throw new ManagementException("Unable to scan standard modpack directory " + project.sourceDirectory(), e);
        }
        files.sort(Comparator.comparing(ScannedFile::path));
        PathSafety.validateDistinctPaths(files.stream().map(ScannedFile::path).toList());
        return List.copyOf(files);
    }

    private static boolean insideDirectory(String path, String directory) {
        return path.toLowerCase(java.util.Locale.ROOT)
                .startsWith(directory.toLowerCase(java.util.Locale.ROOT) + "/");
    }

    private void validateForcedSyncDirectories(ProjectRecord project) {
        for (String directory : project.rules().forcedSyncDirectories()) {
            try {
                Path source = PathSafety.resolveInside(project.sourceDirectory(), directory);
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(source)) {
                    throw new ManagementException("Forced sync source directory is missing or unsafe: "
                            + directory);
                }
            } catch (IOException e) {
                throw new ManagementException("Unable to validate forced sync source directory: "
                        + directory, e);
            }
        }
    }

    private ScannedFile hashStable(Path file, String relative, RuleSet.Decision decision) {
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String hash = CryptoSupport.sha256(file);
            ModMetadata metadata = ModMetadataReader.read(file).orElse(null);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                throw new ManagementException("Source file changed while scanning: " + relative);
            }
            return new ScannedFile(
                    relative,
                    hash,
                    after.size(),
                    after.lastModifiedTime().toMillis(),
                    decision.policy(),
                    file.getFileSystem().supportedFileAttributeViews().contains("posix") && Files.isExecutable(file),
                    metadata == null ? null : metadata.componentId(),
                    metadata == null ? null : metadata.displayName()
            );
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private List<PreviewChange> differences(List<ManifestFile> previous, List<ScannedFile> current) {
        Map<String, ManifestFile> oldByPath = new HashMap<>();
        previous.forEach(file -> oldByPath.put(file.path(), file));
        Map<String, ScannedFile> newByPath = new HashMap<>();
        current.forEach(file -> newByPath.put(file.path(), file));
        List<PreviewChange> changes = new ArrayList<>();

        for (ScannedFile file : current) {
            ManifestFile old = oldByPath.get(file.path());
            if (old == null) {
                changes.add(new PreviewChange(ChangeKind.ADDED, file.path(), null, file.sha256(), file.size()));
            } else if (!old.sha256().equals(file.sha256()) || old.size() != file.size()) {
                changes.add(new PreviewChange(ChangeKind.MODIFIED, file.path(), old.sha256(), file.sha256(), file.size()));
            } else if (old.policy() != file.policy() || old.executable() != file.executable()) {
                changes.add(new PreviewChange(ChangeKind.POLICY_CHANGED, file.path(), old.sha256(), file.sha256(), 0));
            } else if (!java.util.Objects.equals(old.componentId(), file.componentId())
                    || !java.util.Objects.equals(old.displayName(), file.displayName())) {
                changes.add(new PreviewChange(ChangeKind.METADATA_CHANGED,
                        file.path(), old.sha256(), file.sha256(), 0));
            }
        }
        for (ManifestFile old : previous) {
            if (!newByPath.containsKey(old.path())) {
                changes.add(new PreviewChange(ChangeKind.REMOVED, old.path(), old.sha256(), null, 0));
            }
        }
        changes.sort(Comparator.comparing(PreviewChange::path).thenComparing(change -> change.kind().name()));
        return List.copyOf(changes);
    }

    private void save(PublishPreview preview) {
        try {
            AtomicFiles.write(previewPath(preview.projectId()), json.write(preview));
        } catch (IOException e) {
            throw new ManagementException("Unable to persist publish preview", e);
        }
    }

    private Path previewPath(String projectId) {
        if (!projectId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new ManagementException("Invalid project ID");
        }
        return paths.previews().resolve(projectId + ".json");
    }
}
