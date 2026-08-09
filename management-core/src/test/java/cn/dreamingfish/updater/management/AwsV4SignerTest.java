package cn.dreamingfish.updater.management;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AwsV4SignerTest {
    @Test
    void matchesThePublishedAwsS3PutObjectSignatureExample() {
        String payloadHash =
                "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");

        AwsV4Signer.SignedRequest signed = AwsV4Signer.sign(
                "PUT",
                URI.create("https://examplebucket.s3.amazonaws.com/test%24file.text"),
                "us-east-1",
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                payloadHash,
                headers,
                Instant.parse("2013-05-24T00:00:00Z"));

        assertEquals(
                "98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
                signed.signature());
    }
}
