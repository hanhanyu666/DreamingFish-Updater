package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalPublicAddressTest {
    @TempDir
    Path temporary;

    @Test
    void projectsStartWithoutAnAddressButPublishingAndDeliveryWaitForIt() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        ProjectRecord created = fixture.projects.create("demo", "Demo Pack", fixture.source, "  ",
                new Branding("Demo", "", "", null, "#2ee8df", "#b06cff"), ProjectRules.defaults());
        assertEquals("", created.publicBaseUrl());
        Files.writeString(Files.createDirectories(fixture.source.resolve("mods")).resolve("a.jar"), "a");
        fixture.scanner.createPreview("demo");

        ManagementException publish = assertThrows(ManagementException.class,
                () -> fixture.publisher.publish("demo", "1.0", "0.1.13", "First"));
        ManagementException binding = assertThrows(ManagementException.class,
                () -> fixture.projects.bindingFor(fixture.database.requireProject("demo"), "home", null));
        assertTrue(publish.getMessage().contains("玩家更新地址"), publish.getMessage());
        assertTrue(binding.getMessage().contains("玩家更新地址"), binding.getMessage());
        assertTrue(fixture.database.listReleases("demo").isEmpty());

        ProjectRecord configured = fixture.projects.configure(
                "demo", null, "http://play.example.test:8080/", null, null);
        assertEquals("http://play.example.test:8080", configured.publicBaseUrl());
        fixture.scanner.createPreview("demo");
        fixture.publisher.publish("demo", "1.0", "0.1.13", "First");
        assertEquals(1, fixture.database.listReleases("demo").size());
        assertEquals("http://play.example.test:8080",
                fixture.projects.bindingFor(configured, "home", null).baseUrl());
    }

    @Test
    void keepingTheAddressEmptyOnOtherEditsLeavesItUnset() throws Exception {
        ManagementFixture fixture = new ManagementFixture(temporary.resolve("management"));
        fixture.projects.create("demo", "Demo Pack", fixture.source, null, null, ProjectRules.defaults());

        ProjectRecord renamed = fixture.projects.configure("demo", "Renamed", null, null, null, null);

        assertEquals("", renamed.publicBaseUrl());
        assertEquals("Renamed", renamed.displayName());
        assertThrows(ManagementException.class,
                () -> fixture.projects.configure("demo", null, "ftp://play.example.test", null, null));
    }
}
