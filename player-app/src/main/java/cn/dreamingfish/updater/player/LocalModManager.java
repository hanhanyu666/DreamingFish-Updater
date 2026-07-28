package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ModMetadata;
import cn.dreamingfish.updater.protocol.ModMetadataReader;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LocalModManager {
    private static final String DISABLED_ROOT = "local-mods/disabled";

    record Snapshot(long revision, LocalFileOverrides overrides) {
    }

    private final Path instanceRoot;
    private final Path playerHome;
    private final Path preferencesFile;
    private final Path disabledRoot;
    private final JsonCodec json = new JsonCodec();

    LocalModManager(Path instanceRoot, Path playerHome) {
        this.instanceRoot = instanceRoot.toAbsolutePath().normalize();
        this.playerHome = playerHome.toAbsolutePath().normalize();
        preferencesFile = this.playerHome.resolve("state/local-mod-preferences.json");
        disabledRoot = this.playerHome.resolve(DISABLED_ROOT);
    }

    synchronized Snapshot snapshot() throws IOException {
        LocalModPreferences preferences = load();
        Set<String> components = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        for (LocalModPreference preference : preferences.mods()) {
            if (!preference.disabled()) continue;
            if (preference.componentId() != null) components.add(preference.componentId());
            paths.add(preference.path());
        }
        return new Snapshot(preferences.revision(), new LocalFileOverrides(components, paths));
    }

    synchronized ReleaseManifest loadInstalledManifest(String projectId) {
        Path manifest = playerHome.resolve("state/release-manifest.json");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifest)) return null;
        try {
            ReleaseManifest release = json.read(manifest, ReleaseManifest.class);
            return projectId.equals(release.projectId()) ? release : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized List<LocalModEntry> scan(ReleaseManifest release) throws IOException {
        LocalModPreferences preferences = load();
        Map<String, ManifestFile> managedByPath = new HashMap<>();
        Map<String, ManifestFile> managedByComponent = new HashMap<>();
        if (release != null) {
            for (ManifestFile file : release.files()) {
                if (!isModJar(file.path())) continue;
                managedByPath.put(fold(file.path()), file);
                if (file.componentId() != null) {
                    managedByComponent.putIfAbsent(fold(file.componentId()), file);
                }
            }
        }

        Map<String, LocalModPreference> preferencesByKey = new HashMap<>();
        Map<String, LocalModPreference> preferencesByPath = new HashMap<>();
        Map<String, LocalModPreference> preferencesByComponent = new HashMap<>();
        for (LocalModPreference preference : preferences.mods()) {
            preferencesByKey.put(preference.key(), preference);
            preferencesByPath.put(fold(preference.path()), preference);
            if (preference.componentId() != null) {
                preferencesByComponent.put(fold(preference.componentId()), preference);
            }
        }

        Map<String, LocalModEntry> entries = new LinkedHashMap<>();
        Path mods = instanceRoot.resolve("mods");
        if (Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(mods)) {
            try (var stream = Files.walk(mods)) {
                for (Path jar : stream.filter(LocalModManager::isSafeJar).toList()) {
                    String path = relative(jar);
                    ModMetadata metadata = ModMetadataReader.read(jar).orElse(null);
                    ManifestFile managed = managedByPath.get(fold(path));
                    if (managed == null && metadata != null) {
                        managed = managedByComponent.get(fold(metadata.componentId()));
                    }
                    String componentId = metadata != null
                            ? metadata.componentId()
                            : managed == null ? null : managed.componentId();
                    String key = key(componentId, path);
                    LocalModPreference preference = componentId == null
                            ? preferencesByPath.get(fold(path))
                            : preferencesByComponent.getOrDefault(fold(componentId),
                            preferencesByPath.get(fold(path)));
                    String name = managed != null && managed.displayName() != null
                            ? managed.displayName()
                            : metadata != null ? metadata.displayName() : fileDisplayName(jar);
                    entries.put(key, new LocalModEntry(key, name, path, componentId,
                            managed != null, preference != null && preference.disabled(), true,
                            isForced(path, release)));
                }
            }
        }

        for (LocalModPreference preference : preferences.mods()) {
            if (!preference.disabled()) continue;
            boolean represented = entries.values().stream().anyMatch(entry ->
                    preference.key().equals(entry.key())
                            || sameComponent(preference.componentId(), entry.componentId())
                            || fold(preference.path()).equals(fold(entry.path())));
            if (!represented) {
                entries.put(preference.key(), new LocalModEntry(
                        preference.key(), preference.displayName(), preference.path(),
                        preference.componentId(), preference.managedAtDisable(), true, false,
                        isForced(preference.path(), release)));
            }
        }

        return entries.values().stream()
                .sorted(Comparator.comparing(LocalModEntry::disabled).reversed()
                        .thenComparing(LocalModEntry::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized void setDisabled(LocalModEntry entry, boolean disabled) throws IOException {
        if (entry.forced()) {
            throw new IOException("Forced sync mods cannot be changed locally");
        }
        LocalModPreferences current = load();
        List<LocalModPreference> updated = new ArrayList<>(current.mods());
        int index = findPreference(updated, entry);
        if (index >= 0) {
            LocalModPreference existing = updated.get(index);
            if (existing.disabled() == disabled) return;
            updated.set(index, existing.withDisabled(disabled));
        } else {
            if (!disabled) return;
            updated.add(new LocalModPreference(
                    entry.key(), entry.componentId(), entry.path(), entry.displayName(),
                    true, entry.managed(), List.of(), Instant.now()));
        }
        save(new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                current.revision() + 1, updated));
    }

    synchronized void restoreDefaults() throws IOException {
        LocalModPreferences current = load();
        if (current.mods().stream().noneMatch(LocalModPreference::disabled)) return;
        List<LocalModPreference> updated = current.mods().stream()
                .map(preference -> preference.disabled()
                        ? preference.withDisabled(false) : preference)
                .toList();
        save(new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                current.revision() + 1, updated));
    }

    synchronized void reconcileDesiredState() throws IOException {
        reconcileDesiredState(null);
    }

    synchronized void reconcileDesiredState(ReleaseManifest release) throws IOException {
        LocalModPreferences current = load();
        List<LocalModPreference> updated = new ArrayList<>(current.mods());
        boolean changed = restoreEnabledUnmanaged(updated);

        List<Path> active = activeJars();
        for (Path jar : active) {
            String path = relative(jar);
            if (isForced(path, release)) continue;
            ModMetadata metadata = ModMetadataReader.read(jar).orElse(null);
            int index = findDisabledPreference(updated,
                    metadata == null ? null : metadata.componentId(), path);
            if (index < 0) continue;
            LocalModPreference preference = updated.get(index);
            Path stored = allocateStoredPath(jar.getFileName().toString());
            moveVerified(jar, stored);
            List<StoredLocalMod> files = new ArrayList<>(preference.storedFiles());
            files.add(new StoredLocalMod(path, relativeToPlayerHome(stored)));
            updated.set(index, preference.withStoredFiles(files));
            changed = true;
        }

        if (changed) {
            save(new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                    current.revision(), updated));
        }
    }

    synchronized void finalizeSuccessfulUpdate() throws IOException {
        LocalModPreferences current = load();
        List<LocalModPreference> retained = new ArrayList<>();
        boolean changed = false;
        for (LocalModPreference preference : current.mods()) {
            if (preference.disabled()) {
                retained.add(preference);
                continue;
            }
            if (!preference.managedAtDisable() && !preference.storedFiles().isEmpty()) {
                retained.add(preference);
                continue;
            }
            changed = true;
            for (StoredLocalMod stored : preference.storedFiles()) {
                Path path = resolveStored(stored.storedPath());
                Files.deleteIfExists(path);
            }
        }
        if (changed) {
            save(new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                    current.revision(), retained));
        }
    }

    private boolean restoreEnabledUnmanaged(List<LocalModPreference> preferences) throws IOException {
        boolean changed = false;
        for (int index = 0; index < preferences.size(); index++) {
            LocalModPreference preference = preferences.get(index);
            if (preference.disabled() || preference.managedAtDisable()
                    || preference.storedFiles().isEmpty()) continue;
            List<StoredLocalMod> remaining = new ArrayList<>();
            for (StoredLocalMod stored : preference.storedFiles()) {
                Path source = resolveStored(stored.storedPath());
                Path destination = resolveInstance(stored.originalPath());
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    remaining.add(stored);
                    continue;
                }
                moveVerified(source, destination);
                changed = true;
            }
            if (remaining.size() != preference.storedFiles().size()) {
                preferences.set(index, preference.withStoredFiles(remaining));
                changed = true;
            }
        }
        return changed;
    }

    private LocalModPreferences load() throws IOException {
        if (!Files.exists(preferencesFile, LinkOption.NOFOLLOW_LINKS)) {
            return LocalModPreferences.empty();
        }
        if (!Files.isRegularFile(preferencesFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(preferencesFile)) {
            throw new IOException("Local mod preferences path is unsafe");
        }
        LocalModPreferences preferences = json.read(preferencesFile, LocalModPreferences.class);
        validate(preferences);
        return preferences;
    }

    private void validate(LocalModPreferences preferences) throws IOException {
        if (preferences.schemaVersion() != LocalModPreferences.SCHEMA_VERSION
                || preferences.revision() < 0) {
            throw new IOException("Unsupported local mod preferences file");
        }
        Set<String> keys = new HashSet<>();
        for (LocalModPreference preference : preferences.mods()) {
            if (preference.key() == null || preference.path() == null
                    || !isModJar(preference.path())
                    || !preference.key().equals(key(preference.componentId(), preference.path()))
                    || !keys.add(preference.key())
                    || preference.displayName() == null || preference.displayName().isBlank()
                    || preference.displayName().length() > 256
                    || preference.displayName().chars().anyMatch(Character::isISOControl)) {
                throw new IOException("Invalid local mod preference entry");
            }
            PathSafety.normalizeManifestPath(preference.path());
            if (preference.componentId() != null
                    && !preference.componentId().matches("[A-Za-z0-9_.-]{1,128}")) {
                throw new IOException("Invalid local mod component ID");
            }
            for (StoredLocalMod stored : preference.storedFiles()) {
                if (stored.originalPath() == null || !isModJar(stored.originalPath())
                        || stored.storedPath() == null
                        || !fold(stored.storedPath()).startsWith(DISABLED_ROOT + "/")) {
                    throw new IOException("Invalid stored local mod path");
                }
                PathSafety.normalizeManifestPath(stored.originalPath());
                PathSafety.normalizeManifestPath(stored.storedPath());
            }
        }
    }

    private void save(LocalModPreferences preferences) throws IOException {
        Files.createDirectories(preferencesFile.getParent());
        Path temporary = preferencesFile.resolveSibling(
                preferencesFile.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, json.write(preferences),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveReplace(temporary, preferencesFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private List<Path> activeJars() throws IOException {
        Path mods = instanceRoot.resolve("mods");
        if (!Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(mods)) {
            throw new IOException("Minecraft mods path is unsafe");
        }
        try (var stream = Files.walk(mods)) {
            return stream.filter(LocalModManager::isSafeJar).toList();
        }
    }

    private Path allocateStoredPath(String fileName) throws IOException {
        Files.createDirectories(disabledRoot);
        return disabledRoot.resolve(UUID.randomUUID() + "-" + fileName);
    }

    private Path resolveStored(String relative) throws IOException {
        Path path = PathSafety.resolveInside(playerHome, relative);
        if (!path.startsWith(disabledRoot)) throw new IOException("Stored mod path escapes disabled storage");
        return path;
    }

    private Path resolveInstance(String relative) throws IOException {
        return PathSafety.resolveInside(instanceRoot, relative);
    }

    private String relative(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(instanceRoot)) throw new IOException("Mod path escapes the instance");
        return instanceRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private String relativeToPlayerHome(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(playerHome)) throw new IOException("Stored mod path escapes player data");
        return playerHome.relativize(normalized).toString().replace('\\', '/');
    }

    private static void moveVerified(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("Local mod source is unsafe: " + source);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local mod storage target already exists: " + target);
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException ignored) {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return;
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.copy(source, temporary);
            if (Files.size(source) != Files.size(temporary)
                    || !CryptoSupport.sha256(source).equals(CryptoSupport.sha256(temporary))) {
                throw new IOException("Copied local mod failed verification");
            }
            moveReplace(temporary, target);
            Files.delete(source);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int findPreference(List<LocalModPreference> preferences, LocalModEntry entry) {
        for (int i = 0; i < preferences.size(); i++) {
            LocalModPreference preference = preferences.get(i);
            if (preference.key().equals(entry.key())
                    || sameComponent(preference.componentId(), entry.componentId())
                    || fold(preference.path()).equals(fold(entry.path()))) return i;
        }
        return -1;
    }

    private static int findDisabledPreference(List<LocalModPreference> preferences,
                                              String componentId, String path) {
        for (int i = 0; i < preferences.size(); i++) {
            LocalModPreference preference = preferences.get(i);
            if (!preference.disabled()) continue;
            if (sameComponent(preference.componentId(), componentId)
                    || fold(preference.path()).equals(fold(path))) return i;
        }
        return -1;
    }

    private static String key(String componentId, String path) {
        return componentId == null ? "path:" + fold(path) : "component:" + fold(componentId);
    }

    private static boolean sameComponent(String left, String right) {
        return left != null && right != null && fold(left).equals(fold(right));
    }

    private static boolean isSafeJar(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static boolean isModJar(String path) {
        String folded = fold(path);
        return folded.startsWith("mods/") && folded.endsWith(".jar");
    }

    private static boolean isForced(String path, ReleaseManifest release) {
        return release != null && (release.forcedSyncFiles().stream()
                .anyMatch(file -> fold(path).equals(fold(file)))
                || release.forcedSyncDirectories().stream()
                .anyMatch(directory -> fold(path).equals(fold(directory))
                        || fold(path).startsWith(fold(directory) + "/")));
    }

    private static String fileDisplayName(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? name.substring(0, name.length() - 4) : name;
    }

    private static String fold(String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
