package cn.dreamingfish.updater.protocol;

public final class ProtocolConstants {
    public static final int BINDING_SCHEMA_VERSION = 1;
    public static final int RELEASE_SCHEMA_VERSION = 1;
    public static final int PLAYER_PROGRAM_SCHEMA_VERSION = 1;
    public static final int PLAYER_PRESENTATION_SCHEMA_VERSION = 1;
    public static final int RELEASE_HISTORY_SCHEMA_VERSION = 1;
    public static final String CAPABILITY_FORCED_DIRECTORY_SYNC = "forced-directory-sync-v1";
    public static final String CAPABILITY_FORCED_FILE_SYNC = "forced-file-sync-v1";
    public static final String CAPABILITY_RELEASED_PATHS = "released-paths-v1";
    /**
     * Maintenance presets, independent cleanup directories, optional groups,
     * owner deletion over player self-management, takeover backups, duplicate
     * mod removal, withdrawals and corrections.
     */
    public static final String CAPABILITY_MAINTENANCE_POLICY = "maintenance-policy-v2";
    /** Three maintenance choices and persistent, recoverable owner removals. */
    public static final String CAPABILITY_SIMPLIFIED_MAINTENANCE = "simplified-maintenance-v1";
    /** Every release capability this code base can interpret. */
    public static final java.util.Set<String> RELEASE_CAPABILITIES = java.util.Set.of(
            CAPABILITY_FORCED_DIRECTORY_SYNC,
            CAPABILITY_FORCED_FILE_SYNC,
            CAPABILITY_RELEASED_PATHS,
            CAPABILITY_MAINTENANCE_POLICY,
            CAPABILITY_SIMPLIFIED_MAINTENANCE);
    public static final String SIGNATURE_HEADER = "X-Dfs-Signature";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String SIGNATURE_ALGORITHM = "Ed25519";

    private ProtocolConstants() {
    }
}
