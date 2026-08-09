package cn.dreamingfish.updater.management;

import java.net.URI;

public final class S3UploadConfiguration {
    private final URI endpoint;
    private final String region;
    private final String bucket;
    private final String prefix;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final S3AddressingStyle addressingStyle;

    public S3UploadConfiguration(URI endpoint, String region, String bucket,
                                 String prefix, String accessKeyId,
                                 String secretAccessKey, String sessionToken,
                                 S3AddressingStyle addressingStyle) {
        this.endpoint = endpoint;
        this.region = region;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken == null ? "" : sessionToken;
        this.addressingStyle = addressingStyle == null
                ? S3AddressingStyle.PATH : addressingStyle;
    }

    public URI endpoint() {
        return endpoint;
    }

    public String region() {
        return region;
    }

    public String bucket() {
        return bucket;
    }

    public String prefix() {
        return prefix;
    }

    public String accessKeyId() {
        return accessKeyId;
    }

    public String secretAccessKey() {
        return secretAccessKey;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public S3AddressingStyle addressingStyle() {
        return addressingStyle;
    }

    @Override
    public String toString() {
        return "S3UploadConfiguration[endpoint=" + endpoint + ", region="
                + region + ", bucket=" + bucket + ", prefix=" + prefix
                + ", accessKeyId=<redacted>, secretAccessKey=<redacted>, "
                + "sessionToken=<redacted>, addressingStyle=" + addressingStyle + "]";
    }
}
