package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDeploymentServiceTest {
    @TempDir
    Path temporary;

    @Test
    void createsAThinFirstDeploymentWithoutACompleteMinecraftInstance() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.createProject();
        Path mod = fixture.source.resolve("mods/example.jar");
        Files.createDirectories(mod.getParent());
        Files.writeString(mod, "managed-mod");
        fixture.scanner.createPreview("demo");
        StoredRelease release = fixture.publisher.publish(
                "demo", "1.0", "0.1.13", "First");

        Path program = Files.createDirectories(temporary.resolve("program"));
        Files.writeString(program.resolve("DreamingFishUpdater.exe"), "launcher");
        Files.createDirectories(program.resolve("app"));
        Files.writeString(program.resolve("app/player-app.jar"), "application");
        Files.createDirectories(program.resolve("runtime/bin"));
        Files.writeString(program.resolve("runtime/bin/java.dll"), "runtime");
        new PlayerProgramService(fixture.paths, fixture.database, fixture.json)
                .publish("demo", "windows-x64", "0.2.0", program,
                        "DreamingFishUpdater.exe", "0.1.2");

        Path agent = temporary.resolve("bootstrap-agent.jar");
        writeAgent(agent);
        Path outputParent = Files.createDirectories(temporary.resolve("output"));
        PlayerDeploymentService.PreparedDeployment result =
                new PlayerDeploymentService(
                        fixture.paths, fixture.database, fixture.json)
                        .create("demo", "windows-x64", release.releaseId(),
                                outputParent, agent);

        Path output = result.outputDirectory();
        assertTrue(Files.isRegularFile(output.resolve(
                ".dreamingfish-bootstrap/bootstrap-agent.jar")));
        assertTrue(Files.isRegularFile(output.resolve(
                ".dreamingfish-bootstrap/project-binding.json")));
        assertTrue(Files.isRegularFile(output.resolve(
                ".dreamingfish-bootstrap/bundled-release/manifest.json")));
        assertTrue(Files.isRegularFile(output.resolve(
                "DreamingFishUpdater/app/0.2.0/DreamingFishUpdater.exe")));
        assertTrue(Files.readString(output.resolve(
                "DreamingFishUpdater/state/active-player.properties"))
                .matches("(?s).*manifestSha256=[0-9a-f]{64}.*"));
        assertTrue(Files.readString(output.resolve("README.txt"))
                .contains("不包含 Minecraft 本体"));
        assertFalse(Files.exists(output.resolve("mods")),
                "The thin deployment must not create managed modpack directories");
    }

    private static void writeAgent(Path output) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Premain-Class",
                "cn.dreamingfish.updater.bootstrap.BootstrapAgent");
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(output), manifest)) {
            // The deployment service validates the signed entry point contract.
        }
    }
}
