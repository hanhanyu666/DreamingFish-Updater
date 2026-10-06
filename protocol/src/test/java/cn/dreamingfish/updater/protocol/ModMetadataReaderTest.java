package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModMetadataReaderTest {
    @TempDir
    Path temporary;

    @Test
    void readsForgeAndFabricMetadata() throws Exception {
        Path forge = jar("forge.jar", "META-INF/mods.toml", """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="render_opt"
                displayName="渲染优化"
                version="1.0"
                """);
        Path fabric = jar("fabric.jar", "fabric.mod.json", """
                {"schemaVersion":1,"id":"world_map","name":"World Map","version":"1.0"}
                """);

        assertEquals(new ModMetadata("render_opt", "渲染优化", "1.0"),
                ModMetadataReader.read(forge).orElseThrow());
        assertEquals(new ModMetadata("world_map", "World Map", "1.0"),
                ModMetadataReader.read(fabric).orElseThrow());
    }

    @Test
    void resolvesTheForgeJarVersionPlaceholderFromTheJarManifest() throws Exception {
        Path path = temporary.resolve("placeholder.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\r\nImplementation-Version: 15.3.0.4\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            output.write("""
                    [[mods]]
                    modId="jei"
                    displayName="Just Enough Items"
                    version="${file.jarVersion}"
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertEquals(new ModMetadata("jei", "Just Enough Items", "15.3.0.4"),
                ModMetadataReader.read(path).orElseThrow());

        Path unresolved = jar("unresolved.jar", "META-INF/mods.toml", """
                [[mods]]
                modId="unresolved"
                version="${file.jarVersion}"
                """);
        assertEquals(null, ModMetadataReader.read(unresolved).orElseThrow().version());
    }

    @Test
    void ignoresInvalidOrMissingMetadata() throws Exception {
        Path text = temporary.resolve("not-a-jar.jar");
        Files.writeString(text, "not a zip");
        Path invalid = jar("invalid.jar", "fabric.mod.json", "{broken");

        assertTrue(ModMetadataReader.read(text).isEmpty());
        assertTrue(ModMetadataReader.read(invalid).isEmpty());
    }

    @Test
    void usesStablePrecedenceWhenAForgeJarContainsBothMetadataFiles()
            throws Exception {
        Path path = temporary.resolve("both-forge-metadata.jar");
        String legacy = """
                [[mods]]
                modId="stable_mod"
                displayName="Legacy name"
                """;
        String neoForge = """
                [[mods]]
                modId="stable_mod"
                displayName="NeoForge name"
                """;
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            output.write(legacy.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            output.write(neoForge.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertEquals(new ModMetadata("stable_mod", "NeoForge name"),
                ModMetadataReader.read(path).orElseThrow());
        assertEquals(ModMetadataReader.read(path), ModMetadataReader.read(path));
    }

    private Path jar(String name, String entryName, String content) throws Exception {
        Path path = temporary.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }
}
