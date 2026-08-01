package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.PathSafety;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes only updater-owned, reproducible data. Minecraft instance content and forced-sync
 * archives are deliberately outside every cleanup root used by this class.
 */
final class PlayerStorageMaintenance {
    static final Duration INTERRUPTED_DOWNLOAD_RETENTION = Duration.ofDays(7);
    private static final Pattern PARTIAL_NAME = Pattern.compile("[0-9a-f]{64}\\.part");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Clock clock;

    PlayerStorageMaintenance() {
        this(Clock.systemUTC());
    }

    PlayerStorageMaintenance(Clock clock) {
        this.clock = clock;
    }

    void cleanExpiredStaging(EnginePaths paths) {
        Instant cutoff = clock.instant().minus(INTERRUPTED_DOWNLOAD_RETENTION);
        deleteExpiredChildren(paths.downloads(), cutoff, true);
        deleteExpiredChildren(paths.playerHome().resolve("staging/player-program"), cutoff, false);
    }

    void cleanSupersededPrograms(EnginePaths paths) {
        Optional<RetainedPrograms> retained = retainedPrograms(paths.playerHome());
        if (retained.isEmpty()) return;

        Path appRoot = paths.playerHome().resolve("app").toAbsolutePath().normalize();
        Set<Path> retainedAppDirectories = new HashSet<>();
        for (String relativeRoot : retained.get().programRoots()) {
            try {
                Path programRoot = PathSafety.resolveInside(paths.playerHome(), relativeRoot)
                        .toAbsolutePath().normalize();
                if (programRoot.startsWith(appRoot) && !programRoot.equals(appRoot)) {
                    Path first = appRoot.resolve(appRoot.relativize(programRoot).getName(0));
                    retainedAppDirectories.add(first);
                }
            } catch (IOException ignored) {
                return;
            }
        }
        deleteChildrenExcept(appRoot, retainedAppDirectories);

        Path manifestsRoot = paths.state().resolve("player-programs").toAbsolutePath().normalize();
        if (retained.get().manifestSha256s().isEmpty()) return;
        Set<Path> retainedManifests = new HashSet<>();
        for (String hash : retained.get().manifestSha256s()) {
            if (!SHA256.matcher(hash).matches()) return;
            retainedManifests.add(manifestsRoot.resolve(hash));
        }
        deleteChildrenExcept(manifestsRoot, retainedManifests);
    }

    void cleanObjectCache(EnginePaths paths) {
        deleteChildrenExcept(paths.cacheObjects(), Set.of());
    }

    private Optional<RetainedPrograms> retainedPrograms(Path playerHome) {
        Path stateFile = playerHome.resolve("state/active-player.properties");
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(stateFile)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            if (!"1".equals(properties.getProperty("schema"))) return Optional.empty();
            Set<String> roots = new HashSet<>();
            Set<String> hashes = new HashSet<>();
            addNonBlank(roots, properties.getProperty("programRoot"));
            addNonBlank(roots, properties.getProperty("fallbackProgramRoot"));
            addNonBlank(hashes, properties.getProperty("manifestSha256"));
            addNonBlank(hashes, properties.getProperty("fallbackManifestSha256"));
            if (roots.isEmpty()) return Optional.empty();
            return Optional.of(new RetainedPrograms(Set.copyOf(roots), Set.copyOf(hashes)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void addNonBlank(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.trim());
    }

    private void deleteExpiredChildren(Path root, Instant cutoff, boolean partialFilesOnly) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return;
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (partialFilesOnly && !PARTIAL_NAME.matcher(child.getFileName().toString()).matches()) {
                    continue;
                }
                try {
                    FileTime modified = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS);
                    if (modified.toInstant().isBefore(cutoff)) deleteTree(child);
                } catch (IOException ignored) {
                    // Cleanup is best effort and must never prevent a player from updating.
                }
            }
        } catch (IOException ignored) {
            // Cleanup is best effort and must never prevent a player from updating.
        }
    }

    private void deleteChildrenExcept(Path root, Set<Path> retained) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return;
        Set<Path> normalizedRetained = new HashSet<>();
        for (Path path : retained) normalizedRetained.add(path.toAbsolutePath().normalize());
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (normalizedRetained.contains(child.toAbsolutePath().normalize())) continue;
                try {
                    deleteTree(child);
                } catch (IOException ignored) {
                    // Cleanup is best effort and must never prevent a player from updating.
                }
            }
        } catch (IOException ignored) {
            // Cleanup is best effort and must never prevent a player from updating.
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root);
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record RetainedPrograms(Set<String> programRoots, Set<String> manifestSha256s) {
    }
}
