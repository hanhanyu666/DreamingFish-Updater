package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerHomeRelocatorTest {
    @TempDir
    Path temporary;

    @Test
    void copiesVerifiesAndAtomicallyRebindsThePlayerHome() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path bootstrap = Files.createDirectories(instance.resolve(".dreamingfish-bootstrap"));
        Path source = Files.createDirectories(instance.resolve("DreamingFishUpdater/app/0.1.0"))
                .getParent().getParent();
        Files.writeString(source.resolve("app/0.1.0/player-app.jar"), "application");
        Files.createDirectories(source.resolve("state"));
        Files.writeString(source.resolve("state/active-player.properties"),
                "schema=1\nlauncher=app/0.1.0/player.exe\n");
        ProjectBinding binding = new ProjectBinding(1, "demo", "http://127.0.0.1:8080",
                CryptoSupport.encodePublicKey(CryptoSupport.generateEd25519KeyPair().getPublic()),
                "DreamingFishUpdater", null, Branding.empty());
        Path bindingFile = bootstrap.resolve("project-binding.json");
        new JsonCodec().write(bindingFile, binding);
        Path target = temporary.resolve("relocated-player");

        ProjectBinding relocated = new PlayerHomeRelocator().relocate(
                source, target, instance, bindingFile, binding);

        assertEquals("application", Files.readString(target.resolve("app/0.1.0/player-app.jar")));
        assertEquals(target.toAbsolutePath().normalize().toString(), relocated.playerHome());
        assertEquals(relocated, new JsonCodec().read(bindingFile, ProjectBinding.class));
        assertEquals("1\n", Files.readString(target.resolve("state/first-run-complete")));
    }

    @Test
    void rejectsTargetsInsideTheCurrentHomeOrBootstrapDirectory() throws Exception {
        Path instance = Files.createDirectories(temporary.resolve("blocked-instance"));
        Path source = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        Path bindingFile = Files.createDirectories(instance.resolve(".dreamingfish-bootstrap"))
                .resolve("project-binding.json");
        ProjectBinding binding = new ProjectBinding(1, "demo", "http://127.0.0.1:8080",
                CryptoSupport.encodePublicKey(CryptoSupport.generateEd25519KeyPair().getPublic()),
                "DreamingFishUpdater", null, Branding.empty());

        PlayerHomeRelocator relocator = new PlayerHomeRelocator();
        assertThrows(Exception.class, () -> relocator.relocate(source, source.resolve("nested"),
                instance, bindingFile, binding));
        assertThrows(Exception.class, () -> relocator.relocate(source,
                instance.resolve(".dreamingfish-bootstrap/player"), instance, bindingFile, binding));
    }
}
