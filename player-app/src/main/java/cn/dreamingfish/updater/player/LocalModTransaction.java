package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.PathSafety;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Durable file moves and preference updates for local mod enable/disable. */
final class LocalModTransaction {
    static final String TRASH = "state/local-mod-transaction/trash/";
    static final String ARCHIVE_STAGING = "state/local-mod-transaction/archive-staging/";

    record Move(String source, boolean sourceInInstance, String destination,
                boolean destinationInInstance, String sha256, long size, boolean discard) { }

    record Journal(int schemaVersion, boolean committed, boolean preferencesExisted,
                   LocalModPreferences before, LocalModPreferences after, List<Move> moves) { }

    record Barrier(int schemaVersion, String id, String kind) { }

    @FunctionalInterface
    interface Validator { void validate(LocalModPreferences preferences) throws IOException; }

    interface Faults {
        Faults NONE = new Faults() { };
        default void afterMove(int index) throws IOException { }
        default void beforePreferences() throws IOException { }
        default void afterPreferences() throws IOException { }
    }

    private final Path instance;
    private final Path home;
    private final Path root;
    private final Path journal;
    private final Path preferences;
    private final Path barrier;
    private final JsonCodec json;
    private final Validator validator;
    private final Faults faults;

    LocalModTransaction(Path instance, Path home, JsonCodec json, Validator validator, Faults faults) {
        this.instance = instance;
        this.home = home;
        this.root = home.resolve("state/local-mod-transaction");
        this.journal = root.resolve("journal.json");
        this.preferences = home.resolve("state/local-mod-preferences.json");
        this.barrier = home.resolve("state/transactions/local-mods/journal.json");
        this.json = json;
        this.validator = validator;
        this.faults = faults;
    }

    boolean pending() { return Files.exists(journal, LinkOption.NOFOLLOW_LINKS); }

    Move move(Path source, boolean fromInstance, Path destination, boolean toInstance, boolean discard)
            throws IOException {
        PathSafety.assertSafePathTree(source);
        return new Move(relative(source, fromInstance), fromInstance, relative(destination, toInstance),
                toInstance, CryptoSupport.sha256(source), Files.size(source), discard);
    }

    Path trashPath() throws IOException {
        return PathSafety.resolveInside(home, TRASH + UUID.randomUUID() + ".jar");
    }

    void execute(LocalModPreferences before, LocalModPreferences after, List<Move> moves) throws IOException {
        recover();
        validator.validate(after);
        java.util.Set<Path> sources = new java.util.HashSet<>();
        java.util.Set<Path> destinations = new java.util.HashSet<>();
        for (Move move : moves) {
            validateMove(move);
            Path source = resolve(move.source(), move.sourceInInstance());
            Path destination = resolve(move.destination(), move.destinationInInstance());
            if (!sources.add(source) || !destinations.add(destination)
                    || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Conflicting local mod move plan");
            }
        }
        PathSafety.createSafeDirectories(root);
        forceDirectories(root, home);
        Journal record = new Journal(2, false, Files.exists(preferences, LinkOption.NOFOLLOW_LINKS),
                before, after, List.copyOf(moves));
        // Older engines reject this unsupported transaction before granting launch permission.
        write(barrier, json.writePretty(new Barrier(0, "local-mods", "local-mod-moves-v1")));
        forceDirectories(barrier.getParent(), home);
        write(journal, json.writePretty(record));
        try {
            for (int index = 0; index < moves.size(); index++) {
                Move move = moves.get(index);
                Path source = resolve(move.source(), move.sourceInInstance());
                Path destination = resolve(move.destination(), move.destinationInInstance());
                if (!matches(source, move)) throw new IOException("Local mod changed before its move: " + source);
                LocalModManager.moveVerified(source, destination);
                forceMoved(destination);
                forceDirectories(source.getParent(), move.sourceInInstance() ? instance : home);
                forceDirectories(destination.getParent(), move.destinationInInstance() ? instance : home);
                faults.afterMove(index);
            }
            faults.beforePreferences();
            write(preferences, json.writePretty(after));
            faults.afterPreferences();
            write(journal, json.writePretty(new Journal(record.schemaVersion(), true, record.preferencesExisted(),
                    before, after, record.moves())));
        } catch (IOException | RuntimeException failure) {
            try { recover(); }
            catch (IOException | RuntimeException recovery) {
                failure.addSuppressed(recovery);
                throw new IOException("Local mod transaction requires recovery; files and journal retained at " + root, failure);
            }
            throw failure;
        }
        recover();
    }

    void recover() throws IOException {
        PathSafety.assertSafePathTree(root);
        if (!pending()) { clearBarrier(); return; }
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe local mod journal");
        Journal record = json.read(journal, Journal.class);
        if ((record.schemaVersion() != 1 && record.schemaVersion() != 2) || record.before() == null || record.after() == null
                || record.moves() == null || record.moves().size() > 10_000) {
            throw new IOException("Invalid local mod transaction journal");
        }
        validator.validate(record.before());
        validator.validate(record.after());
        for (Move move : record.moves()) validateMove(move);
        if (!record.committed()) {
            List<Move> reverse = new ArrayList<>(record.moves());
            Collections.reverse(reverse);
            for (Move move : reverse) {
                Path source = resolve(move.source(), move.sourceInInstance());
                Path destination = resolve(move.destination(), move.destinationInInstance());
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (!matches(destination, move)) {
                        if (matches(source, move)) continue; // Preserve a separately-created local file.
                        throw new IOException("Recovery copy changed: " + destination);
                    }
                    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                        LocalModManager.moveVerified(destination, source);
                        forceMoved(source);
                    } else {
                        if (!matches(source, move)) throw new IOException("Original local mod changed during recovery: " + source);
                        Files.delete(destination);
                    }
                    forceDirectories(source.getParent(), move.sourceInInstance() ? instance : home);
                    forceDirectories(destination.getParent(), move.destinationInInstance() ? instance : home);
                } else if (!matches(source, move)) {
                    throw new IOException("Local mod transaction has no intact original copy: " + source);
                }
            }
            if (record.preferencesExisted()) write(preferences, json.writePretty(record.before()));
            else { Files.deleteIfExists(preferences); forceDirectory(preferences.getParent()); }
        } else {
            for (Move move : record.moves()) {
                if (!move.discard()) continue;
                Path discarded = resolve(move.destination(), move.destinationInInstance());
                if (Files.exists(discarded, LinkOption.NOFOLLOW_LINKS)) {
                    if (!matches(discarded, move)) throw new IOException("Retained cleanup copy changed: " + discarded);
                    Files.delete(discarded);
                }
            }
        }
        Files.delete(journal);
        forceDirectory(root);
        clearBarrier();
        // Leftover empty directories are harmless; never recursively remove an unindexed copy.
    }

    private void clearBarrier() throws IOException {
        PathSafety.assertSafePathTree(barrier);
        if (!Files.exists(barrier, LinkOption.NOFOLLOW_LINKS)) return;
        Barrier record = json.read(barrier, Barrier.class);
        if (record.schemaVersion() != 0 || !"local-mods".equals(record.id())
                || !"local-mod-moves-v1".equals(record.kind())) {
            throw new IOException("Unknown local mod recovery barrier; retained at " + barrier);
        }
        Files.delete(barrier);
        forceDirectory(barrier.getParent());
        try (var entries = Files.list(barrier.getParent())) {
            if (entries.findAny().isPresent()) return;
        }
        Files.delete(barrier.getParent());
        forceDirectory(barrier.getParent().getParent());
    }

    private void validateMove(Move move) throws IOException {
        if (move == null || move.size() < 0 || move.sha256() == null
                || !move.sha256().matches("[0-9a-f]{64}")) throw new IOException("Invalid local mod move");
        validateLocation(move.source(), move.sourceInInstance(), false);
        validateLocation(move.destination(), move.destinationInInstance(), move.discard());
        if (move.discard() && move.destinationInInstance()) throw new IOException("Invalid local mod cleanup destination");
        if (resolve(move.source(), move.sourceInInstance()).equals(resolve(move.destination(), move.destinationInInstance()))) {
            throw new IOException("Local mod move has identical locations");
        }
    }

    private static void validateLocation(String relative, boolean inInstance, boolean trash) throws IOException {
        String normalized = PathSafety.normalizeManifestPath(relative).toLowerCase(java.util.Locale.ROOT);
        if (!inInstance && !trash) {
            boolean indexStaging = normalized.startsWith(ARCHIVE_STAGING) && normalized.endsWith(".json");
            boolean archive = normalized.startsWith("backups/archive/")
                    && (normalized.endsWith(".jar") || normalized.endsWith("/archive-index.json"));
            if (indexStaging || archive) return;
        }
        String prefix = inInstance ? "mods/" : trash ? TRASH : "local-mods/disabled/";
        if (!normalized.startsWith(prefix) || !normalized.endsWith(".jar")) throw new IOException("Invalid local mod location");
    }

    private Path resolve(String relative, boolean inInstance) throws IOException {
        return PathSafety.resolveInside(inInstance ? instance : home, relative);
    }

    private String relative(Path path, boolean inInstance) throws IOException {
        Path base = inInstance ? instance : home;
        if (!path.toAbsolutePath().normalize().startsWith(base)) throw new IOException("Local mod move escapes its root");
        return base.relativize(path).toString().replace('\\', '/');
    }

    private static boolean matches(Path path, Move move) throws IOException {
        PathSafety.assertSafePathTree(path);
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) == move.size() && CryptoSupport.sha256(path).equals(move.sha256());
    }

    static void write(Path path, byte[] bytes) throws IOException {
        PathSafety.createSafeDirectories(path.getParent());
        PathSafety.assertSafePathTree(path);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            force(temporary);
            LocalModManager.moveReplace(temporary, path);
            forceDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void forceMoved(Path path) throws IOException {
        // A rename does not rewrite content and must preserve read-only attributes.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static void forceDirectory(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { }
    }

    static void forceDirectories(Path directory, Path base) {
        for (Path current = directory; current != null && current.startsWith(base); current = current.getParent()) {
            forceDirectory(current);
        }
        if (base.getParent() != null) forceDirectory(base.getParent());
    }
}
