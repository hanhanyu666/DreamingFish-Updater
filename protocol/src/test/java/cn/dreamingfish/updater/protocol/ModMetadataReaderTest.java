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

        assertEquals(new ModMetadata("render_opt", "渲染优化"),
                ModMetadataReader.read(forge).orElseThrow());
        assertEquals(new ModMetadata("world_map", "World Map"),
                ModMetadataReader.read(fabric).orElseThrow());
    }

    @Test
    void ignoresInvalidOrMissingMetadata() throws Exception {
        Path text = temporary.resolve("not-a-jar.jar");
        Files.writeString(text, "not a zip");
        Path invalid = jar("invalid.jar", "fabric.mod.json", "{broken");

        assertTrue(ModMetadataReader.read(text).isEmpty());
        assertTrue(ModMetadataReader.read(invalid).isEmpty());
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
