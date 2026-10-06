package cn.dreamingfish.updater.protocol;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModMetadataReader {
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    /**
     * Keep the metadata precedence stable across JVM processes.  A Set has no
     * iteration-order contract, and jars containing both files could therefore
     * produce a different display name during the publish preview and its
     * final verification scan.
     */
    private static final List<String> FORGE_METADATA = List.of(
            "META-INF/neoforge.mods.toml", "META-INF/mods.toml");

    private ModMetadataReader() {
    }

    public static Optional<ModMetadata> read(Path jar) {
        if (jar == null || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(jar)
                || !jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            for (String name : FORGE_METADATA) {
                ZipEntry entry = zip.getEntry(name);
                if (entry != null) {
                    Optional<ModMetadata> metadata = readForge(zip, entry);
                    if (metadata.isPresent()) return metadata;
                }
            }
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            return fabric == null ? Optional.empty() : readFabric(zip, fabric);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<ModMetadata> readForge(ZipFile zip, ZipEntry entry) throws IOException {
        byte[] bytes = readLimited(zip, entry);
        TomlParseResult result = Toml.parse(new String(bytes, StandardCharsets.UTF_8));
        if (result.hasErrors()) return Optional.empty();
        TomlArray mods = result.getArray("mods");
        if (mods == null || mods.size() == 0) return Optional.empty();
        TomlTable first = mods.getTable(0);
        String version = text(first.get("version"));
        if (version != null && version.contains("${")) {
            // Forge build scripts commonly substitute ${file.jarVersion} from the jar manifest.
            version = manifestVersion(zip);
        }
        return metadata(text(first.get("modId")), text(first.get("displayName")), version);
    }

    @SuppressWarnings("unchecked")
    private static Optional<ModMetadata> readFabric(ZipFile zip, ZipEntry entry) throws IOException {
        Map<String, Object> values = new JsonCodec().read(readLimited(zip, entry), Map.class);
        return metadata(text(values.get("id")), text(values.get("name")), text(values.get("version")));
    }

    private static String manifestVersion(ZipFile zip) throws IOException {
        ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
        if (manifest == null) return null;
        String text = new String(readLimited(zip, manifest), StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            if (line.regionMatches(true, 0, "Implementation-Version:", 0, 23)) {
                return line.substring(23).strip();
            }
        }
        return null;
    }

    private static Optional<ModMetadata> metadata(String id, String name, String version) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,128}")) return Optional.empty();
        String display = name == null || name.isBlank() ? id : name.strip();
        if (display.length() > 256 || display.chars().anyMatch(Character::isISOControl)) {
            display = id;
        }
        String safeVersion = version == null || version.isBlank() || version.length() > 128
                || version.contains("${")
                || version.chars().anyMatch(Character::isISOControl) ? null : version.strip();
        return Optional.of(new ModMetadata(id, display, safeVersion));
    }

    private static String text(Object value) {
        return value instanceof String string ? string.strip() : null;
    }

    private static byte[] readLimited(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.isDirectory() || entry.getSize() > MAX_METADATA_BYTES) {
            throw new IOException("Mod metadata is too large");
        }
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_METADATA_BYTES) throw new IOException("Mod metadata is too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
