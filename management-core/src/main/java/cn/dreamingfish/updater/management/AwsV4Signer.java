package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

final class AwsV4Signer {
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private AwsV4Signer() {
    }

    static SignedRequest sign(String method, URI uri, String region,
                              String accessKeyId, String secretAccessKey,
                              String payloadHash, Map<String, String> headers,
                              Instant now) {
        String date = DATE.format(now);
        String timestamp = TIMESTAMP.format(now);
        TreeMap<String, String> canonical = new TreeMap<>();
        canonical.put("host", canonicalHost(uri));
        headers.forEach((name, value) -> canonical.put(
                name.toLowerCase(java.util.Locale.ROOT), normalize(value)));
        canonical.put("x-amz-date", timestamp);

        StringBuilder canonicalHeaders = new StringBuilder();
        canonical.forEach((name, value) -> canonicalHeaders
                .append(name).append(':').append(value).append('\n'));
        String signedHeaders = String.join(";", canonical.keySet());
        String canonicalRequest = method + "\n"
                + (uri.getRawPath() == null || uri.getRawPath().isEmpty()
                ? "/" : uri.getRawPath()) + "\n"
                + (uri.getRawQuery() == null ? "" : uri.getRawQuery()) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n"
                + scope + "\n" + sha256(canonicalRequest);
        byte[] dateKey = hmac(("AWS4" + secretAccessKey)
                .getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, "s3");
        byte[] signingKey = hmac(serviceKey, "aws4_request");
        String signature = Hex.encode(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
        return new SignedRequest(timestamp, authorization, signature,
                signedHeaders, canonicalRequest);
    }

    private static String canonicalHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ManagementException("S3 上传地址缺少主机名");
        }
        if (host.contains(":")) host = "[" + host + "]";
        int port = uri.getPort();
        boolean defaultPort = port < 0
                || (uri.getScheme().equalsIgnoreCase("https") && port == 443)
                || (uri.getScheme().equalsIgnoreCase("http") && port == 80);
        return host.toLowerCase(java.util.Locale.ROOT)
                + (defaultPort ? "" : ":" + port);
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.trim().replaceAll("[\\t\\r\\n ]+", " ");
    }

    private static String sha256(String value) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate AWS Signature V4", e);
        }
    }

    record SignedRequest(
            String timestamp,
            String authorization,
            String signature,
            String signedHeaders,
            String canonicalRequest
    ) {
    }
}
