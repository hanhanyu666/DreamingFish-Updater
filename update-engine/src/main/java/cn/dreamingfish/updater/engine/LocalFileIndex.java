package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ModMetadata;
import cn.dreamingfish.updater.protocol.ModMetadataReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cache of content hashes and mod metadata keyed by instance-relative path.
 * A file is hashed again whenever its size or modification time changes, in
 * the same way version control tools avoid re-reading unchanged files. The
 * cache is an optimization only: losing or corrupting it just costs time.
 */
public final class LocalFileIndex {
    private static final int SCHEMA_VERSION = 1;

    public record Entry(long size, long modifiedMillis, String sha256, boolean metadataRead,
                        String componentId, String displayName, String version) {
    }

    record Stored(int schemaVersion, Map<String, Entry> files) {
    }

    /** Inspection result for one local file. */
    public record Inspection(String sha256, long size, String componentId, String displayName,
                             String version) {
    }

    private final Path storage;
    private final JsonCodec json = new JsonCodec();
    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<String> touched = new HashSet<>();
    private boolean dirty;

    private LocalFileIndex(Path storage) {
        this.storage = storage;
    }

    public static LocalFileIndex load(Path storage) {
        LocalFileIndex index = new LocalFileIndex(storage);
        if (storage == null) return index;
        try {
            if (Files.isRegularFile(storage, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(storage)) {
                Stored stored = index.json.read(storage, Stored.class);
                if (stored.schemaVersion() == SCHEMA_VERSION && stored.files() != null) {
                    index.entries.putAll(stored.files());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A damaged cache only means files are hashed again.
            index.entries.clear();
        }
        return index;
    }

    /** An index that never persists, for one-off inspections. */
    public static LocalFileIndex transientIndex() {
        return new LocalFileIndex(null);
    }

    public synchronized Inspection inspect(Path file, String relativePath, boolean needMetadata)
            throws IOException {
        String key = ManagedPaths.fold(relativePath);
        BasicFileAttributes before = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Not a regular file: " + relativePath);
        long size = before.size();
        long modified = before.lastModifiedTime().toMillis();
        touched.add(key);
        Entry cached = entries.get(key);
        if (cached != null && cached.size() == size && cached.modifiedMillis() == modified
                && cached.sha256() != null && (!needMetadata || cached.metadataRead())) {
            return inspection(cached);
        }
        String hash = cached != null && cached.size() == size && cached.modifiedMillis() == modified
                && cached.sha256() != null ? cached.sha256() : CryptoSupport.sha256(file);
        ModMetadata metadata = null;
        boolean metadataRead = false;
        if (needMetadata || (cached != null && cached.metadataRead())) {
            metadata = ModMetadataReader.read(file).orElse(null);
            metadataRead = true;
        }
        BasicFileAttributes after = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Entry entry = new Entry(size, modified, hash, metadataRead,
                metadata == null ? null : metadata.componentId(),
                metadata == null ? null : metadata.displayName(),
                metadata == null ? null : metadata.version());
        if (after.size() == size && after.lastModifiedTime().toMillis() == modified) {
            entries.put(key, entry);
            dirty = true;
        } else {
            entries.remove(key);
        }
        return inspection(entry);
    }

    public String sha256(Path file, String relativePath) throws IOException {
        return inspect(file, relativePath, false).sha256();
    }

    /** True when the file exists with exactly the given size and content. */
    public boolean matches(Path file, String relativePath, String sha256, long size)
            throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.size(file) != size) return false;
        return inspect(file, relativePath, false).sha256().equals(sha256);
    }

    /**
     * Persists entries seen during this run; entries for vanished files are dropped.
     * Only complete update runs save, so partial inspections never prune the cache.
     */
    public synchronized void save() {
        if (storage == null || touched.isEmpty()) return;
        if (!dirty && touched.containsAll(entries.keySet())) return;
        Map<String, Entry> retained = new TreeMap<>();
        for (String key : touched) {
            Entry entry = entries.get(key);
            if (entry != null) retained.put(key, entry);
        }
        try {
            AtomicFileSupport.write(storage, json.write(new Stored(SCHEMA_VERSION, retained)));
            dirty = false;
        } catch (IOException | RuntimeException ignored) {
            // The cache is optional; the next run hashes again.
        }
    }

    private static Inspection inspection(Entry entry) {
        return new Inspection(entry.sha256(), entry.size(), entry.componentId(),
                entry.displayName(), entry.version());
    }
}
