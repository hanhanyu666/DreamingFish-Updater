package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ProtocolConstants;
import cn.dreamingfish.updater.protocol.SignedDocument;
import cn.dreamingfish.updater.protocol.ProtocolException;

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
 * hosting, from one atomic {@code .signed} document. Old static directories
 * retain read compatibility through their neighbouring {@code .sig} file.
 */
public final class SignedPayloadSupport {
    private static final int MAX_SIGNATURE_BYTES = 512;

    private SignedPayloadSupport() {
    }

    public static SignedDocument resolvePayload(HttpClient client, HttpResponse<?> response, URI uri,
                                                Duration timeout, CancellationToken cancellation,
                                                byte[] payload, int maximumPayload)
            throws IOException, InterruptedException {
        cancellation.throwIfCancelled();
        Optional<String> header = response.headers().firstValue(ProtocolConstants.SIGNATURE_HEADER);
        if (header.isPresent()) {
            return new SignedDocument(payload, normalized(header.get()).orElseThrow(() ->
                    new UpdateException(UpdateErrorCode.INVALID_SIGNATURE, "Payload signature header is malformed")));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri.toASCIIString() + ".signed"))
                .GET().timeout(timeout).header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity").build();
        HttpResponse<InputStream> atomic = HttpTransfer.send(client, request, timeout, cancellation);
        try (InputStream input = atomic.body()) {
            if (atomic.statusCode() == 200) {
                byte[] bytes = input.readNBytes(maximumPayload + SignedDocument.MAX_OVERHEAD + 1);
                try { return SignedDocument.decode(bytes, maximumPayload); }
                catch (ProtocolException invalid) {
                    throw new UpdateException(UpdateErrorCode.INVALID_SIGNATURE, "Atomic signed document is invalid", invalid);
                }
            }
            if (atomic.statusCode() != 404) {
                if (atomic.statusCode() >= 500 || atomic.statusCode() == 408 || atomic.statusCode() == 429) {
                    throw new IOException("Atomic signed document is temporarily unavailable (HTTP " + atomic.statusCode() + ")");
                }
                throw new UpdateException(UpdateErrorCode.HTTP_ERROR, "Atomic signed document returned HTTP " + atomic.statusCode());
            }
        }
        String signature = resolveSignature(client, response, uri, timeout, cancellation).orElseThrow(() ->
                new UpdateException(UpdateErrorCode.INVALID_SIGNATURE, "Payload is missing its signature"));
        return new SignedDocument(payload, signature);
    }

    public static Optional<String> resolveSignature(HttpClient client,
                                                     HttpResponse<?> payloadResponse,
                                                     URI payloadUri,
                                                     Duration timeout)
            throws IOException, InterruptedException {
        return resolveSignature(client, payloadResponse, payloadUri, timeout, CancellationToken.NEVER);
    }

    private static Optional<String> resolveSignature(HttpClient client, HttpResponse<?> payloadResponse,
                                                      URI payloadUri, Duration timeout, CancellationToken cancellation)
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
        HttpResponse<InputStream> response = HttpTransfer.send(client, request, timeout, cancellation);
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
