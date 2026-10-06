package cn.dreamingfish.updater.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single interpretation of how a release maintains each path. Releases created
 * before {@link ProtocolConstants#CAPABILITY_MAINTENANCE_POLICY} are mapped to
 * the same vocabulary: ordinary files become {@link Behavior#SYNC}, exact
 * forced files and files inside forced directories become
 * {@link Behavior#REQUIRED}, forced directories become cleanup directories and
 * the historical {@code DEFAULT} token keeps its reinstall-when-missing meaning.
 */
public final class MaintenanceModel {
    public enum Behavior {
        REQUIRED,
        SYNC,
        INITIAL,
        DEFAULT_CONFIG,
        /** Historical DEFAULT token: installed whenever missing, never overwritten or removed. */
        LEGACY_MISSING_ONLY;

        public static Behavior of(MaintenancePreset preset) {
            return switch (preset) {
                case REQUIRED -> REQUIRED;
                case SYNC -> SYNC;
                case INITIAL -> INITIAL;
                case DEFAULT_CONFIG -> DEFAULT_CONFIG;
            };
        }

        /** Whether an installed copy must keep exactly the published content. */
        public boolean convergesContent() {
            return this == REQUIRED || this == SYNC;
        }

        /** Whether removing the file from the release removes the player's copy. */
        public boolean removedWithRelease() {
            return this == REQUIRED || this == SYNC || this == DEFAULT_CONFIG;
        }
    }

    private final ReleaseManifest manifest;
    private final boolean policy;
    private final Map<String, ManifestFile> files = new LinkedHashMap<>();
    private final Map<String, Behavior> behaviors = new HashMap<>();
    private final List<String> cleanupDirectories;
    private final Map<String, OptionalGroup> groups = new LinkedHashMap<>();
    private final Set<String> released = new HashSet<>();
    private final Set<String> retainedSelfManaged = new HashSet<>();
    private final Map<String, Correction> corrections = new LinkedHashMap<>();
    private final Map<String, List<ManifestFile>> filesByComponent = new HashMap<>();

    private MaintenanceModel(ReleaseManifest manifest) {
        this.manifest = manifest;
        this.policy = manifest.usesMaintenancePolicy();
        List<String> cleanup = new ArrayList<>(manifest.cleanupDirectories());
        for (String directory : manifest.forcedSyncDirectories()) {
            if (cleanup.stream().noneMatch(existing -> ManagedPaths.fold(existing)
                    .equals(ManagedPaths.fold(directory)))) {
                cleanup.add(directory);
            }
        }
        this.cleanupDirectories = List.copyOf(cleanup);
        Set<String> forcedFiles = new HashSet<>();
        manifest.forcedSyncFiles().forEach(path -> forcedFiles.add(ManagedPaths.fold(path)));
        for (ManifestFile file : manifest.files()) {
            String folded = ManagedPaths.fold(file.path());
            files.put(folded, file);
            behaviors.put(folded, behaviorOf(file, forcedFiles));
            if (file.componentId() != null) {
                filesByComponent.computeIfAbsent(ManagedPaths.fold(file.componentId()),
                        ignored -> new ArrayList<>()).add(file);
            }
        }
        manifest.optionalGroups().forEach(group -> groups.put(group.id(), group));
        manifest.releasedPaths().forEach(path -> released.add(ManagedPaths.fold(path)));
        manifest.retainedSelfManagedPaths().forEach(path ->
                retainedSelfManaged.add(ManagedPaths.fold(path)));
        manifest.corrections().forEach(correction ->
                corrections.put(ManagedPaths.fold(correction.path()), correction));
    }

    public static MaintenanceModel of(ReleaseManifest manifest) {
        return new MaintenanceModel(manifest);
    }

    private Behavior behaviorOf(ManifestFile file, Set<String> forcedFiles) {
        if (file.preset() != null) return Behavior.of(file.preset());
        if (file.policy() == FilePolicy.LEGACY_MISSING_ONLY) return Behavior.LEGACY_MISSING_ONLY;
        String folded = ManagedPaths.fold(file.path());
        if (forcedFiles.contains(folded)) return Behavior.REQUIRED;
        for (String directory : manifest.forcedSyncDirectories()) {
            if (ManagedPaths.isBelow(folded, directory)) return Behavior.REQUIRED;
        }
        return Behavior.SYNC;
    }

    public ReleaseManifest manifest() {
        return manifest;
    }

    /** True when the release declares the preset-based maintenance semantics. */
    public boolean policyV2() {
        return policy;
    }

    public boolean simplified() {
        return manifest.requiredCapabilities().contains(ProtocolConstants.CAPABILITY_SIMPLIFIED_MAINTENANCE);
    }

    public Collection<ManifestFile> files() {
        return files.values();
    }

    public Optional<ManifestFile> file(String path) {
        return Optional.ofNullable(files.get(ManagedPaths.fold(path)));
    }

    public boolean contains(String path) {
        return files.containsKey(ManagedPaths.fold(path));
    }

    /** Behavior of a published file; unknown paths have no behavior. */
    public Optional<Behavior> behavior(String path) {
        return Optional.ofNullable(behaviors.get(ManagedPaths.fold(path)));
    }

    public Behavior behaviorOf(ManifestFile file) {
        return behaviors.get(ManagedPaths.fold(file.path()));
    }

    /** Required files ignore every player exemption, disable switch and optional group choice. */
    public boolean locked(String path) {
        return behavior(path).map(behavior -> behavior == Behavior.REQUIRED).orElse(false);
    }

    public List<String> cleanupDirectories() {
        return cleanupDirectories;
    }

    public Optional<String> cleanupDirectoryFor(String path) {
        for (String directory : cleanupDirectories) {
            if (ManagedPaths.isBelow(path, directory)) return Optional.of(directory);
        }
        return Optional.empty();
    }

    public boolean insideCleanupDirectory(String path) {
        return cleanupDirectoryFor(path).isPresent();
    }

    public Collection<OptionalGroup> optionalGroups() {
        return groups.values();
    }

    public Optional<OptionalGroup> optionalGroup(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(groups.get(id));
    }

    /** The group a published file belongs to; required files never belong to a group. */
    public Optional<OptionalGroup> groupOf(String path) {
        return file(path).flatMap(file -> optionalGroup(file.optionalGroup()));
    }

    public boolean released(String path) {
        return released.contains(ManagedPaths.fold(path));
    }

    /** Removed paths whose self-managed player copies are kept instead of backed up. */
    public boolean retainedForSelfManaged(String path) {
        return retainedSelfManaged.contains(ManagedPaths.fold(path));
    }

    public Optional<Correction> correction(String path) {
        return Optional.ofNullable(corrections.get(ManagedPaths.fold(path)));
    }

    public List<Correction> corrections() {
        return manifest.corrections();
    }

    public List<Withdrawal> withdrawals() {
        return manifest.withdrawals();
    }

    /** Published mod files declaring the given mod ID. */
    public List<ManifestFile> filesForComponent(String componentId) {
        if (componentId == null) return List.of();
        return filesByComponent.getOrDefault(ManagedPaths.fold(componentId), List.of());
    }

    public boolean publishesComponent(String componentId) {
        return !filesForComponent(componentId).isEmpty();
    }

    /** Finds the withdrawal matching a local file, by content or by mod ID and version. */
    public Optional<Match> withdrawalFor(String path, String sha256, String componentId,
                                         String version) {
        for (Withdrawal withdrawal : manifest.withdrawals()) {
            for (WithdrawalItem item : withdrawal.items()) {
                if (!inScope(item, path)) continue;
                boolean hash = sha256 != null && sha256.equals(item.sha256());
                boolean modVersion = item.componentId() != null && item.version() != null
                        && componentId != null && version != null
                        && ManagedPaths.fold(item.componentId()).equals(ManagedPaths.fold(componentId))
                        && item.version().equals(version);
                boolean removal = withdrawal.removal() && (!item.modScoped()
                        || (item.componentId() != null && componentId != null
                        && ManagedPaths.fold(item.componentId()).equals(ManagedPaths.fold(componentId)))
                        || ((item.componentId() == null || componentId == null)
                        && ManagedPaths.fold(item.path()).equals(ManagedPaths.fold(path))));
                if (hash || modVersion || removal) return Optional.of(new Match(withdrawal, item));
            }
        }
        return Optional.empty();
    }

    /** Whether a withdrawal could apply to this path, before any content is read. */
    public boolean withdrawalMayApply(String path) {
        for (Withdrawal withdrawal : manifest.withdrawals()) {
            for (WithdrawalItem item : withdrawal.items()) {
                if (inScope(item, path)) return true;
            }
        }
        return false;
    }

    /** Top-level directories that must be scanned for withdrawn mod copies. */
    public Set<String> withdrawalDirectories() {
        Set<String> directories = new java.util.TreeSet<>();
        for (Withdrawal withdrawal : manifest.withdrawals()) {
            for (WithdrawalItem item : withdrawal.items()) {
                if (item.modScoped() && item.path().indexOf('/') > 0) {
                    directories.add(ManagedPaths.topLevel(item.path()));
                }
            }
        }
        return directories;
    }

    private static boolean inScope(WithdrawalItem item, String path) {
        if (!item.modScoped()) return ManagedPaths.fold(item.path()).equals(ManagedPaths.fold(path));
        String top = ManagedPaths.topLevel(item.path());
        return ManagedPaths.isBelow(path, top) && ManagedPaths.fold(path).endsWith(".jar");
    }

    public record Match(Withdrawal withdrawal, WithdrawalItem item) {
    }
}
