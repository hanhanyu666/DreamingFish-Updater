package cn.dreamingfish.updater.management.cli;

record ManagementSettings(
        int schemaVersion,
        String dataDirectory,
        String defaultProjectId,
        String httpHost,
        int httpPort,
        int webPort,
        String webHost
) {
    static final int CURRENT_SCHEMA = 3;
    static final int DEFAULT_WEB_PORT = 18080;

    ManagementSettings withDataDirectory(String value) {
        return new ManagementSettings(schemaVersion, value, defaultProjectId,
                httpHost, httpPort, webPort, webHost);
    }

    ManagementSettings withDefaultProject(String value) {
        return new ManagementSettings(schemaVersion, dataDirectory, value,
                httpHost, httpPort, webPort, webHost);
    }

    ManagementSettings withHttp(String host, int port) {
        return new ManagementSettings(schemaVersion, dataDirectory, defaultProjectId,
                host, port, webPort, webHost);
    }

    ManagementSettings withWebPort(int port) {
        return new ManagementSettings(schemaVersion, dataDirectory, defaultProjectId,
                httpHost, httpPort, port, webHost);
    }

    ManagementSettings withWeb(String host, int port) {
        return new ManagementSettings(schemaVersion, dataDirectory, defaultProjectId,
                httpHost, httpPort, port, host);
    }
}
