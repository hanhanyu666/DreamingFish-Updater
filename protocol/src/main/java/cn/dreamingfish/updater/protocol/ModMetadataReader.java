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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModMetadataReader {
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final Set<String> FORGE_METADATA = Set.of(
            "META-INF/mods.toml", "META-INF/neoforge.mods.toml");

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
        return metadata(text(first.get("modId")), text(first.get("displayName")));
    }

    @SuppressWarnings("unchecked")
    private static Optional<ModMetadata> readFabric(ZipFile zip, ZipEntry entry) throws IOException {
        Map<String, Object> values = new JsonCodec().read(readLimited(zip, entry), Map.class);
        return metadata(text(values.get("id")), text(values.get("name")));
    }

    private static Optional<ModMetadata> metadata(String id, String name) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,128}")) return Optional.empty();
        String display = name == null || name.isBlank() ? id : name.strip();
        if (display.length() > 256 || display.chars().anyMatch(Character::isISOControl)) {
            display = id;
        }
        return Optional.of(new ModMetadata(id, display));
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
