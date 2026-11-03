package cn.dreamingfish.updater.protocol;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Signed by the publisher, then explicitly adopted and re-signed by a project. */
public record OfficialPlayerCatalog(int schemaVersion, Instant createdAt, List<Release> releases) {
    public OfficialPlayerCatalog { releases = releases == null ? List.of() : List.copyOf(releases); }
    public void validate() {
        if (schemaVersion != 1 || createdAt == null || releases.size() > 32) throw new ProtocolException("Invalid official player catalogue");
        Set<String> identities = new HashSet<>();
        for (Release release : releases) {
            SemanticVersion.parse(release.version());
            SemanticVersion.parse(release.minimumBootstrapVersion());
            SemanticVersion.parse(release.minimumManagementVersion());
            URI uri = URI.create(release.downloadUrl());
            if ((!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !release.platform().matches("(windows|linux)-x64")
                    || !release.archiveSha256().matches("[0-9a-f]{64}") || release.archiveSize() < 1
                    || release.archiveSize() > 4L * 1024 * 1024 * 1024
                    || release.files().isEmpty() || release.files().size() > 20_000
                    || !identities.add(release.platform())) throw new ProtocolException("Invalid official player release");
            PathSafety.normalizeManifestPath(release.launchPath());
            Set<String> names = new HashSet<>(); long size = 0;
            for (var file : release.files()) {
                String path = PathSafety.normalizeManifestPath(file.path());
                if (!names.add(ManagedPaths.fold(path)) || !file.sha256().matches("[0-9a-f]{64}") || file.size() < 0)
                    throw new ProtocolException("Invalid official player file");
                size = Math.addExact(size, file.size());
            }
            if (size > 4L * 1024 * 1024 * 1024 || release.files().stream().noneMatch(f -> f.path().equals(release.launchPath())))
                throw new ProtocolException("Invalid official player payload");
        }
    }
    public record Release(String version, String platform, String downloadUrl, String archiveSha256,
                          long archiveSize, String launchPath, String minimumBootstrapVersion,
                          String minimumManagementVersion, String changelog, Set<String> requiredCapabilities,
                          List<PlayerProgramFile> files) {
        public Release { files = files == null ? List.of() : List.copyOf(files);
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities); }
    }
}
