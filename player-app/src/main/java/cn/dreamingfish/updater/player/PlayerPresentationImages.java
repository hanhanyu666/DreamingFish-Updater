package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.CancellationToken;
import cn.dreamingfish.updater.engine.HttpTransfer;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.PlayerPresentationImage;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ShowcaseRules;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/** Optional image resources are downloaded independently, never on the update worker. */
final class PlayerPresentationImages {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private HttpClient client;

    private HttpClient client() {
        if (client == null) client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        return client;
    }

    synchronized boolean sync(ProjectBinding binding, Path root, List<PlayerPresentationImage> images, CancellationToken cancellation) {
        if (images == null) return false;
        boolean changed = false;
        for (var image : images) {
            if (cancellation.isCancelled()) break;
            try { changed |= download(binding, root, image, cancellation); }
            catch (IOException | RuntimeException ignored) { /* Only this optional image is unavailable. */ }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        return changed;
    }

    private boolean download(ProjectBinding binding, Path root, PlayerPresentationImage image, CancellationToken cancellation)
            throws IOException, InterruptedException {
        ShowcaseRules.validateImageFileName(image.fileName());
        if (!cn.dreamingfish.updater.protocol.Hex.isSha256(image.sha256())
                || image.size() < 1 || image.size() > ShowcaseRules.MAX_IMAGE_BYTES) return false;
        Path target = PathSafety.resolveInside(root, "DreamingFishUpdater/presentation-images/" + image.fileName());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == image.size()
                && CryptoSupport.sha256(target).equals(image.sha256())) return false;
        PathSafety.createSafeDirectories(target.getParent());
        String base = binding.baseUrl().endsWith("/") ? binding.baseUrl() : binding.baseUrl() + "/";
        URI endpoint = URI.create(base).resolve("v1/objects/sha256/" + image.sha256());
        HttpRequest request = HttpRequest.newBuilder(endpoint).GET().timeout(TIMEOUT).header("Accept-Encoding", "identity").build();
        Path temporary = Files.createTempFile(target.getParent(), ".image-", ".tmp");
        try {
            var response = HttpTransfer.send(client(), request, TIMEOUT, cancellation);
            try (var input = response.body(); var output = Files.newOutputStream(temporary)) {
                if (response.statusCode() != 200) throw new IOException("Image response status");
                long total = 0;
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > image.size()) throw new IOException("Image exceeds its signed size");
                    output.write(buffer, 0, read);
                }
                if (total != image.size()) throw new IOException("Truncated image");
            }
            if (!CryptoSupport.sha256(temporary).equals(image.sha256())) throw new IOException("Image hash mismatch");
            cancellation.throwIfCancelled();
            PathSafety.assertSafePathTree(target);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } finally { Files.deleteIfExists(temporary); }
    }
}
