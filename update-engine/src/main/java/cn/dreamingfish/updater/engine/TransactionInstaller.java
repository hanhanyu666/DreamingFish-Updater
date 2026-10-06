package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TransactionInstaller {
    private static final DateTimeFormatter ARCHIVE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(ZoneId.systemDefault());
    private static final List<String> METADATA_NAMES = List.of(
            "verified-installation.json", "trust-state.json",
            "release-manifest.json", "release-manifest.sig",
            EnginePaths.MAINTENANCE_STATE_FILE
    );

    private final JsonCodec json = new JsonCodec();
    private final LocalInstallationStore localStore;
    private final TransactionFaultInjector faultInjector;

    TransactionInstaller(LocalInstallationStore localStore) {
        this(localStore, TransactionFaultInjector.NONE);
    }

    TransactionInstaller(LocalInstallationStore localStore, TransactionFaultInjector faultInjector) {
        this.localStore = localStore;
        this.faultInjector = faultInjector;
    }

    boolean hasPendingTransactions(EnginePaths paths) {
        if (!Files.isDirectory(paths.transactions(), LinkOption.NOFOLLOW_LINKS)) return false;
        try (var entries = Files.list(paths.transactions())) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.RECOVERY_FAILED,
                    "Unable to inspect unfinished update transactions", e);
        }
    }

    void recover(EnginePaths paths, ProgressListener listener) {
        if (!Files.isDirectory(paths.transactions(), LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(paths.transactions())) {
            for (Path directory : entries.sorted().toList()) {
                requireSafeDirectory(directory, "Unsafe update transaction entry");
                recoverOne(paths, directory, listener);
            }
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.RECOVERY_FAILED,
                    "Unable to enumerate unfinished update transactions", e);
        }
    }

    InstallResult install(EnginePaths paths, UpdatePlan plan, ProgressListener listener,
                          LocalFileOverrides overrides, CancellationToken cancellationToken) {
        return install(paths, plan, listener, overrides, LocalFileIndex.transientIndex(),
                cancellationToken);
    }

    InstallResult install(EnginePaths paths, UpdatePlan plan, ProgressListener listener,
                          LocalFileOverrides overrides, LocalFileIndex index,
                          CancellationToken cancellationToken) {
        String id = UUID.randomUUID().toString();
        Path directory = paths.transactions().resolve(id);
        Path backupRoot = directory.resolve("backup");
        Path metadataRoot = directory.resolve("metadata");
        Path pendingArchiveRoot = directory.resolve("archive");
        Path journalPath = directory.resolve("journal.json");
        String archiveDirectory = plan.archiveCount() == 0
                ? null
                : archiveDirectory(plan, id);
        TransactionJournal journal = null;
        try {
            Files.createDirectories(backupRoot);
            Files.createDirectories(metadataRoot);
            if (archiveDirectory != null) Files.createDirectories(pendingArchiveRoot);
            List<TransactionEntry> entries = backupFiles(paths, plan.operations(), backupRoot);
            List<String> metadataFiles = backupMetadata(paths, metadataRoot);
            journal = new TransactionJournal(TransactionJournal.SCHEMA_VERSION, id,
                    TransactionPhase.BACKED_UP, plan.release().manifest().releaseId(),
                    plan.release().manifest().sequence(), java.time.Instant.now(), entries,
                    metadataFiles, archiveDirectory);
            writeJournal(journalPath, journal);
            faultInjector.afterPhase(TransactionPhase.BACKED_UP);

            journal = journal.withPhase(TransactionPhase.COMMITTING);
            writeJournal(journalPath, journal);
            faultInjector.afterPhase(TransactionPhase.COMMITTING);
            List<ArchivedFile> archived = new ArrayList<>();
            for (int i = 0; i < plan.operations().size(); i++) {
                cancellationToken.throwIfCancelled();
                FileOperation operation = plan.operations().get(i);
                listener.onProgress(new ProgressEvent(UpdateStage.INSTALLING,
                        switch (operation.kind()) {
                            case INSTALL -> operation.archiveExisting()
                                    ? "正在备份本地副本并安装文件" : "正在安装文件";
                            case DELETE -> "正在移除旧文件";
                            case ARCHIVE -> "正在把本地文件移入备份";
                        },
                        operation.path(), i, plan.operations().size()));
                ArchivedFile entry = apply(paths, operation, pendingArchiveRoot);
                if (entry != null) archived.add(entry);
                faultInjector.afterOperation(i);
            }

            verifyTarget(paths, plan.release(), overrides, index, listener, cancellationToken);
            localStore.save(paths, plan.release(), plan.nextState());
            Path finalArchive = finalizeArchive(paths, plan, pendingArchiveRoot, archiveDirectory,
                    archived);
            faultInjector.beforeCommit();
            journal = journal.withPhase(TransactionPhase.COMMITTED);
            writeJournal(journalPath, journal);
            faultInjector.afterPhase(TransactionPhase.COMMITTED);
            deleteTree(directory);
            List<Path> archivedFiles = archived.stream()
                    .map(file -> Path.of(file.originalPath()))
                    .toList();
            return new InstallResult(archivedFiles, finalArchive, archived);
        } catch (Exception e) {
            if (journal != null && journal.phase() == TransactionPhase.COMMITTING) {
                try {
                    restore(paths, directory, journal);
                    deleteTree(directory);
                } catch (Exception restoreFailure) {
                    e.addSuppressed(restoreFailure);
                    throw new UpdateException(UpdateErrorCode.RECOVERY_FAILED,
                            "Update failed and the previous installation could not be restored", e);
                }
            } else {
                try {
                    deleteTree(directory);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            if (e instanceof UpdateException updateException) throw updateException;
            throw new UpdateException(UpdateErrorCode.TRANSACTION_FAILED,
                    "Unable to commit update transaction", e);
        }
    }

    private void recoverOne(EnginePaths paths, Path directory, ProgressListener listener) {
        Path journalPath = directory.resolve("journal.json");
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                deleteTree(directory);
                return;
            } catch (IOException e) {
                throw recoveryFailure(directory, e);
            }
        }
        if (!Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(journalPath)) {
            throw recoveryFailure(directory,
                    new IllegalStateException("Transaction journal is not a safe regular file"));
        }
        TransactionJournal journal;
        try {
            journal = json.read(journalPath, TransactionJournal.class);
        } catch (Exception e) {
            throw recoveryFailure(directory, e);
        }
        if (journal.schemaVersion() != TransactionJournal.SCHEMA_VERSION
                || !directory.getFileName().toString().equals(journal.id())
                || journal.phase() == null || journal.entries() == null
                || journal.metadataFiles() == null) {
            throw recoveryFailure(directory, new IllegalStateException("Invalid transaction journal identity"));
        }
        listener.onProgress(new ProgressEvent(UpdateStage.RECOVERING,
                "正在恢复上次中断的更新", null, 0, 0));
        try {
            if (journal.phase() == TransactionPhase.COMMITTING) {
                restore(paths, directory, journal);
            }
            deleteTree(directory);
        } catch (Exception e) {
            throw recoveryFailure(directory, e);
        }
    }

    private List<TransactionEntry> backupFiles(EnginePaths paths, List<FileOperation> operations,
                                               Path backupRoot) throws IOException {
        List<TransactionEntry> entries = new ArrayList<>();
        for (FileOperation operation : operations) {
            Path target = resolve(paths.instanceRoot(), operation.path());
            boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (existed) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                            "Managed path is not a regular file: " + operation.path());
                }
                Path backup = resolve(backupRoot, operation.path());
                Files.createDirectories(backup.getParent());
                Files.copy(target, backup);
                AtomicFileSupport.force(backup);
            }
            entries.add(new TransactionEntry(operation.path(), existed));
        }
        return entries;
    }

    private List<String> backupMetadata(EnginePaths paths, Path metadataRoot) throws IOException {
        List<String> present = new ArrayList<>();
        for (String name : METADATA_NAMES) {
            Path source = paths.state().resolve(name);
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                Path backup = metadataRoot.resolve(name);
                Files.copy(source, backup);
                AtomicFileSupport.force(backup);
                present.add(name);
            } else if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                        "Local metadata path is not a regular file: " + name);
            }
        }
        return present;
    }

    private ArchivedFile apply(EnginePaths paths, FileOperation operation, Path pendingArchiveRoot)
            throws IOException {
        Path target = resolve(paths.instanceRoot(), operation.path());
        if (operation.kind() == OperationKind.DELETE) {
            Files.deleteIfExists(target);
            AtomicFileSupport.forceDirectory(target.getParent());
            return null;
        }
        if (operation.kind() == OperationKind.ARCHIVE) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target)) {
                throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                        "Archive source is missing or unsafe: " + operation.path());
            }
            ArchivedFile entry = archiveCopy(target, operation, pendingArchiveRoot);
            Files.delete(target);
            AtomicFileSupport.forceDirectory(target.getParent());
            return entry;
        }
        Path source = paths.cacheObject(operation.sha256());
        if (!isValid(source, operation.sha256(), operation.size())) {
            throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                    "Cached object changed before installation: " + operation.sha256());
        }
        ArchivedFile entry = null;
        if (operation.archiveExisting() && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateException(UpdateErrorCode.PATH_UNSAFE,
                        "Managed path is not a regular file: " + operation.path());
            }
            entry = archiveCopy(target, operation, pendingArchiveRoot);
        }
        AtomicFileSupport.copyReplace(source, target);
        setExecutable(target, operation.executable());
        return entry;
    }

    private ArchivedFile archiveCopy(Path target, FileOperation operation, Path pendingArchiveRoot)
            throws IOException {
        Path pending = resolve(pendingArchiveRoot, operation.path());
        AtomicFileSupport.copyReplace(target, pending);
        String hash = operation.localSha256() != null ? operation.localSha256()
                : CryptoSupport.sha256(pending);
        ArchiveReason reason = operation.reason() == null ? ArchiveReason.CLEANUP : operation.reason();
        return new ArchivedFile(operation.path(), operation.path(), hash, Files.size(pending),
                reason, operation.detail(), operation.componentId(), operation.version(), null);
    }

    private void verifyTarget(EnginePaths paths, SignedRelease release,
                              LocalFileOverrides overrides, LocalFileIndex index,
                              ProgressListener listener, CancellationToken cancellationToken) {
        MaintenanceModel model = MaintenanceModel.of(release.manifest());
        List<ManifestFile> checked = release.manifest().files().stream()
                .filter(file -> verification(model, overrides, file) != Verification.NONE)
                .toList();
        long total = checked.stream()
                .filter(file -> verification(model, overrides, file) == Verification.CONTENT)
                .mapToLong(ManifestFile::size).sum();
        long complete = 0;
        for (ManifestFile file : checked) {
            cancellationToken.throwIfCancelled();
            Path target = resolve(paths.instanceRoot(), file.path());
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateException(UpdateErrorCode.TRANSACTION_FAILED,
                        "Installed file is missing: " + file.path());
            }
            if (verification(model, overrides, file) == Verification.CONTENT) {
                boolean valid;
                try {
                    valid = index.matches(target, file.path(), file.sha256(), file.size());
                } catch (IOException e) {
                    valid = false;
                }
                if (!valid) {
                    throw new UpdateException(UpdateErrorCode.HASH_MISMATCH,
                            "Installed file failed verification: " + file.path());
                }
                complete += file.size();
                listener.onProgress(new ProgressEvent(UpdateStage.VERIFYING,
                        "正在校验已安装文件", file.path(), complete, total));
            }
        }
    }

    enum Verification { NONE, PRESENCE, CONTENT }

    /**
     * What a completed installation guarantees for a published file. Files the
     * player keeps out of maintenance and "首次提供" files are not checked;
     * default configurations only have to exist because the player may change them.
     */
    static Verification verification(MaintenanceModel model, LocalFileOverrides overrides,
                                     ManifestFile file) {
        if (overrides.excludes(file, model)) return Verification.NONE;
        return switch (model.behaviorOf(file)) {
            case REQUIRED, SYNC -> Verification.CONTENT;
            case DEFAULT_CONFIG, LEGACY_MISSING_ONLY -> Verification.PRESENCE;
            case INITIAL -> Verification.NONE;
        };
    }

    private void restore(EnginePaths paths, Path directory, TransactionJournal journal) throws IOException {
        Path backupRoot = directory.resolve("backup");
        Path metadataRoot = directory.resolve("metadata");
        requireSafeDirectory(backupRoot, "Unsafe transaction backup directory");
        requireSafeDirectory(metadataRoot, "Unsafe transaction metadata directory");
        for (TransactionEntry entry : journal.entries()) {
            Path target = resolve(paths.instanceRoot(), entry.path());
            if (entry.originalExisted()) {
                Path backup = resolve(backupRoot, entry.path());
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Transaction backup is missing for " + entry.path());
                }
                AtomicFileSupport.copyReplace(backup, target);
            } else {
                Files.deleteIfExists(target);
                AtomicFileSupport.forceDirectory(target.getParent());
            }
        }

        Set<String> originallyPresent = Set.copyOf(journal.metadataFiles());
        for (String name : METADATA_NAMES) {
            Path target = paths.state().resolve(name);
            if (originallyPresent.contains(name)) {
                Path backup = metadataRoot.resolve(name);
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Transaction metadata backup is missing for " + name);
                }
                AtomicFileSupport.copyReplace(backup, target);
            } else {
                Files.deleteIfExists(target);
            }
        }
        Path committedArchive = resolveArchiveDirectory(paths, journal.archiveDirectory());
        if (committedArchive != null) {
            deleteTree(committedArchive);
        }
    }

    private Path finalizeArchive(EnginePaths paths, UpdatePlan plan, Path pendingArchiveRoot,
                                 String archiveDirectory, List<ArchivedFile> archived)
            throws IOException {
        if (archiveDirectory == null) return null;
        requireSafeDirectory(pendingArchiveRoot, "Unsafe pending player backup archive");
        String archiveId = archiveDirectory.substring(archiveDirectory.lastIndexOf('/') + 1);
        ArchiveIndex archiveIndex = new ArchiveIndex(ArchiveIndex.SCHEMA_VERSION, archiveId,
                plan.release().manifest().releaseId(), plan.release().manifest().displayVersion(),
                java.time.Instant.now(), archived);
        AtomicFileSupport.write(pendingArchiveRoot.resolve(ArchiveIndex.FILE_NAME),
                json.writePretty(archiveIndex));
        StringBuilder text = new StringBuilder("DreamingFish player backup\n")
                .append("Release: ").append(plan.release().manifest().displayVersion())
                .append(" (").append(plan.release().manifest().releaseId()).append(")\n")
                .append("Archived files: ").append(archived.size()).append("\n\n");
        for (ArchivedFile file : archived) {
            text.append(file.originalPath()).append("  —  ").append(file.reason().description());
            if (!file.detail().isBlank()) text.append("：").append(file.detail());
            text.append('\n');
        }
        AtomicFileSupport.write(pendingArchiveRoot.resolve("archived-files.txt"),
                text.toString().getBytes(StandardCharsets.UTF_8));
        Path target = resolveArchiveDirectory(paths, archiveDirectory);
        if (target == null || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player backup archive destination already exists or is invalid");
        }
        Files.createDirectories(target.getParent());
        AtomicFileSupport.moveReplace(pendingArchiveRoot, target);
        AtomicFileSupport.forceDirectory(target.getParent());
        return target;
    }

    private String archiveDirectory(UpdatePlan plan, String transactionId) {
        String name = ARCHIVE_TIME.format(java.time.Instant.now()) + "_"
                + plan.release().manifest().releaseId() + "_"
                + transactionId.substring(0, 8);
        return EnginePaths.ARCHIVE_ROOT + "/" + name;
    }

    private Path resolveArchiveDirectory(EnginePaths paths, String archiveDirectory) throws IOException {
        if (archiveDirectory == null) return null;
        String normalized = PathSafety.normalizeManifestPath(archiveDirectory);
        String folded = normalized.toLowerCase(java.util.Locale.ROOT);
        Path root;
        if (folded.startsWith(EnginePaths.ARCHIVE_ROOT + "/")) {
            root = paths.archiveBackups();
        } else if (folded.startsWith(EnginePaths.LEGACY_ARCHIVE_ROOT + "/")) {
            // Journals written by earlier player versions.
            root = paths.forcedSyncBackups();
        } else {
            throw new IOException("Transaction archive path is outside the player backup roots");
        }
        Path target = PathSafety.resolveInside(paths.playerHome(), normalized);
        if (!target.startsWith(root)) {
            throw new IOException("Transaction archive path escapes the player backup root");
        }
        return target;
    }

    private void setExecutable(Path path, boolean executable) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return;
        Set<PosixFilePermission> permissions = new HashSet<>(view.readAttributes().permissions());
        Set<PosixFilePermission> execute = Set.of(PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
        if (executable) permissions.addAll(execute);
        else permissions.removeAll(execute);
        view.setPermissions(permissions);
    }

    private boolean isValid(Path file, String sha256, long size) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(file) == size && CryptoSupport.sha256(file).equals(sha256);
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolve(Path root, String path) {
        try {
            return PathSafety.resolveInside(root, path);
        } catch (IOException | ProtocolException e) {
            throw new UpdateException(UpdateErrorCode.PATH_UNSAFE, "Unsafe transaction path: " + path, e);
        }
    }

    private void writeJournal(Path path, TransactionJournal journal) throws IOException {
        AtomicFileSupport.write(path, json.writePretty(journal));
    }

    private static void deleteTree(Path root) throws IOException {
        PathSafety.assertSafePathTree(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                PathSafety.assertSafePathTree(path);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void requireSafeDirectory(Path directory, String message) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new UpdateException(UpdateErrorCode.RECOVERY_FAILED,
                    message + ": " + directory);
        }
    }

    private UpdateException recoveryFailure(Path directory, Throwable cause) {
        return new UpdateException(UpdateErrorCode.RECOVERY_FAILED,
                "Unable to recover update transaction " + directory.getFileName(), cause);
    }
}
