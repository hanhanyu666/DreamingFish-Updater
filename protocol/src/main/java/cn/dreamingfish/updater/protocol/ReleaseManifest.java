package cn.dreamingfish.updater.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ReleaseManifest(
        int schemaVersion,
        String projectId,
        String releaseId,
        long sequence,
        Instant createdAt,
        String displayVersion,
        String minimumPlayerVersion,
        String changelog,
        Set<String> requiredCapabilities,
        List<String> forcedSyncDirectories,
        List<String> forcedSyncFiles,
        List<String> releasedPaths,
        Branding branding,
        List<ManifestFile> files
) {
    public ReleaseManifest {
        requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        forcedSyncDirectories = forcedSyncDirectories == null ? List.of() : List.copyOf(forcedSyncDirectories);
        forcedSyncFiles = forcedSyncFiles == null ? List.of() : List.copyOf(forcedSyncFiles);
        releasedPaths = releasedPaths == null ? List.of() : List.copyOf(releasedPaths);
        files = files == null ? List.of() : List.copyOf(files);
        branding = branding == null ? Branding.empty() : branding;
        changelog = changelog == null ? "" : changelog;
    }

    public ReleaseManifest(int schemaVersion, String projectId, String releaseId, long sequence,
                           Instant createdAt, String displayVersion, String minimumPlayerVersion,
                           String changelog, Set<String> requiredCapabilities,
                           List<String> forcedSyncDirectories, Branding branding,
                           List<ManifestFile> files) {
        this(schemaVersion, projectId, releaseId, sequence, createdAt, displayVersion,
                minimumPlayerVersion, changelog, requiredCapabilities, forcedSyncDirectories,
                List.of(), List.of(), branding, files);
    }

    public ReleaseManifest(int schemaVersion, String projectId, String releaseId, long sequence,
                           Instant createdAt, String displayVersion, String minimumPlayerVersion,
                           String changelog, Set<String> requiredCapabilities, Branding branding,
                           List<ManifestFile> files) {
        this(schemaVersion, projectId, releaseId, sequence, createdAt, displayVersion,
                minimumPlayerVersion, changelog, requiredCapabilities, List.of(), List.of(),
                List.of(), branding, files);
    }
}
