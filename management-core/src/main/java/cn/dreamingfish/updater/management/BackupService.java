package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupService {
    private static final int BACKUP_FORMAT_VERSION = 1;
    private static final long MAX_RESTORE_BYTES = 4L * 1024 * 1024 * 1024 * 1024;

    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;
    private final EncryptedBackupCodec encryption = new EncryptedBackupCodec();

    public BackupService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths;
        this.database = database;
        this.json = json;
    }

    public Path create(Path destination, char[] password) {
        Path target = destination.toAbsolutePath().normalize();
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path snapshot = Files.createTempFile(paths.temporary(), "database-", ".db");
            Path zip = Files.createTempFile(paths.temporary(), "backup-", ".zip");
            Path encrypted = Files.createTempFile(paths.temporary(), "backup-", ".encrypted");
            try {
                database.createConsistentSnapshot(snapshot);
                writeZip(zip, snapshot);
                encryption.encrypt(zip, encrypted, password);
                AtomicFiles.moveReplace(encrypted, target);
                return target;
            } finally {
                Files.deleteIfExists(snapshot);
                Files.deleteIfExists(zip);
                Files.deleteIfExists(encrypted);
            }
        } catch (IOException e) {
            throw new ManagementException("Unable to create management backup", e);
        }
    }

    public void restore(Path archive, char[] password, boolean force) {
        Path source = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new ManagementException("Backup archive does not exist: " + source);
        }
        Path parent = paths.root().getParent();
        if (parent == null) {
            throw new ManagementException("Management data root must have a parent directory");
        }
        Path restoreRoot = parent.resolve(paths.root().getFileName() + ".restore-" + UUID.randomUUID());
        Path decryptedZip = parent.resolve(paths.root().getFileName() + ".restore-" + UUID.randomUUID() + ".zip");
        Path rollbackRoot = parent.resolve(paths.root().getFileName() + ".rollback-" + UUID.randomUUID());
        boolean oldMoved = false;
        boolean newMoved = false;
        try {
            Files.createDirectories(restoreRoot);
            encryption.decrypt(source, decryptedZip, password);
            extractZip(decryptedZip, restoreRoot);
            verifyRestoredData(restoreRoot);
            ManagementPaths.at(restoreRoot).initialize();

            if (Files.exists(paths.root()) && directoryHasEntries(paths.root())) {
                if (!force) {
                    throw new ManagementException("Management data directory is not empty; use --force after taking a backup");
                }
                Files.move(paths.root(), rollbackRoot);
                oldMoved = true;
            } else {
                Files.deleteIfExists(paths.root());
            }
            AtomicFiles.moveReplace(restoreRoot, paths.root());
            newMoved = true;
            if (oldMoved) {
                AtomicFiles.deleteRecursively(rollbackRoot);
                oldMoved = false;
            }
        } catch (IOException | RuntimeException e) {
            if (oldMoved && !newMoved) {
                try {
                    AtomicFiles.moveReplace(rollbackRoot, paths.root());
                    oldMoved = false;
                } catch (IOException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            if (e instanceof ManagementException managementException) {
                throw managementException;
            }
            throw new ManagementException("Unable to restore management backup", e);
        } finally {
            try {
                Files.deleteIfExists(decryptedZip);
                if (!newMoved) AtomicFiles.deleteRecursively(restoreRoot);
                if (oldMoved) AtomicFiles.deleteRecursively(rollbackRoot);
            } catch (IOException ignored) {
                // The primary restore result is more useful than a temporary cleanup failure.
            }
        }
    }

    private void writeZip(Path zip, Path databaseSnapshot) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(zip, StandardOpenOption.TRUNCATE_EXISTING)))) {
            putBytes(output, "backup-metadata.json", json.write(
                    new BackupMetadata(BACKUP_FORMAT_VERSION, Instant.now(), "0.1.0")));
            putFile(output, databaseSnapshot, "management.db");
            putTree(output, paths.keys(), "keys");
            putTree(output, paths.manifests(), "manifests");
            putTree(output, paths.playerPrograms(), "player-programs");
            putTree(output, paths.objects(), "objects/sha256");
        }
    }

    private void putTree(ZipOutputStream output, Path root, String zipRoot) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            List<Path> files = stream.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .sorted().toList();
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) {
                    throw new ManagementException("Backup source cannot contain symbolic links: " + file);
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                putFile(output, file, zipRoot + "/" + relative);
            }
        }
    }

    private static void putFile(ZipOutputStream output, Path source, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        Files.copy(source, output);
        output.closeEntry();
    }

    private static void putBytes(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private void extractZip(Path zip, Path destination) throws IOException {
        Set<String> names = new HashSet<>();
        long total = 0;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                validateZipEntry(name, names);
                Path target = destination.resolve(name.replace('/', java.io.File.separatorChar)).normalize();
                if (!target.startsWith(destination)) {
                    throw new ManagementException("Backup entry escapes the restore directory: " + name);
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(target,
                        StandardOpenOption.CREATE_NEW))) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            total += read;
                            if (total > MAX_RESTORE_BYTES) {
                                throw new ManagementException("Backup expands beyond the restore safety limit");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
        if (!Files.isRegularFile(destination.resolve("backup-metadata.json"))
                || !Files.isRegularFile(destination.resolve("management.db"))) {
            throw new ManagementException("Backup is missing required metadata or database");
        }
        BackupMetadata metadata = json.read(destination.resolve("backup-metadata.json"), BackupMetadata.class);
        if (metadata.formatVersion() != BACKUP_FORMAT_VERSION) {
            throw new ManagementException("Unsupported management backup version: " + metadata.formatVersion());
        }
        Files.delete(destination.resolve("backup-metadata.json"));
    }

    private void verifyRestoredData(Path root) {
        ManagementPaths restoredPaths = ManagementPaths.at(root);
        ManagementDatabase restoredDatabase = new ManagementDatabase(restoredPaths, json);
        ObjectStore restoredObjects = new ObjectStore(restoredPaths);
        Set<String> verifiedObjects = new HashSet<>();
        for (ProjectRecord project : restoredDatabase.listProjects()) {
            if (!Files.isRegularFile(project.privateKeyFile())) {
                throw new ManagementException("Backup is missing private key for " + project.id());
            }
            PublicKey publicKey = CryptoSupport.decodePublicKey(project.publicKey());
            byte[] keyChallenge = ("DreamingFish backup identity\n" + project.id())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] challengeSignature = CryptoSupport.sign(
                    keyChallenge, new ProjectKeyStore(restoredPaths).load(project));
            if (!CryptoSupport.verify(keyChallenge, challengeSignature, publicKey)) {
                throw new ManagementException("Backup private key does not match project identity: "
                        + project.id());
            }
            for (StoredRelease release : restoredDatabase.listReleases(project.id())) {
                try {
                    byte[] bytes = Files.readAllBytes(release.manifestPath());
                    if (!CryptoSupport.sha256(bytes).equals(release.manifestSha256())
                            || !CryptoSupport.verify(bytes, Base64.getDecoder().decode(release.signature()), publicKey)) {
                        throw new ManagementException("Backup contains an invalid signed release: " + release.releaseId());
                    }
                    ReleaseManifest manifest = json.read(bytes, ReleaseManifest.class);
                    ManifestValidator.validateRelease(manifest, Set.of());
                    if (!manifest.projectId().equals(project.id()) || manifest.sequence() != release.sequence()) {
                        throw new ManagementException("Backup release metadata does not match its signed manifest");
                    }
                    for (var file : manifest.files()) {
                        if (verifiedObjects.add(file.sha256())) {
                            Path object = restoredObjects.require(file.sha256());
                            restoredObjects.verify(object, file.sha256(), file.size());
                        }
                    }
                    if (manifest.branding().coverObject() != null
                            && verifiedObjects.add(manifest.branding().coverObject())) {
                        Path cover = restoredObjects.require(manifest.branding().coverObject());
                        String actual = CryptoSupport.sha256(cover);
                        if (!actual.equals(manifest.branding().coverObject())) {
                            throw new ManagementException("Backup contains a corrupt cover object");
                        }
                    }
                } catch (IOException | IllegalArgumentException e) {
                    throw new ManagementException("Unable to verify restored release " + release.releaseId(), e);
                }
            }
        }
        new PlayerProgramService(restoredPaths, restoredDatabase, json).verifyAllPublishedPrograms();
    }

    private static void validateZipEntry(String name, Set<String> names) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")
                || name.contains(":") || name.endsWith("/") || name.contains("//")) {
            throw new ManagementException("Unsafe backup entry path: " + name);
        }
        for (String segment : name.split("/")) {
            if (segment.equals(".") || segment.equals("..") || segment.isBlank()) {
                throw new ManagementException("Unsafe backup entry path: " + name);
            }
        }
        if (!names.add(name.toLowerCase(Locale.ROOT))) {
            throw new ManagementException("Duplicate backup entry path: " + name);
        }
    }

    private static boolean directoryHasEntries(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return Files.exists(directory);
        try (var stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        }
    }
}
