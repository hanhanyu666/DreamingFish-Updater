package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.CancellationToken;
import cn.dreamingfish.updater.engine.HttpTransfer;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.Hex;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Backgrounds have a signed hash but no filename or size in the release format. */
final class PlayerBackgroundImage {
    static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private HttpClient client;

    Path cached(Path root, String hash) throws IOException {
        if (!Hex.isSha256(hash)) return null;
        Path target = target(root, hash);
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) <= MAX_BYTES
                && CryptoSupport.sha256(target).equals(hash) ? target : null;
    }

    synchronized Path sync(ProjectBinding binding, Path root, String hash, Path bundled,
                           CancellationToken cancellation) throws IOException, InterruptedException {
        if (hash == null || hash.isBlank()) return null; // Clearing the background must not resurrect the bundled one.
        if (!Hex.isSha256(hash)) throw new IOException("Invalid background hash");
        cancellation.throwIfCancelled();
        Path present = cached(root, hash);
        if (present != null) return present;
        Path target = target(root, hash);
        PathSafety.createSafeDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".cover-", ".tmp");
        try {
            if (bundled != null && Files.isRegularFile(bundled, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(bundled) <= MAX_BYTES && CryptoSupport.sha256(bundled).equals(hash)) {
                Files.copy(bundled, temporary, StandardCopyOption.REPLACE_EXISTING);
            } else {
                if (client == null) client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER).build();
                String base = binding.baseUrl().endsWith("/") ? binding.baseUrl() : binding.baseUrl() + "/";
                var request = HttpRequest.newBuilder(URI.create(base).resolve("v1/objects/sha256/" + hash))
                        .GET().timeout(TIMEOUT).header("Accept-Encoding", "identity").build();
                var response = HttpTransfer.send(client, request, TIMEOUT, cancellation);
                try (var input = response.body(); var output = Files.newOutputStream(temporary)) {
                    if (response.statusCode() != 200) throw new IOException("Background HTTP " + response.statusCode());
                    long total = 0;
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        cancellation.throwIfCancelled();
                        total += read;
                        if (total > MAX_BYTES) throw new IOException("Background exceeds 32 MiB");
                        output.write(buffer, 0, read);
                    }
                }
            }
            if (!CryptoSupport.sha256(temporary).equals(hash)) throw new IOException("Background hash mismatch");
            cancellation.throwIfCancelled();
            PathSafety.assertSafePathTree(target);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path target(Path root, String hash) throws IOException {
        return PathSafety.resolveInside(root, "DreamingFishUpdater/backgrounds/" + hash);
    }
}
