package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ProtocolConstants;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Resolves a payload signature from the normal response header or, for static
 * hosting services that cannot add custom headers, from a neighbouring
 * {@code .sig} file.
 */
public final class SignedPayloadSupport {
    private static final int MAX_SIGNATURE_BYTES = 512;

    private SignedPayloadSupport() {
    }

    public static Optional<String> resolveSignature(HttpClient client,
                                                     HttpResponse<?> payloadResponse,
                                                     URI payloadUri,
                                                     Duration timeout)
            throws IOException, InterruptedException {
        Optional<String> header = payloadResponse.headers()
                .firstValue(ProtocolConstants.SIGNATURE_HEADER);
        if (header.isPresent()) {
            return normalized(header.get());
        }

        URI sidecarUri = URI.create(payloadUri.toASCIIString() + ".sig");
        HttpRequest request = HttpRequest.newBuilder(sidecarUri)
                .GET()
                .timeout(timeout)
                .header("Accept", "text/plain, application/octet-stream")
                .header("Accept-Encoding", "identity")
                .build();
        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() >= 500 || response.statusCode() == 408
                    || response.statusCode() == 429) {
                throw new IOException("Signature sidecar is temporarily unavailable (HTTP "
                        + response.statusCode() + ")");
            }
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            byte[] bytes = input.readNBytes(MAX_SIGNATURE_BYTES + 1);
            if (bytes.length > MAX_SIGNATURE_BYTES) {
                return Optional.empty();
            }
            return normalized(new String(bytes, StandardCharsets.US_ASCII));
        }
    }

    private static Optional<String> normalized(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_SIGNATURE_BYTES) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }
}
