package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ReleaseHistory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;

final class ReleaseHistoryClient {
    private static final int MAX_HISTORY_BYTES = 4 * 1024 * 1024;
    private final JsonCodec json = new JsonCodec();

    ReleaseHistory fetch(ProjectBinding binding, Path playerHome) throws IOException {
        String base = binding.baseUrl().endsWith("/") ? binding.baseUrl() : binding.baseUrl() + "/";
        URI endpoint = URI.create(base).resolve(
                "v1/projects/" + binding.projectId() + "/history");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .GET()
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .build();
        try {
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Release history returned HTTP " + response.statusCode());
                }
                byte[] bytes = readLimited(input);
                ReleaseHistory history = json.read(bytes, ReleaseHistory.class);
                validate(history, binding.projectId());
                writeCache(playerHome, bytes);
                return history;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Release history request was interrupted", e);
        }
    }

    ReleaseHistory loadCached(ProjectBinding binding, Path playerHome) {
        Path cache = cachePath(playerHome);
        if (!Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(cache)) return null;
        try {
            if (Files.size(cache) > MAX_HISTORY_BYTES) return null;
            ReleaseHistory history = json.read(cache, ReleaseHistory.class);
            validate(history, binding.projectId());
            return history;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void validate(ReleaseHistory history, String projectId) throws IOException {
        if (history.schemaVersion() != ProtocolConstants.RELEASE_HISTORY_SCHEMA_VERSION
                || !projectId.equals(history.projectId()) || history.releases().size() > 10000) {
            throw new IOException("Release history is incompatible");
        }
        long previous = Long.MAX_VALUE;
        for (var release : history.releases()) {
            if (release.releaseId() == null || release.releaseId().isBlank()
                    || release.displayVersion() == null || release.displayVersion().isBlank()
                    || release.createdAt() == null || release.sequence() < 1
                    || release.sequence() > previous) {
                throw new IOException("Release history contains an invalid entry");
            }
            previous = release.sequence();
        }
    }

    private void writeCache(Path playerHome, byte[] bytes) throws IOException {
        Path cache = cachePath(playerHome);
        Files.createDirectories(cache.getParent());
        Path temporary = cache.resolveSibling(cache.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path cachePath(Path playerHome) {
        return playerHome.resolve("state/release-history.json");
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > MAX_HISTORY_BYTES) throw new IOException("Release history is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
