package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficialDownloadProxyTest {
    @TempDir Path temporary;

    private static Proxy http(String host, int port) { return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)); }
    private static Proxy route(OfficialDownloadProxy proxy, String uri) { return proxy.select(URI.create(uri)).getFirst(); }

    @Test void httpsProxyComesFirstThenTheWindowsSettingAndLocalOrExemptHostsStayDirect() {
        var environment = new OfficialDownloadProxy(Map.of("HTTPS_PROXY", "http://127.0.0.1:7897", "NO_PROXY", ".corp.example,localhost"),
                () -> { throw new AssertionError("the system setting is not read when HTTPS_PROXY is set"); });
        assertEquals(http("127.0.0.1", 7897), route(environment, "https://api.github.com/repos/o/r/releases/latest"));
        assertEquals(http("127.0.0.1", 7897), route(environment, "https://release-assets.githubusercontent.com/x"));
        assertEquals(Proxy.NO_PROXY, route(environment, "https://mirror.corp.example/x"));
        assertEquals(Proxy.NO_PROXY, route(environment, "http://127.0.0.1:8080/x"), "loopback never goes through a proxy");

        assertEquals(http("127.0.0.2", 8080), route(new OfficialDownloadProxy(Map.of("https_proxy", "127.0.0.2:8080"), () -> null),
                "https://github.com/x"), "lower-case names and a bare host:port work as in curl");
        assertEquals(http("10.0.0.2", 8443), route(new OfficialDownloadProxy(Map.of(),
                () -> "http=10.0.0.1:80;https=10.0.0.2:8443;socks=10.0.0.3:1080"), "https://github.com/x"));
        assertEquals(http("10.0.0.5", 7890), route(new OfficialDownloadProxy(Map.of(), () -> "10.0.0.5:7890"), "https://github.com/x"));

        assertEquals(http("10.0.0.5", 7890), route(new OfficialDownloadProxy(Map.of("HTTPS_PROXY", "http://user:secret@10.0.0.4:8080"),
                () -> "10.0.0.5:7890"), "https://github.com/x"), "a proxy needing credentials is skipped, not half-used");
        assertEquals(Proxy.NO_PROXY, route(new OfficialDownloadProxy(Map.of("HTTPS_PROXY", "socks5://127.0.0.1:1080"),
                () -> "socks=10.0.0.3:1080"), "https://github.com/x"), "the JDK client cannot tunnel through SOCKS");
        assertEquals(Proxy.NO_PROXY, route(new OfficialDownloadProxy(Map.of("HTTPS_PROXY", "http://10.0.0.6:8080", "NO_PROXY", "*"),
                () -> null), "https://github.com/x"));
        assertEquals(Proxy.NO_PROXY, route(new OfficialDownloadProxy(Map.of(), () -> null), "https://github.com/x"));
    }

    @Test void theOfficialCatalogueAndProgramComeThroughTheProxy() throws Exception {
        var fixture = new ManagementFixture(temporary.resolve("proxy-management")); fixture.createProject();
        var keys = CryptoSupport.generateEd25519KeyPair();
        byte[] program = "official-program".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive = temporary.resolve("proxy-player.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/app/0.3.3/DreamingFishUpdater.exe")); zip.write(program); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("DreamingFishUpdater/state/active-player.properties")); zip.write("schema=1\nversion=0.3.3\n".getBytes()); zip.closeEntry();
        }
        byte[] zipBytes = Files.readAllBytes(archive);
        var release = new OfficialPlayerCatalog.Release("0.3.3", "windows-x64", "https://github.invalid/player.zip", CryptoSupport.sha256(zipBytes),
                zipBytes.length, "DreamingFishUpdater.exe", "0.1.2", "0.3.1", "proxy", Set.of(),
                List.of(new PlayerProgramFile("DreamingFishUpdater.exe", CryptoSupport.sha256(program), program.length, false)));
        byte[] payload = fixture.json.write(new OfficialPlayerCatalog(1, Instant.now(), List.of(release)));
        byte[] catalog = new SignedDocument(payload, Base64.getEncoder().encodeToString(CryptoSupport.sign(payload, keys.getPrivate()))).encode();
        var forwarded = Collections.synchronizedList(new ArrayList<String>());
        // Stands in for a local proxy: the unresolvable host can only be reached through it. It answers the
        // catalogue request and refuses the HTTPS tunnel, which is enough to show the download asks for one.
        try (var proxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (!proxy.isClosed()) {
                    try (Socket socket = proxy.accept()) {
                        var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                        String request = in.readLine(); forwarded.add(request);
                        for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) { }
                        var out = socket.getOutputStream();
                        if (request != null && request.startsWith("GET http://github.invalid/catalog ")) {
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + catalog.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            out.write(catalog);
                        } else out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch (java.io.IOException closed) { /* the test is over */ }
                }
            });
            var service = new OfficialPlayerService(fixture.paths, fixture.database, fixture.json, URI.create("http://github.invalid/catalog"),
                    keys.getPublic(), null, new OfficialDownloadProxy(Map.of("HTTPS_PROXY", "http://127.0.0.1:" + proxy.getLocalPort()), () -> null));
            service.retryPause = java.time.Duration.ofMillis(10); service.patience = java.time.Duration.ofMillis(300);
            assertEquals("0.3.3", service.check(true).releases().getFirst().version());
            var refused = assertThrows(ManagementException.class,
                    () -> service.prepare("demo", "windows-x64", "0.3.3", release.archiveSha256(), "0.3.1"));
            assertTrue(refused.getMessage().contains("没有任何进展"), refused.getMessage());
            assertEquals("GET http://github.invalid/catalog HTTP/1.1", forwarded.getFirst());
            assertTrue(forwarded.stream().anyMatch(line -> line.startsWith("CONNECT github.invalid:443 ")), forwarded.toString());
        }
    }
}
