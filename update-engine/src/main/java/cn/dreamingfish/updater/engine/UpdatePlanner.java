package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.Correction;
import cn.dreamingfish.updater.protocol.CorrectionMode;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.MaintenanceModel.Behavior;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.Withdrawal;
import cn.dreamingfish.updater.protocol.WithdrawalItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a target release, the last verified installation, the player's choices
 * and the files on disk into one list of operations. Every path is decided
 * once, in this order of precedence:
 *
 * <ol>
 *   <li>protected updater paths are never touched;</li>
 *   <li>withdrawn versions are moved into the player backup;</li>
 *   <li>corrections and requested default restores put the published content in place;</li>
 *   <li>player choices keep files out of maintenance, except required files;</li>
 *   <li>the file's preset decides between install, update, keep and backup;</li>
 *   <li>files the release no longer contains are deleted or backed up;</li>
 *   <li>cleanup directories and duplicate mods are cleaned last.</li>
 * </ol>
 *
 * Releases without {@code maintenance-policy-v2} keep their historical
 * semantics: player exemptions win over deletions, removed files are deleted
 * even when modified, and duplicate mods are only reported.
 */
final class UpdatePlanner {
    UpdatePlan create(EnginePaths paths, SignedRelease target, LocalInstallation local,
                      LocalFileOverrides choices, LocalFileIndex index,
                      ProgressListener listener, CancellationToken cancellationToken) {
        return new Run(paths, target, local, choices, index, listener, cancellationToken).plan();
    }

    private static final class Run {
        private final EnginePaths paths;
        private final SignedRelease target;
        private final MaintenanceModel model;
        private final boolean v2;
        private final MaintenanceModel baselineModel;
        private final Map<String, InstalledFileState> baseline = new LinkedHashMap<>();
        private final LocalFileOverrides choices;
        private final LocalFileIndex index;
        private final MaintenanceState state;
        private final ProgressListener listener;
        private final CancellationToken cancellation;

        private final Map<String, FileOperation> operations = new LinkedHashMap<>();
        private final Map<String, Long> requiredObjects = new LinkedHashMap<>();
        private final Map<String, Withdrawn> withdrawn = new LinkedHashMap<>();
        private final List<Path> released = new ArrayList<>();
        private final List<Path> keptModified = new ArrayList<>();
        private final List<Path> skippedSelfManaged = new ArrayList<>();
        private final List<Path> resetPaths = new ArrayList<>();
        private final Set<String> newlyInitialized = new LinkedHashSet<>();
        private final Set<String> newlyApplied = new LinkedHashSet<>();

        private record Withdrawn(String path, MaintenanceModel.Match match,
                                 LocalFileIndex.Inspection local) {
        }

        Run(EnginePaths paths, SignedRelease target, LocalInstallation local,
            LocalFileOverrides choices, LocalFileIndex index, ProgressListener listener,
            CancellationToken cancellation) {
            this.paths = paths;
            this.target = target;
            this.model = MaintenanceModel.of(target.manifest());
            this.v2 = model.policyV2();
            this.baselineModel = local == null ? null : MaintenanceModel.of(local.release().manifest());
            if (local != null) {
                local.installation().files().forEach(file ->
                        baseline.put(ManagedPaths.fold(file.path()), file));
            }
            this.choices = choices == null ? LocalFileOverrides.NONE : choices;
            this.index = index == null ? LocalFileIndex.transientIndex() : index;
            this.state = local == null || local.state() == null
                    ? MaintenanceState.empty(target.manifest().projectId()) : local.state();
            this.listener = listener == null ? ProgressListener.NONE : listener;
            this.cancellation = cancellation == null ? CancellationToken.NEVER : cancellation;
        }

        UpdatePlan plan() {
            validateProtectedPaths();
            if (v2) findWithdrawnCopies();
            planTargetFiles();
            planRemovedFiles();
            planCleanupDirectories();
            planWithdrawnLeftovers();
            if (v2) planDuplicateMods();
            List<Path> unmanaged = findUnmanagedMods();

            MaintenanceState next = state.with(newlyInitialized, newlyApplied);
            List<FileOperation> ordered = new ArrayList<>(operations.values());
            ordered.sort(Comparator.comparing(FileOperation::path));
            return new UpdatePlan(target, ordered, requiredObjects, unmanaged,
                    sortedPaths(released), sortedPaths(keptModified),
                    sortedPaths(skippedSelfManaged), sortedPaths(resetPaths),
                    next, !next.equals(state));
        }

        private void validateProtectedPaths() {
            for (String directory : model.cleanupDirectories()) {
                ProtectedPathPolicy.validate(paths, directory);
            }
            for (String file : target.manifest().forcedSyncFiles()) {
                ProtectedPathPolicy.validate(paths, file);
            }
            for (ManifestFile file : model.files()) {
                ProtectedPathPolicy.validate(paths, file.path());
            }
            for (Correction correction : model.corrections()) {
                ProtectedPathPolicy.validate(paths, correction.path());
            }
            for (String directory : model.withdrawalDirectories()) {
                ProtectedPathPolicy.validate(paths, directory);
            }
            for (Withdrawal withdrawal : model.withdrawals()) {
                for (WithdrawalItem item : withdrawal.items()) {
                    if (!item.modScoped()) ProtectedPathPolicy.validate(paths, item.path());
                }
            }
        }

        // ---- withdrawals -------------------------------------------------------------

        private void findWithdrawnCopies() {
            if (model.withdrawals().isEmpty()) return;
            for (Withdrawal withdrawal : model.withdrawals()) {
                for (WithdrawalItem item : withdrawal.items()) {
                    if (item.modScoped()) continue;
                    Path file = resolve(item.path());
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        considerWithdrawn(file, item.path(), false);
                    }
                }
            }
            for (String directory : model.withdrawalDirectories()) {
                Path root = resolve(directory);
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
                for (Path file : SafeWalk.regularFiles(root, SafeWalk.Mode.LENIENT,
                        "Withdrawal directory " + directory)) {
                    cancellation.throwIfCancelled();
                    String relative = relative(file);
                    if (!relative.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) continue;
                    considerWithdrawn(file, relative, true);
                }
            }
        }

        private void considerWithdrawn(Path file, String relative, boolean metadata) {
            if (!model.withdrawalMayApply(relative)) return;
            LocalFileIndex.Inspection local = inspect(file, relative, metadata);
            model.withdrawalFor(relative, local.sha256(), local.componentId(), local.version())
                    .ifPresent(match -> withdrawn.put(ManagedPaths.fold(relative),
                            new Withdrawn(relative, match, local)));
        }

        private void planWithdrawnLeftovers() {
            for (Withdrawn copy : withdrawn.values()) {
                String key = ManagedPaths.fold(copy.path());
                if (operations.containsKey(key)) continue;
                put(FileOperation.archive(copy.path(), copy.local().size(), withdrawalReason(copy),
                        copy.match().withdrawal().reason(), copy.local()));
                progress("服主撤回了问题版本", copy.path());
            }
        }

        // ---- published files ---------------------------------------------------------

        private void planTargetFiles() {
            List<ManifestFile> files = new ArrayList<>(model.files());
            files.sort(Comparator.comparing(ManifestFile::path));
            for (ManifestFile file : files) {
                cancellation.throwIfCancelled();
                String key = ManagedPaths.fold(file.path());
                Behavior behavior = model.behaviorOf(file);
                Path destination = resolve(file.path());
                boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
                if (exists && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                            "Managed file path is not a regular file: " + file.path());
                }
                boolean excluded = choices.excludes(file, model);

                Withdrawn withdrawnCopy = withdrawn.get(key);
                if (withdrawnCopy != null) {
                    String reason = withdrawnCopy.match().withdrawal().reason();
                    if (excluded) {
                        put(FileOperation.archive(file.path(), withdrawnCopy.local().size(),
                                withdrawalReason(withdrawnCopy), reason, withdrawnCopy.local()));
                    } else {
                        replace(file, withdrawalReason(withdrawnCopy), reason, withdrawnCopy.local());
                    }
                    continue;
                }

                if (v2) {
                    Correction correction = model.correction(file.path()).orElse(null);
                    if (correction != null && planCorrection(file, correction, destination, exists)) {
                        continue;
                    }
                }
                if ((behavior == Behavior.DEFAULT_CONFIG || behavior == Behavior.INITIAL)
                        && choices.resetRequested(file.path())) {
                    resetPaths.add(Path.of(file.path()));
                    if (behavior == Behavior.INITIAL) newlyInitialized.add(file.path());
                    if (file.componentId() != null && ManagedPaths.isModJar(file.path())) {
                        for (Path jar : modJars()) {
                            String path = relative(jar);
                            if (path.equalsIgnoreCase(file.path()) || model.contains(path)) continue;
                            LocalFileIndex.Inspection copy = inspect(jar, path, true);
                            if (file.componentId().equalsIgnoreCase(copy.componentId())) {
                                put(FileOperation.archive(path, copy.size(), ArchiveReason.RESET_DEFAULT, null, copy));
                            }
                        }
                    }
                    if (!exists) {
                        install(file);
                    } else {
                        LocalFileIndex.Inspection local = inspect(destination, file.path(), false);
                        if (!sameContent(local, file.sha256(), file.size())) {
                            replace(file, ArchiveReason.RESET_DEFAULT, null, local);
                        }
                    }
                    continue;
                }

                if (excluded) {
                    if (choices.selfManages(file)) {
                        InstalledFileState previous = baseline.get(key);
                        if (previous == null || !previous.sha256().equals(file.sha256())) {
                            skippedSelfManaged.add(Path.of(file.path()));
                        }
                    }
                    progress("保留玩家已取消管理的文件", file.path());
                    continue;
                }

                switch (behavior) {
                    case REQUIRED, SYNC -> planConverging(file, destination, exists);
                    case LEGACY_MISSING_ONLY -> {
                        if (!exists) install(file);
                    }
                    case INITIAL -> planInitial(file, exists);
                    case DEFAULT_CONFIG -> planDefaultConfig(file, destination, exists);
                }
                progress("正在扫描受管理文件", file.path());
            }
        }

        private void planConverging(ManifestFile file, Path destination, boolean exists) {
            if (!exists) {
                install(file);
                return;
            }
            LocalFileIndex.Inspection local = inspect(destination, file.path(), false);
            if (sameContent(local, file.sha256(), file.size())) return;
            InstalledFileState previous = baseline.get(ManagedPaths.fold(file.path()));
            if (previous != null && sameContent(local, previous.sha256(), previous.size())) {
                install(file);
            } else {
                replace(file, previous == null ? ArchiveReason.TAKEOVER
                        : ArchiveReason.REPLACED_MODIFIED, null, local);
            }
        }

        private void planInitial(ManifestFile file, boolean exists) {
            String identity = file.componentId() == null ? file.path()
                    : ManagedPaths.topLevel(file.path()) + "/" + cn.dreamingfish.updater.protocol.CryptoSupport.sha256(
                    ManagedPaths.fold(file.componentId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/.initial";
            if (state.initialized(file.path()) || state.initialized(identity)) return;
            boolean provided = baselineModel != null && file.componentId() != null
                    && baselineModel.filesForComponent(file.componentId()).stream()
                    .anyMatch(old -> state.initialized(old.path()));
            if (!exists && !provided && file.componentId() != null) {
                provided = modJars().stream().anyMatch(jar -> {
                    LocalFileIndex.Inspection copy = inspect(jar, relative(jar), true);
                    return file.componentId().equalsIgnoreCase(copy.componentId());
                });
            }
            if (!exists && !provided) install(file);
            newlyInitialized.add(file.path());
            newlyInitialized.add(identity);
        }

        private void planDefaultConfig(ManifestFile file, Path destination, boolean exists) {
            if (!exists) {
                install(file);
                return;
            }
            LocalFileIndex.Inspection local = inspect(destination, file.path(), false);
            if (sameContent(local, file.sha256(), file.size())) return;
            InstalledFileState previous = baseline.get(ManagedPaths.fold(file.path()));
            if (previous != null && sameContent(local, previous.sha256(), previous.size())) {
                install(file);
                return;
            }
            if (previous == null || !previous.sha256().equals(file.sha256())) {
                keptModified.add(Path.of(file.path()));
            }
        }

        private boolean planCorrection(ManifestFile file, Correction correction,
                                       Path destination, boolean exists) {
            LocalFileIndex.Inspection local = exists
                    ? inspect(destination, file.path(), false) : null;
            if (correction.mode() == CorrectionMode.KNOWN_BAD) {
                boolean affected = local != null && correction.badSha256().contains(local.sha256());
                affected |= choices.storedProblemVersion(file.componentId(), correction.badSha256());
                if (ManagedPaths.isModJar(file.path())) {
                    for (Path jar : modJars()) {
                        String path = relative(jar);
                        if (path.equalsIgnoreCase(file.path()) || model.contains(path) || operations.containsKey(ManagedPaths.fold(path))) continue;
                        LocalFileIndex.Inspection copy = inspect(jar, path, true);
                        if (correction.badSha256().contains(copy.sha256()) && (file.componentId() == null
                                || file.componentId().equalsIgnoreCase(copy.componentId()))) {
                            put(FileOperation.archive(path, copy.size(), ArchiveReason.CORRECTED, correction.reason(), copy));
                            affected = true;
                        }
                    }
                }
                if (!affected) return false;
                if (local != null && sameContent(local, file.sha256(), file.size())) return true;
            } else {
                if (state.correctionApplied(correction.id())) return false;
                newlyApplied.add(correction.id());
                if (local != null && sameContent(local, file.sha256(), file.size())) return true;
            }
            if (local == null) {
                install(file);
            } else {
                replace(file, ArchiveReason.CORRECTED, correction.reason(), local);
            }
            progress("服主修正了文件", file.path());
            return true;
        }

        // ---- files the release no longer contains -----------------------------------

        private void planRemovedFiles() {
            // New complete targets declare removals explicitly. Inferring ownership from an old
            // baseline here would make restored files depend on which releases the player skipped.
            if (model.simplified()) return;
            for (InstalledFileState previous : baseline.values()) {
                cancellation.throwIfCancelled();
                String key = ManagedPaths.fold(previous.path());
                if (model.contains(previous.path()) || operations.containsKey(key)
                        || withdrawn.containsKey(key)) {
                    continue;
                }
                if (model.released(previous.path())) {
                    released.add(Path.of(previous.path()));
                    progress("保留管理端已放弃管理的文件", previous.path());
                    continue;
                }
                Behavior previousBehavior = baselineModel == null ? legacyBehavior(previous)
                        : baselineModel.behavior(previous.path()).orElse(legacyBehavior(previous));
                if (!previousBehavior.removedWithRelease() && !model.simplified()) continue;

                ProtectedPathPolicy.validate(paths, previous.path());
                Path destination = resolve(previous.path());
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                            "Removed managed path is not a regular file: " + previous.path());
                }
                ManifestFile previousFile = baselineModel == null ? null
                        : baselineModel.file(previous.path()).orElse(null);
                if (model.simplified() && previousFile != null && previousFile.componentId() != null
                        && model.filesForComponent(previousFile.componentId()).stream()
                        .anyMatch(file -> model.behaviorOf(file) == Behavior.INITIAL)) {
                    continue; // A renamed initial resource belongs to the player, not to daily sync.
                }

                if (model.insideCleanupDirectory(previous.path())) {
                    // Historical releases leave this to the cleanup scan, which archives it.
                    if (v2) removeOfficialCopy(previous, destination, ArchiveReason.REMOVED_MODIFIED);
                    continue;
                }
                if (choices.excludes(previousFile)) {
                    if (!v2) continue;
                    if (previousFile != null && choices.excludesComponent(previousFile.componentId())
                            && model.publishesComponent(previousFile.componentId())) {
                        continue; // The same mod is still published; the player keeps managing it.
                    }
                    if (model.retainedForSelfManaged(previous.path()) && !model.simplified()) continue;
                    LocalFileIndex.Inspection local = inspect(destination, previous.path(), true);
                    put(FileOperation.archive(previous.path(), local.size(),
                            ArchiveReason.REMOVED_SELF_MANAGED, null, local));
                    continue;
                }
                if (!v2) {
                    put(FileOperation.delete(previous.path(), previous.sha256(), previous.size()));
                    continue;
                }
                removeOfficialCopy(previous, destination, ArchiveReason.REMOVED_MODIFIED);
            }
        }

        private void removeOfficialCopy(InstalledFileState previous, Path destination,
                                        ArchiveReason modifiedReason) {
            LocalFileIndex.Inspection local = inspect(destination, previous.path(), true);
            if (model.simplified()) {
                put(FileOperation.archive(previous.path(), local.size(), ArchiveReason.OWNER_REMOVED, null, local));
                progress("正在备份并移出服主已移除的文件", previous.path());
                return;
            }
            if (sameContent(local, previous.sha256(), previous.size())) {
                put(FileOperation.delete(previous.path(), previous.sha256(), previous.size()));
            } else {
                put(FileOperation.archive(previous.path(), local.size(), modifiedReason, null, local));
            }
            progress("正在移除旧文件", previous.path());
        }

        private static Behavior legacyBehavior(InstalledFileState previous) {
            return previous.policy() == FilePolicy.LEGACY_MISSING_ONLY
                    ? Behavior.LEGACY_MISSING_ONLY : Behavior.SYNC;
        }

        private static ArchiveReason withdrawalReason(Withdrawn copy) {
            return copy.match().withdrawal().removal() ? ArchiveReason.OWNER_REMOVED : ArchiveReason.WITHDRAWN;
        }

        // ---- cleanup directories and duplicates ------------------------------------

        private void planCleanupDirectories() {
            for (String directory : model.cleanupDirectories()) {
                cancellation.throwIfCancelled();
                Path root = resolve(directory);
                if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                    throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                            "Forced sync path is not a safe directory: " + directory);
                }
                for (Path file : SafeWalk.regularFiles(root, SafeWalk.Mode.STRICT,
                        "Forced sync directory " + directory)) {
                    cancellation.throwIfCancelled();
                    String relative = relative(file);
                    String key = ManagedPaths.fold(relative);
                    if (model.contains(relative) || operations.containsKey(key)
                            || withdrawn.containsKey(key)) {
                        continue;
                    }
                    LocalFileIndex.Inspection local = v2 ? inspect(file, relative, false) : null;
                    put(FileOperation.archive(relative, size(file), ArchiveReason.CLEANUP,
                            directory, local));
                    progress("正在扫描强制同步目录", relative);
                }
            }
        }

        private void planDuplicateMods() {
            Map<String, String> active = new HashMap<>();
            for (ManifestFile file : model.files()) {
                if (file.componentId() == null || !ManagedPaths.isModJar(file.path())) continue;
                if (model.behaviorOf(file) == Behavior.INITIAL) continue;
                if (choices.excludes(file, model)) continue;
                active.putIfAbsent(ManagedPaths.fold(file.componentId()), file.path());
            }
            if (active.isEmpty()) return;
            for (Path jar : modJars()) {
                cancellation.throwIfCancelled();
                String relative = relative(jar);
                String key = ManagedPaths.fold(relative);
                if (model.contains(relative) || operations.containsKey(key)
                        || withdrawn.containsKey(key) || model.insideCleanupDirectory(relative)) {
                    continue;
                }
                LocalFileIndex.Inspection local = inspect(jar, relative, true);
                if (local.componentId() == null) continue;
                String publishedPath = active.get(ManagedPaths.fold(local.componentId()));
                if (publishedPath != null) {
                    put(FileOperation.archive(relative, local.size(), ArchiveReason.DUPLICATE,
                            publishedPath, local));
                    progress("移出重复的模组", relative);
                }
            }
        }

        private List<Path> findUnmanagedMods() {
            Set<String> managed = new java.util.HashSet<>();
            model.files().stream()
                    .map(ManifestFile::path)
                    .filter(path -> ManagedPaths.fold(path).startsWith("mods/"))
                    .forEach(path -> managed.add(ManagedPaths.fold(path)));
            operations.values().stream()
                    .filter(operation -> operation.kind() == OperationKind.DELETE
                            || operation.kind() == OperationKind.ARCHIVE)
                    .map(operation -> ManagedPaths.fold(operation.path()))
                    .forEach(managed::add);
            List<Path> result = new ArrayList<>();
            for (Path jar : modJars()) {
                String relative = relative(jar);
                if (managed.contains(ManagedPaths.fold(relative))) continue;
                if (model.insideCleanupDirectory(relative)) continue;
                result.add(Path.of(relative));
            }
            result.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER));
            return result;
        }

        private List<Path> modJars() {
            Path mods = paths.instanceRoot().resolve("mods");
            if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(mods)) {
                return List.of();
            }
            try {
                PathSafety.assertSafePathTree(mods);
            } catch (IOException | ProtocolException unsafe) {
                return List.of();
            }
            return SafeWalk.regularFiles(mods, SafeWalk.Mode.LENIENT, "Local mods directory").stream()
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .toList();
        }

        // ---- helpers -----------------------------------------------------------------

        private void install(ManifestFile file) {
            put(FileOperation.install(file.path(), file.sha256(), file.size(), file.executable()));
            require(file.sha256(), file.size());
        }

        private void replace(ManifestFile file, ArchiveReason reason, String detail,
                             LocalFileIndex.Inspection local) {
            put(FileOperation.replace(file.path(), file.sha256(), file.size(), file.executable(),
                    reason, detail, local));
            require(file.sha256(), file.size());
        }

        private void put(FileOperation operation) {
            operations.put(ManagedPaths.fold(operation.path()), operation);
        }

        private void require(String sha256, long size) {
            Long previous = requiredObjects.putIfAbsent(sha256, size);
            if (previous != null && previous != size) {
                throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                        "One object hash is declared with conflicting sizes");
            }
        }

        private LocalFileIndex.Inspection inspect(Path file, String relative, boolean metadata) {
            try {
                return index.inspect(file, relative, metadata);
            } catch (IOException e) {
                throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                        "Unable to inspect managed file " + relative, e);
            }
        }

        private static boolean sameContent(LocalFileIndex.Inspection local, String sha256, long size) {
            return local.size() == size && local.sha256().equals(sha256);
        }

        private static long size(Path file) {
            try {
                return Files.size(file);
            } catch (IOException e) {
                throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                        "Unable to inspect local file " + file, e);
            }
        }

        private String relative(Path file) {
            return paths.instanceRoot().relativize(file).toString().replace('\\', '/');
        }

        private Path resolve(String manifestPath) {
            try {
                return PathSafety.resolveInside(paths.instanceRoot(), manifestPath);
            } catch (IOException | ProtocolException e) {
                throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                        "Unsafe managed path: " + manifestPath, e);
            }
        }

        private void progress(String message, String path) {
            listener.onProgress(new ProgressEvent(UpdateStage.SCANNING, message, path, 0, 0));
        }

        private static List<Path> sortedPaths(List<Path> values) {
            return values.stream()
                    .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }
}
