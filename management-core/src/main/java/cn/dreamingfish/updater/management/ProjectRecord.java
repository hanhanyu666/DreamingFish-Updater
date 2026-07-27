package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;

import java.nio.file.Path;
import java.time.Instant;

public record ProjectRecord(
        String id,
        String displayName,
        Path sourceDirectory,
        String publicBaseUrl,
        String publicKey,
        Path privateKeyFile,
        Branding branding,
        ProjectRules rules,
        long nextSequence,
        Instant createdAt
) {
}
