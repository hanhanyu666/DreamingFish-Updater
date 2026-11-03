package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerUpdatePolicyServiceTest {
    @TempDir Path temporary;

    @Test void preparationApprovalPauseAndResumeAreIndependentOfContent() throws Exception {
        var fixture = new ManagementFixture(temporary); fixture.createProject();
        Files.writeString(fixture.source.resolve("guide.txt"), "content");
        fixture.scanner.createPreview("demo"); fixture.publisher.publish("demo", "1.0", "0.2.0", "first");
        var policies = new PlayerUpdatePolicyService(fixture.paths, fixture.database, fixture.json);
        var programs = new PlayerProgramService(fixture.paths, fixture.database, fixture.json);
        assertFalse(policies.read("demo").enabled());
        Path source = Files.createDirectories(temporary.resolve("program")); Files.writeString(source.resolve("player.exe"), "program");
        var candidate = programs.prepare("demo", "windows-x64", "0.3.3", source, "player.exe", "0.1.2");
        assertTrue(programs.latest("demo", "windows-x64").isEmpty());
        var approved = policies.approve("demo", "windows-x64", candidate.version(), candidate.manifestSha256(), 0);
        assertTrue(approved.enabled()); assertEquals(1, approved.revision());
        assertThrows(ManagementException.class, () -> policies.setEnabled("demo", false, 0));
        try (var server = new PublicFileServer(fixture.database, fixture.objects, new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.address().getPort() + "/v1/projects/demo/";
            assertEquals(200, get(client, base + "player/windows-x64/latest"));
            policies.setEnabled("demo", false, 1);
            assertEquals(404, get(client, base + "player/windows-x64/latest"));
            assertEquals(404, get(client, base + "player/windows-x64/latest.sig"));
            assertEquals(200, get(client, base + "latest"));
            assertEquals(200, get(client, base + "player/policy.signed"));
            Path output = temporary.resolve("export");
            var exporter = new StaticDistributionService(fixture.paths, fixture.database, fixture.json);
            exporter.exportProject("demo", output);
            assertFalse(Files.exists(output.resolve("v1/projects/demo/player/windows-x64/latest")));
            var retired = fixture.json.read(output.resolve(".dreamingfish-player-retirements.json"), StaticDistributionService.PlayerRetirements.class);
            assertEquals(3, retired.paths().size());
            assertTrue(Files.exists(output.resolve("v1/projects/demo/player/windows-x64/versions/0.3.3/manifest")));
            policies.setEnabled("demo", true, 2);
            assertEquals(200, get(client, base + "player/windows-x64/latest"));
            exporter.exportProject("demo", output);
            assertTrue(Files.exists(output.resolve("v1/projects/demo/player/windows-x64/latest.signed")));
        }
    }

    @Test void legacyApprovalIsPreservedAndPolicyCorruptionIsNotReset() throws Exception {
        var fixture = new ManagementFixture(temporary); fixture.createProject();
        Path source = Files.createDirectories(temporary.resolve("program")); Files.writeString(source.resolve("player.exe"), "program");
        var programs = new PlayerProgramService(fixture.paths, fixture.database, fixture.json);
        programs.prepare("demo", "windows-x64", "0.3.2", source, "player.exe", "0.1.2");
        Files.writeString(fixture.paths.playerProgramLatest("demo", "windows-x64"), "0.3.2\n");
        var policies = new PlayerUpdatePolicyService(fixture.paths, fixture.database, fixture.json);
        assertTrue(policies.read("demo").enabled());
        assertEquals("0.3.2", policies.read("demo").targets().get("windows-x64").version());
        policies.setEnabled("demo", false, 0);
        Path policyFile = fixture.paths.playerPrograms().resolve("demo/update-policy.signed");
        Files.writeString(policyFile, "broken");
        assertThrows(ManagementException.class, () -> policies.read("demo"));
        assertEquals("broken", Files.readString(policyFile));
    }
    private static int get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
