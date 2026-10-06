package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.LocalFileIndex;
import cn.dreamingfish.updater.engine.ArchiveIndex;
import cn.dreamingfish.updater.engine.ArchivedFile;
import cn.dreamingfish.updater.engine.ArchiveReason;
import cn.dreamingfish.updater.engine.MaintenanceState;
import cn.dreamingfish.updater.protocol.CorrectionMode;
import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.OptionalGroup;
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
    private final LocalModTransaction transaction;
    record StoredArchive(Path directory, List<ArchivedFile> files) { }
    private final List<StoredArchive> archivedDuringRun = new ArrayList<>();
    private StoredArchive plannedArchive;

    LocalModManager(Path instanceRoot, Path playerHome) {
        this(instanceRoot, playerHome, LocalModTransaction.Faults.NONE);
    }

    LocalModManager(Path instanceRoot, Path playerHome, LocalModTransaction.Faults faults) {
        this.instanceRoot = instanceRoot.toAbsolutePath().normalize();
        this.playerHome = playerHome.toAbsolutePath().normalize();
        preferencesFile = this.playerHome.resolve("state/local-mod-preferences.json");
        disabledRoot = this.playerHome.resolve(DISABLED_ROOT);
        transaction = new LocalModTransaction(this.instanceRoot, this.playerHome, json, this::validate, faults);
    }

    synchronized Snapshot snapshot() throws IOException {
        LocalModPreferences preferences = load();
        Set<String> components = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        Map<String, Set<String>> storedHashes = new LinkedHashMap<>();
        for (LocalModPreference preference : preferences.mods()) {
            if (preference.componentId() != null) {
                for (StoredLocalMod stored : preference.storedFiles()) {
                    Path source = resolveStored(stored.storedPath());
                    if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        storedHashes.computeIfAbsent(preference.componentId(), ignored -> new LinkedHashSet<>())
                                .add(CryptoSupport.sha256(source));
                    }
                }
            }
            if (!preference.disabled()) continue;
            if (preference.componentId() != null) components.add(preference.componentId());
            paths.add(preference.path());
        }
        return new Snapshot(preferences.revision(), new LocalFileOverrides(components, paths).withStoredModHashes(storedHashes));
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
        MaintenanceModel model = release == null ? null : MaintenanceModel.of(release);
        LocalFileIndex index = LocalFileIndex.load(playerHome.resolve("state/file-index.json"));
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
        PathSafety.assertSafePathTree(mods);
        if (Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(mods)) {
            try (var stream = Files.walk(mods)) {
                for (Path jar : stream.filter(LocalModManager::isSafeJar).toList()) {
                    PathSafety.assertSafePathTree(jar);
                    String path = relative(jar);
                    LocalFileIndex.Inspection inspection = index.inspect(jar, path, true);
                    ManifestFile managed = managedByPath.get(fold(path));
                    if (managed == null && inspection.componentId() != null) {
                        managed = managedByComponent.get(fold(inspection.componentId()));
                    }
                    String componentId = inspection.componentId() != null
                            ? inspection.componentId()
                            : managed == null ? null : managed.componentId();
                    String key = key(componentId, path);
                    LocalModPreference preference = componentId == null
                            ? preferencesByPath.get(fold(path))
                            : preferencesByComponent.getOrDefault(fold(componentId),
                            preferencesByPath.get(fold(path)));
                    String name = managed != null && managed.displayName() != null
                            ? managed.displayName()
                            : inspection.displayName() != null ? inspection.displayName()
                            : fileDisplayName(jar);
                    String version = inspection.version() != null ? inspection.version()
                            : managed == null ? null : managed.version();
                    LocalModEntry candidate = describe(key, name, path, componentId, managed,
                            preference != null && preference.disabled(), true, version,
                            model, inspection.sha256());
                    // Two copies of one mod: list the published one; the next update moves the other out.
                    if (!entries.containsKey(key) || managedByPath.containsKey(fold(path))) {
                        entries.put(key, candidate);
                    }
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
                ManifestFile managed = managedByPath.get(fold(preference.path()));
                if (managed == null && preference.componentId() != null) {
                    managed = managedByComponent.get(fold(preference.componentId()));
                }
                StoredCopy stored = storedCopy(preference);
                entries.put(preference.key(), describe(preference.key(), preference.displayName(),
                        preference.path(), preference.componentId(),
                        preference.managedAtDisable() ? managed : null, true, false,
                        stored == null ? null : stored.version(), model,
                        stored == null ? null : stored.sha256()));
            }
        }

        return entries.values().stream()
                .sorted(Comparator.comparing(LocalModEntry::disabled).reversed()
                        .thenComparing(LocalModEntry::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized void setDisabled(LocalModEntry entry, boolean disabled) throws IOException {
        if (entry.forced()) {
            throw new IOException(entry.lockReason() == null
                    ? "Forced sync mods cannot be changed locally" : entry.lockReason());
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
                current.revision() + 1, updated, current.appliedStoredCorrections()));
    }

    synchronized void restoreDefaults() throws IOException {
        LocalModPreferences current = load();
        if (current.mods().stream().noneMatch(LocalModPreference::disabled)) return;
        List<LocalModPreference> updated = current.mods().stream()
                .map(preference -> preference.disabled()
                        ? preference.withDisabled(false) : preference)
                .toList();
        save(new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                current.revision() + 1, updated, current.appliedStoredCorrections()));
    }

    synchronized void reconcileDesiredState() throws IOException {
        reconcileDesiredState(null);
    }

    synchronized void reconcileDesiredState(ReleaseManifest release) throws IOException {
        reconcileDesiredState(release, Map.of());
    }

    /**
     * Applies the player's switches to the mods folder: disabled mods move into
     * local storage, re-enabled player copies return, and switched-off optional
     * groups disable their mods. Required mods are never moved.
     */
    synchronized void reconcileDesiredState(ReleaseManifest release, Map<String, Boolean> groupChoices)
            throws IOException {
        reconcileDesiredState(release, groupChoices, true);
    }

    synchronized void reconcileDesiredState(ReleaseManifest release, Map<String, Boolean> groupChoices,
                                            boolean verifiedOwnerActions) throws IOException {
        recoverPendingTransaction();
        LocalModPreferences current = load();
        List<LocalModPreference> updated = new ArrayList<>(current.mods());
        boolean changed = applyOwnershipReleases(updated, release);
        changed |= syncGroupPreferences(updated, release, groupChoices == null ? Map.of() : groupChoices);
        List<LocalModTransaction.Move> moves = new ArrayList<>();
        plannedArchive = null;
        Set<String> appliedCorrections = new LinkedHashSet<>(current.appliedStoredCorrections());
        if (verifiedOwnerActions) changed |= archiveWithdrawnStored(updated, release, moves, appliedCorrections);
        changed |= restoreEnabledUnmanaged(updated, release, moves);
        MaintenanceModel model = release == null ? null : MaintenanceModel.of(release);

        List<Path> active = activeJars();
        for (Path jar : active) {
            String path = relative(jar);
            ModMetadata metadata = ModMetadataReader.read(jar).orElse(null);
            if (required(path, metadata == null ? null : metadata.componentId(), model)) continue;
            int index = findDisabledPreference(updated,
                    metadata == null ? null : metadata.componentId(), path);
            if (index < 0) continue;
            LocalModPreference preference = updated.get(index);
            Path stored = allocateStoredPath(jar.getFileName().toString());
            moves.add(transaction.move(jar, true, stored, false, false));
            List<StoredLocalMod> files = new ArrayList<>(preference.storedFiles());
            files.add(new StoredLocalMod(path, relativeToPlayerHome(stored), !preference.managedAtDisable()));
            updated.set(index, preference.withStoredFiles(files));
            changed = true;
        }

        if (changed) {
            transaction.execute(current, new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                    current.revision(), updated, List.copyOf(appliedCorrections)), moves);
            if (plannedArchive != null) archivedDuringRun.add(plannedArchive);
        }
    }

    synchronized void finalizeSuccessfulUpdate() throws IOException {
        finalizeSuccessfulUpdate(null);
    }

    synchronized void finalizeSuccessfulUpdate(ReleaseManifest release) throws IOException {
        recoverPendingTransaction();
        LocalModPreferences current = load();
        List<LocalModPreference> owned = new ArrayList<>(current.mods());
        boolean changed = applyOwnershipReleases(owned, release);
        List<LocalModPreference> retained = new ArrayList<>();
        List<LocalModTransaction.Move> moves = new ArrayList<>();
        for (LocalModPreference preference : owned) {
            if (preference.disabled()) {
                if (!removedByOwner(preference, release)) {
                    retained.add(preference);
                    continue;
                }
                // The owner deleted this mod: official stored copies go, the player's own stay.
                List<StoredLocalMod> kept = new ArrayList<>();
                for (StoredLocalMod stored : preference.storedFiles()) {
                    if (stored.playerOwned() || !preference.managedAtDisable()) {
                        kept.add(stored);
                        continue;
                    }
                    Path path = resolveStored(stored.storedPath());
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        moves.add(transaction.move(path, false, transaction.trashPath(), false, true));
                    }
                }
                if (!kept.isEmpty()) retained.add(preference.withStoredFiles(kept));
                changed = true;
                continue;
            }
            List<StoredLocalMod> kept = new ArrayList<>();
            for (StoredLocalMod stored : preference.storedFiles()) {
                if (stored.playerOwned() || !preference.managedAtDisable()) {
                    kept.add(stored);
                    continue;
                }
                Path path = resolveStored(stored.storedPath());
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    moves.add(transaction.move(path, false, transaction.trashPath(), false, true));
                }
            }
            if (!kept.isEmpty()) retained.add(preference.withStoredFiles(kept));
            changed |= !kept.equals(preference.storedFiles()) || kept.isEmpty();
        }
        if (changed) {
            transaction.execute(current, new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                    current.revision(), retained, current.appliedStoredCorrections()), moves);
        }
    }

    synchronized void recoverPendingTransaction() throws IOException { transaction.recover(); }

    synchronized List<StoredArchive> drainStoredArchives() {
        List<StoredArchive> result = List.copyOf(archivedDuringRun);
        archivedDuringRun.clear();
        return result;
    }

    /** Stored copies and their backup index commit in the same journal as the preference changes. */
    private boolean archiveWithdrawnStored(List<LocalModPreference> preferences, ReleaseManifest release,
                                           List<LocalModTransaction.Move> moves, Set<String> appliedCorrections) throws IOException {
        if (release == null || (release.withdrawals().isEmpty() && release.corrections().isEmpty())) return false;
        MaintenanceModel model = MaintenanceModel.of(release);
        Path stateFile = PathSafety.resolveInside(playerHome, "state/maintenance-state.json");
        MaintenanceState state = Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                ? json.read(stateFile, MaintenanceState.class) : MaintenanceState.empty(release.projectId());
        Set<String> once = new LinkedHashSet<>();
        for (var correction : model.corrections()) {
            if (correction.mode() == CorrectionMode.ONCE && state.correctionApplied(correction.id())
                    && !appliedCorrections.contains(correction.id())) once.add(correction.id());
        }
        String archiveId = "local-mods-" + UUID.randomUUID();
        Path directory = PathSafety.resolveInside(playerHome, "backups/archive/" + archiveId);
        List<ArchivedFile> archived = new ArrayList<>();
        boolean changed = false;
        for (int index = 0; index < preferences.size(); index++) {
            LocalModPreference preference = preferences.get(index);
            List<StoredLocalMod> kept = new ArrayList<>();
            for (StoredLocalMod stored : preference.storedFiles()) {
                Path source = resolveStored(stored.storedPath());
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { kept.add(stored); continue; }
                ModMetadata metadata = ModMetadataReader.read(source).orElse(null);
                String hash = CryptoSupport.sha256(source);
                String component = metadata == null ? preference.componentId() : metadata.componentId();
                String version = metadata == null ? null : metadata.version();
                var match = model.withdrawalFor(stored.originalPath(), hash, component, version);
                String detail = match.map(found -> found.withdrawal().reason()).orElse(null);
                ArchiveReason reason = match.map(found -> found.withdrawal().removal()
                        ? ArchiveReason.OWNER_REMOVED : ArchiveReason.WITHDRAWN).orElse(null);
                if (match.isEmpty()) {
                    for (var correction : model.corrections()) {
                        ManifestFile targetFile = model.file(correction.path()).orElse(null);
                        if (targetFile == null || (!stored.originalPath().equalsIgnoreCase(targetFile.path())
                                && !sameComponent(component, targetFile.componentId()))) continue;
                        boolean affected = correction.mode() == CorrectionMode.KNOWN_BAD
                                ? correction.badSha256().contains(hash) : once.contains(correction.id());
                        if (affected && !hash.equals(targetFile.sha256())) {
                            reason = ArchiveReason.CORRECTED;
                            detail = correction.reason();
                            break;
                        }
                    }
                }
                if (reason == null) { kept.add(stored); continue; }
                String destination = "stored/" + archived.size() + "/" + stored.originalPath();
                Path target = PathSafety.resolveInside(directory, destination);
                moves.add(transaction.move(source, false, target, false, false));
                archived.add(new ArchivedFile(stored.originalPath(), destination, hash, Files.size(source),
                        reason, detail, component, version, null));
                changed = true;
            }
            if (!kept.equals(preference.storedFiles())) preferences.set(index, preference.withStoredFiles(kept));
        }
        if (!archived.isEmpty()) {
            ArchiveIndex record = new ArchiveIndex(ArchiveIndex.SCHEMA_VERSION, archiveId, release.releaseId(),
                    release.displayVersion(), Instant.now(), archived);
            Path staging = PathSafety.resolveInside(playerHome, LocalModTransaction.ARCHIVE_STAGING + archiveId + ".json");
            LocalModTransaction.write(staging, json.writePretty(record));
            moves.add(transaction.move(staging, false, directory.resolve(ArchiveIndex.FILE_NAME), false, false));
            plannedArchive = new StoredArchive(directory, List.copyOf(archived));
        }
        changed |= appliedCorrections.addAll(once);
        return changed;
    }

    private boolean restoreEnabledUnmanaged(List<LocalModPreference> preferences, ReleaseManifest release,
                                            List<LocalModTransaction.Move> moves) throws IOException {
        boolean changed = false;
        Set<Path> reserved = new HashSet<>();
        for (int index = 0; index < preferences.size(); index++) {
            LocalModPreference preference = preferences.get(index);
            if (preference.disabled()
                    || preference.storedFiles().isEmpty()) continue;
            List<StoredLocalMod> remaining = new ArrayList<>();
            for (StoredLocalMod stored : preference.storedFiles()) {
                if ((!stored.playerOwned() && preference.managedAtDisable())
                        || currentlyManaged(preference, release)) {
                    remaining.add(stored);
                    continue;
                }
                Path source = resolveStored(stored.storedPath());
                Path destination = resolveInstance(stored.originalPath());
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                        || withdrawn(stored.originalPath(), source, release) != null) {
                    remaining.add(stored);
                    continue;
                }
                if (!reserved.add(destination)) {
                    remaining.add(stored);
                    continue;
                }
                moves.add(transaction.move(source, false, destination, true, false));
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
        if (transaction.pending()) throw new IOException("An unfinished local mod transaction must be recovered before editing preferences");
        PathSafety.assertSafePathTree(preferencesFile);
        if (!Files.exists(preferencesFile, LinkOption.NOFOLLOW_LINKS)) {
            return LocalModPreferences.empty();
        }
        if (!Files.isRegularFile(preferencesFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(preferencesFile)) {
            throw new IOException("Local mod preferences path is unsafe");
        }
        LocalModPreferences preferences = json.read(preferencesFile, LocalModPreferences.class);
        validate(preferences);
        if (preferences.schemaVersion() == 1) {
            Set<String> applied = new LinkedHashSet<>(preferences.appliedStoredCorrections());
            Path installedFile = PathSafety.resolveInside(playerHome, "state/release-manifest.json");
            Path stateFile = PathSafety.resolveInside(playerHome, "state/maintenance-state.json");
            if (Files.isRegularFile(installedFile, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                ReleaseManifest installed = json.read(installedFile, ReleaseManifest.class);
                if (!MaintenanceModel.of(installed).simplified()) {
                    applied.addAll(json.read(stateFile, MaintenanceState.class).appliedCorrections());
                }
            }
            preferences = new LocalModPreferences(LocalModPreferences.SCHEMA_VERSION,
                    preferences.revision(), preferences.mods(), List.copyOf(applied));
            validate(preferences);
            LocalModTransaction.write(preferencesFile, json.writePretty(preferences));
        }
        return preferences;
    }

    private void validate(LocalModPreferences preferences) throws IOException {
        if ((preferences.schemaVersion() != 1 && preferences.schemaVersion() != LocalModPreferences.SCHEMA_VERSION)
                || preferences.revision() < 0) {
            throw new IOException("Unsupported local mod preferences file");
        }
        if (preferences.appliedStoredCorrections().size() > 10_000
                || preferences.appliedStoredCorrections().stream().anyMatch(id -> id == null
                || !id.matches("[a-z0-9][a-z0-9._-]{0,127}"))) {
            throw new IOException("Invalid stored correction memory");
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
            if (preference.group() != null
                    && !preference.group().matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IOException("Invalid local mod optional group");
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
        LocalModTransaction.write(preferencesFile, json.writePretty(preferences));
        LocalModTransaction.forceDirectories(preferencesFile.getParent(), playerHome);
    }

    private List<Path> activeJars() throws IOException {
        Path mods = instanceRoot.resolve("mods");
        PathSafety.assertSafePathTree(mods);
        if (!Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(mods)) {
            throw new IOException("Minecraft mods path is unsafe");
        }
        try (var stream = Files.walk(mods)) {
            List<Path> jars = stream.filter(LocalModManager::isSafeJar).sorted().toList();
            for (Path jar : jars) PathSafety.assertSafePathTree(jar);
            return jars;
        }
    }

    private Path allocateStoredPath(String fileName) throws IOException {
        PathSafety.createSafeDirectories(disabledRoot);
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

    static void moveVerified(Path source, Path target) throws IOException {
        PathSafety.assertSafePathTree(source);
        PathSafety.assertSafePathTree(target);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("Local mod source is unsafe: " + source);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local mod storage target already exists: " + target);
        }
        PathSafety.createSafeDirectories(target.getParent());
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
            var dos = Files.getFileAttributeView(temporary, java.nio.file.attribute.DosFileAttributeView.class);
            boolean readOnly = dos != null && dos.readAttributes().isReadOnly();
            if (readOnly) dos.setReadOnly(false);
            var posix = Files.getFileAttributeView(temporary, java.nio.file.attribute.PosixFileAttributeView.class);
            Set<java.nio.file.attribute.PosixFilePermission> permissions = posix == null
                    ? null : posix.readAttributes().permissions();
            if (permissions != null) {
                Set<java.nio.file.attribute.PosixFilePermission> writable = new HashSet<>(permissions);
                writable.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                posix.setPermissions(writable);
            }
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.size(source) != Files.size(temporary)
                    || !CryptoSupport.sha256(source).equals(CryptoSupport.sha256(temporary))) {
                throw new IOException("Copied local mod failed verification");
            }
            moveReplace(temporary, target);
            Files.delete(source);
            if (permissions != null) Files.setPosixFilePermissions(target, permissions);
            if (readOnly) Files.getFileAttributeView(target, java.nio.file.attribute.DosFileAttributeView.class).setReadOnly(true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void moveReplace(Path source, Path target) throws IOException {
        PathSafety.assertSafePathTree(source);
        PathSafety.assertSafePathTree(target);
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

    private static boolean currentlyManaged(LocalModPreference preference, ReleaseManifest release) {
        return release != null && release.files().stream().anyMatch(file ->
                file.preset() != cn.dreamingfish.updater.protocol.MaintenancePreset.INITIAL
                        && (fold(file.path()).equals(fold(preference.path()))
                        || sameComponent(file.componentId(), preference.componentId())));
    }

    private static boolean applyOwnershipReleases(List<LocalModPreference> preferences, ReleaseManifest release) {
        if (release == null) return false;
        Set<String> released = release.releasedPaths().stream().map(LocalModManager::fold)
                .collect(java.util.stream.Collectors.toSet());
        boolean changed = false;
        for (int index = 0; index < preferences.size(); index++) {
            LocalModPreference previous = preferences.get(index);
            boolean initial = release.files().stream().anyMatch(file ->
                    file.preset() == cn.dreamingfish.updater.protocol.MaintenancePreset.INITIAL
                    && (file.path().equalsIgnoreCase(previous.path())
                    || sameComponent(file.componentId(), previous.componentId())));
            List<StoredLocalMod> files = previous.storedFiles().stream()
                    .map(file -> released.contains(fold(file.originalPath()))
                            || initial || !previous.managedAtDisable() ? file.ownedByPlayer() : file).toList();
            LocalModPreference next = previous.withStoredFiles(files);
            if ((initial || released.contains(fold(previous.path()))) && !currentlyManaged(previous, release)) {
                next = next.withManagedAtDisable(false);
            }
            if (!previous.equals(next)) { preferences.set(index, next); changed = true; }
        }
        return changed;
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

    private record StoredCopy(String sha256, String componentId, String version) {
    }

    private StoredCopy storedCopy(LocalModPreference preference) {
        for (StoredLocalMod stored : preference.storedFiles()) {
            try {
                Path source = resolveStored(stored.storedPath());
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                ModMetadata metadata = ModMetadataReader.read(source).orElse(null);
                return new StoredCopy(CryptoSupport.sha256(source),
                        metadata == null ? preference.componentId() : metadata.componentId(),
                        metadata == null ? null : metadata.version());
            } catch (IOException ignored) {
                // An unreadable stored copy is shown without version details.
            }
        }
        return null;
    }

    /** Builds the window entry, including why the switch may be unavailable. */
    private static LocalModEntry describe(String key, String name, String path, String componentId,
                                          ManifestFile managed, boolean disabled, boolean active,
                                          String version, MaintenanceModel model, String sha256) {
        String preset = null;
        OptionalGroup group = null;
        String lockReason = null;
        String withdrawnReason = null;
        boolean required = false;
        if (model != null) {
            if (managed != null) {
                preset = model.behaviorOf(managed).name();
                group = model.groupOf(managed.path()).orElse(null);
            }
            required = required(path, componentId, model);
            if (required) {
                lockReason = "服主设为必需同步，不能在本机停用";
            } else if (group != null) {
                lockReason = "由可选内容“" + group.title() + "”统一开关";
            } else if (managed == null && model.insideCleanupDirectory(path)) {
                lockReason = "这个目录由服主统一管理，自行添加的模组会在更新时移入备份";
            }
            withdrawnReason = model.withdrawalFor(path, sha256, componentId, version)
                    .map(match -> match.withdrawal().reason().isBlank()
                            ? "服主撤回了这个版本" : match.withdrawal().reason())
                    .orElse(null);
        }
        // A required mod stays installed whatever the player switched earlier.
        return new LocalModEntry(key, name, path, componentId,
                managed != null && (model == null || model.behaviorOf(managed) != MaintenanceModel.Behavior.INITIAL),
                disabled && !required, active,
                lockReason != null, version, preset, group == null ? null : group.id(),
                group == null ? null : group.title(), lockReason, withdrawnReason);
    }

    /** Required mods ignore every local switch, including through renamed files of the same mod. */
    private static boolean required(String path, String componentId, MaintenanceModel model) {
        if (model == null) return false;
        if (model.locked(path)) return true;
        return componentId != null && model.filesForComponent(componentId).stream()
                .anyMatch(file -> model.locked(file.path()));
    }

    private static String withdrawn(String path, Path copy, ReleaseManifest release) {
        if (release == null || release.withdrawals().isEmpty()) return null;
        try {
            ModMetadata metadata = ModMetadataReader.read(copy).orElse(null);
            return MaintenanceModel.of(release).withdrawalFor(path, CryptoSupport.sha256(copy),
                    metadata == null ? null : metadata.componentId(),
                    metadata == null ? null : metadata.version())
                    .map(match -> match.withdrawal().id()).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Group switches become disable entries tagged with their group. A switch the
     * player set on one mod always wins over the group.
     */
    private static boolean syncGroupPreferences(List<LocalModPreference> preferences,
                                                ReleaseManifest release, Map<String, Boolean> choices) {
        if (release == null) return false;
        MaintenanceModel model = MaintenanceModel.of(release);
        Map<String, ManifestFile> desired = new LinkedHashMap<>();
        Map<String, String> desiredGroup = new HashMap<>();
        for (ManifestFile file : model.files()) {
            if (!isModJar(file.path())) continue;
            OptionalGroup group = model.groupOf(file.path()).orElse(null);
            if (group == null) continue;
            Boolean choice = choices.get(group.id());
            if (choice == null ? group.defaultInstall() : choice) continue;
            String key = key(file.componentId(), file.path());
            desired.put(key, file);
            desiredGroup.put(key, group.id());
        }
        boolean changed = false;
        for (int index = 0; index < preferences.size(); index++) {
            LocalModPreference preference = preferences.get(index);
            if (preference.group() == null) {
                desired.remove(preference.key());
                continue;
            }
            if (desired.containsKey(preference.key())) {
                if (!preference.disabled()) {
                    preferences.set(index, preference.withDisabled(true)
                            .withGroup(desiredGroup.get(preference.key())));
                    changed = true;
                }
                desired.remove(preference.key());
            } else if (preference.disabled()) {
                preferences.set(index, preference.withDisabled(false));
                changed = true;
            }
        }
        for (Map.Entry<String, ManifestFile> entry : desired.entrySet()) {
            ManifestFile file = entry.getValue();
            String name = file.displayName() != null ? file.displayName()
                    : file.path().substring(file.path().lastIndexOf('/') + 1);
            preferences.add(new LocalModPreference(entry.getKey(), file.componentId(), file.path(),
                    name, true, true, List.of(), Instant.now(), desiredGroup.get(entry.getKey())));
            changed = true;
        }
        return changed;
    }

    /** Whether a maintenance-policy release stopped publishing a disabled official mod. */
    private static boolean removedByOwner(LocalModPreference preference, ReleaseManifest release) {
        if (release == null || !release.usesMaintenancePolicy() || !preference.managedAtDisable()) {
            return false;
        }
        if (currentlyManaged(preference, release)) return false;
        return release.releasedPaths().stream().noneMatch(path -> fold(path).equals(fold(preference.path())));
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
