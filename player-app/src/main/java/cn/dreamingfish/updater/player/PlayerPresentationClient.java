package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.engine.SignedPayloadSupport;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PlayerPresentation;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/** Fetches the signed, release-independent player presentation and keeps a verified offline cache. */
final class PlayerPresentationClient {
    private static final int MAX_PRESENTATION_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 512;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final JsonCodec json = new JsonCodec();

    Branding fetch(UpdateRequest request) throws IOException {
        ProjectBinding binding = request.binding();
        CachedPresentation cached = loadVerified(binding, request.playerHome());
        String base = binding.baseUrl().endsWith("/") ? binding.baseUrl() : binding.baseUrl() + "/";
        URI endpoint = URI.create(base).resolve(
                "v1/projects/" + binding.projectId() + "/presentation");
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity");
        if (cached != null) {
            builder.header("If-None-Match", '"' + cached.sha256() + '"');
        }

        try {
            HttpResponse<InputStream> response = request.httpClient().send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() == 304 && cached != null) {
                    return cached.presentation().branding();
                }
                if (response.statusCode() != 200) {
                    throw new IOException("Player presentation returned HTTP "
                            + response.statusCode());
                }
                byte[] payload = readLimited(input);
                String signature = SignedPayloadSupport.resolveSignature(
                                request.httpClient(), response, endpoint, REQUEST_TIMEOUT)
                        .orElseThrow(() -> new IOException(
                                "Player presentation response is not signed"));
                PlayerPresentation presentation = verifyAndDecode(
                        binding, payload, signature);
                try {
                    writeCache(request.playerHome(), payload, signature);
                } catch (IOException ignored) {
                    // A verified network response is still safe to use for this launch.
                }
                return presentation.branding();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Player presentation request was interrupted", e);
        }
    }

    Branding loadCached(ProjectBinding binding, Path playerHome) {
        CachedPresentation cached = loadVerified(binding, playerHome);
        return cached == null ? null : cached.presentation().branding();
    }

    private CachedPresentation loadVerified(ProjectBinding binding, Path playerHome) {
        Path payloadPath = payloadPath(playerHome);
        Path signaturePath = signaturePath(playerHome);
        if (!safeRegularFile(payloadPath) || !safeRegularFile(signaturePath)) return null;
        try {
            if (Files.size(payloadPath) > MAX_PRESENTATION_BYTES
                    || Files.size(signaturePath) > MAX_SIGNATURE_BYTES) return null;
            byte[] payload = Files.readAllBytes(payloadPath);
            String signature = Files.readString(signaturePath, StandardCharsets.US_ASCII).trim();
            PlayerPresentation presentation = verifyAndDecode(binding, payload, signature);
            return new CachedPresentation(
                    presentation, CryptoSupport.sha256(payload));
        } catch (Exception ignored) {
            return null;
        }
    }

    private PlayerPresentation verifyAndDecode(ProjectBinding binding, byte[] payload,
                                                String encodedSignature) throws IOException {
        if (encodedSignature == null || encodedSignature.length() > MAX_SIGNATURE_BYTES) {
            throw new IOException("Player presentation signature is invalid");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException e) {
            throw new IOException("Player presentation signature is invalid", e);
        }
        if (!CryptoSupport.verify(payload, signature,
                CryptoSupport.decodePublicKey(binding.publicKey()))) {
            throw new IOException("Player presentation signature verification failed");
        }
        try {
            PlayerPresentation presentation = json.read(payload, PlayerPresentation.class);
            ManifestValidator.validatePlayerPresentation(presentation);
            if (!binding.projectId().equals(presentation.projectId())) {
                throw new IOException("Player presentation belongs to another project");
            }
            return presentation;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Player presentation is invalid", e);
        }
    }

    private static void writeCache(Path playerHome, byte[] payload,
                                   String signature) throws IOException {
        atomicWrite(payloadPath(playerHome), payload);
        atomicWrite(signaturePath(playerHome),
                (signature + System.lineSeparator()).getBytes(StandardCharsets.US_ASCII));
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean safeRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private static Path payloadPath(Path playerHome) {
        return playerHome.resolve("state/player-presentation.json");
    }

    private static Path signaturePath(Path playerHome) {
        return playerHome.resolve("state/player-presentation.sig");
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > MAX_PRESENTATION_BYTES) {
                throw new IOException("Player presentation is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private record CachedPresentation(PlayerPresentation presentation, String sha256) {
    }
}
