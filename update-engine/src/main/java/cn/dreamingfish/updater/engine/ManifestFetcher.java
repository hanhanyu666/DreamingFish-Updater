package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import cn.dreamingfish.updater.protocol.SemanticVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Set;

final class ManifestFetcher {
    private static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    private final JsonCodec json = new JsonCodec();

    SignedRelease fetch(UpdateRequest request, PublicKey publicKey, TrustState trustState) {
        URI uri = endpoint(request.binding(), "v1/projects/" + request.binding().projectId() + "/latest");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(request.requestTimeout())
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .build();
        try {
            HttpResponse<InputStream> response = client(request).send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() >= 500 || response.statusCode() == 408
                        || response.statusCode() == 429) {
                    throw new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE,
                            "Update service is temporarily unavailable (HTTP "
                                    + response.statusCode() + ")");
                }
                if (response.statusCode() != 200) {
                    throw new UpdateException(UpdateErrorCode.HTTP_ERROR,
                            "Latest release request failed with HTTP " + response.statusCode());
                }
                String signature = response.headers().firstValue(ProtocolConstants.SIGNATURE_HEADER)
                        .orElseThrow(() -> new UpdateException(UpdateErrorCode.INVALID_SIGNATURE,
                                "Release response does not contain a signature"));
                byte[] bytes = readLimited(input, MAX_MANIFEST_BYTES);
                LocalInstallationStore.verifySignature(bytes, signature, publicKey);
                ReleaseManifest manifest;
                try {
                    manifest = json.read(bytes, ReleaseManifest.class);
                    ManifestValidator.validateRelease(manifest, request.supportedCapabilities());
                } catch (ProtocolException e) {
                    throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                            "Release manifest is invalid", e);
                }
                validateIdentityAndVersion(manifest, request);
                String hash = CryptoSupport.sha256(bytes);
                validateReplay(manifest, hash, trustState);
                return new SignedRelease(manifest, bytes, signature, hash);
            }
        } catch (UpdateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.CANCELLED, "Release check was interrupted", e);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE,
                    "Unable to contact the update service", e);
        }
    }

    static URI endpoint(ProjectBinding binding, String relative) {
        String base = binding.baseUrl().endsWith("/") ? binding.baseUrl() : binding.baseUrl() + "/";
        return URI.create(base).resolve(relative);
    }

    static HttpClient client(UpdateRequest request) {
        if (request.httpClient() != null) return request.httpClient();
        return HttpClient.newBuilder()
                .connectTimeout(request.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private void validateIdentityAndVersion(ReleaseManifest manifest, UpdateRequest request) {
        if (!manifest.projectId().equals(request.binding().projectId())) {
            throw new UpdateException(UpdateErrorCode.WRONG_PROJECT,
                    "Release manifest belongs to another project");
        }
        try {
            if (SemanticVersion.parse(request.playerVersion())
                    .compareTo(SemanticVersion.parse(manifest.minimumPlayerVersion())) < 0) {
                throw new UpdateException(UpdateErrorCode.UNSUPPORTED_PLAYER_VERSION,
                        "Player updater " + request.playerVersion() + " is older than required "
                                + manifest.minimumPlayerVersion());
            }
        } catch (ProtocolException e) {
            throw new UpdateException(UpdateErrorCode.UNSUPPORTED_PLAYER_VERSION,
                    "Player updater version is invalid", e);
        }
    }

    private void validateReplay(ReleaseManifest manifest, String hash, TrustState trust) {
        if (trust == null) return;
        if (manifest.sequence() < trust.highestSequence()) {
            throw new UpdateException(UpdateErrorCode.REPLAY_DETECTED,
                    "Update service returned an older release sequence");
        }
        if (manifest.sequence() == trust.highestSequence()
                && (!manifest.releaseId().equals(trust.releaseId())
                || !hash.equals(trust.manifestSha256()))) {
            throw new UpdateException(UpdateErrorCode.REPLAY_DETECTED,
                    "Update service changed a previously accepted release sequence");
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > maximum) {
                throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                        "Release manifest exceeds the 32 MiB limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
