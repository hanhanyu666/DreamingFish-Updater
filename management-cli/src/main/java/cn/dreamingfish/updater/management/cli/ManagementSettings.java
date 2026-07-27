package cn.dreamingfish.updater.management.cli;

record ManagementSettings(
        int schemaVersion,
        String dataDirectory,
        String defaultProjectId,
        String httpHost,
        int httpPort
) {
    static final int CURRENT_SCHEMA = 1;

    ManagementSettings withDataDirectory(String value) {
        return new ManagementSettings(schemaVersion, value, defaultProjectId, httpHost, httpPort);
    }

    ManagementSettings withDefaultProject(String value) {
        return new ManagementSettings(schemaVersion, dataDirectory, value, httpHost, httpPort);
    }

    ManagementSettings withHttp(String host, int port) {
        return new ManagementSettings(schemaVersion, dataDirectory, defaultProjectId, host, port);
    }
}
