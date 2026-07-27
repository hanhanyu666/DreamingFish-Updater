package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ProjectBinding;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public record UpdateRequest(
        Path instanceRoot,
        Path playerHome,
        ProjectBinding binding,
        String playerVersion,
        Set<String> supportedCapabilities,
        Duration connectTimeout,
        Duration requestTimeout,
        HttpClient httpClient,
        CancellationToken cancellationToken,
        LocalFileOverrides localFileOverrides
) {
    public UpdateRequest {
        instanceRoot = instanceRoot.toAbsolutePath().normalize();
        playerHome = playerHome.toAbsolutePath().normalize();
        supportedCapabilities = supportedCapabilities == null ? Set.of() : Set.copyOf(supportedCapabilities);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        cancellationToken = cancellationToken == null ? CancellationToken.NEVER : cancellationToken;
        localFileOverrides = localFileOverrides == null ? LocalFileOverrides.NONE : localFileOverrides;
    }

    public UpdateRequest(Path instanceRoot, Path playerHome, ProjectBinding binding,
                         String playerVersion, Set<String> supportedCapabilities,
                         Duration connectTimeout, Duration requestTimeout, HttpClient httpClient,
                         CancellationToken cancellationToken) {
        this(instanceRoot, playerHome, binding, playerVersion, supportedCapabilities,
                connectTimeout, requestTimeout, httpClient, cancellationToken,
                LocalFileOverrides.NONE);
    }

    public static UpdateRequest defaults(Path instanceRoot, Path playerHome, ProjectBinding binding,
                                         String playerVersion, Set<String> supportedCapabilities) {
        return new UpdateRequest(instanceRoot, playerHome, binding, playerVersion, supportedCapabilities,
                Duration.ofSeconds(5), Duration.ofSeconds(30), null, CancellationToken.NEVER,
                LocalFileOverrides.NONE);
    }
}
