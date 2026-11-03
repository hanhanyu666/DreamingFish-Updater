package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Opens GitHub release assets through api.github.com before github.com: some management hosts, notably in
 * mainland China, cannot reach github.com but can reach the API and the asset storage it redirects to. Callers
 * verify every byte against the official signature or signed hashes, so the route taken never affects trust.
 */
final class GitHubReleaseAssets {
    private static final Pattern DOWNLOAD = Pattern.compile(
            "/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/releases/(?:latest/download|download/([^/]+))/([^/]+)");
    private static final String AGENT = "DreamingFish-Updater";
    private final HttpClient client;
    private final JsonCodec json;
    private final URI api;
    private final String webHost;

    GitHubReleaseAssets(HttpClient client, JsonCodec json) {
        this(client, json, URI.create("https://api.github.com/"), "github.com");
    }

    GitHubReleaseAssets(HttpClient client, JsonCodec json, URI api, String webHost) {
        this.client = client; this.json = json; this.api = api; this.webHost = webHost;
    }

    /**
     * The asset's response, or {@code null} when GitHub answered that the release has no such asset. An
     * unreachable or rate-limited API falls back to the original address, which other hosts may reach directly.
     */
    TimedHttpResponse open(URI url, Duration bodyTimeout) throws IOException, InterruptedException {
        return open(url, bodyTimeout, null, 0);
    }

    /**
     * As {@link #open(URI, Duration)}, resuming at byte {@code from}: the caller checks whether the answer is the
     * requested 206 range or a full 200 body. Each call asks the API again, as its storage links expire quickly.
     */
    TimedHttpResponse open(URI url, Duration bodyTimeout, Duration idle, long from) throws IOException, InterruptedException {
        var matcher = DOWNLOAD.matcher(url.getRawPath() == null ? "" : url.getRawPath());
        if (webHost.equalsIgnoreCase(url.getHost()) && matcher.matches()) {
            try {
                URI asset = apiAsset(matcher.group(1), matcher.group(2), matcher.group(3),
                        URLDecoder.decode(matcher.group(4), StandardCharsets.UTF_8));
                if (asset == null) return null;
                var response = TimedHttpResponse.send(client, request(asset, "application/octet-stream", from), bodyTimeout, idle);
                if (response.statusCode() == 200 || from > 0 && response.statusCode() == 206) return response;
                response.close();
            } catch (IOException | RuntimeException unavailable) {
                // The API is unreachable or limited from here; github.com may still answer.
            }
        }
        return TimedHttpResponse.send(client, request(url, "*/*", from), bodyTimeout, idle);
    }

    /** The API download address, or {@code null} when the release or the named asset does not exist. */
    private URI apiAsset(String owner, String repository, String tag, String name)
            throws IOException, InterruptedException {
        URI release = api.resolve("repos/" + owner + "/" + repository + "/releases/"
                + (tag == null ? "latest" : "tags/" + tag));
        try (var response = TimedHttpResponse.send(client, request(release, "application/vnd.github+json", 0),
                Duration.ofSeconds(30))) {
            if (response.statusCode() == 404) return null;
            if (response.statusCode() != 200) throw new IOException("GitHub API HTTP " + response.statusCode());
            byte[] bytes = response.body().readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("GitHub release description is too large");
            for (JsonNode asset : json.read(bytes, JsonNode.class).path("assets")) {
                if (!name.equals(asset.path("name").asText())) continue;
                URI download = URI.create(asset.path("url").asText());
                if (!api.getHost().equalsIgnoreCase(download.getHost())) throw new IOException("Unexpected asset address");
                return download;
            }
            return null;
        }
    }

    private static HttpRequest request(URI uri, String accept, long from) {
        var request = HttpRequest.newBuilder(uri).header("Accept", accept).header("User-Agent", AGENT)
                .timeout(Duration.ofSeconds(30)).GET();
        if (from > 0) request.header("Range", "bytes=" + from + "-");
        return request.build();
    }
}
