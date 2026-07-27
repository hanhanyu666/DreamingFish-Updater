package cn.dreamingfish.updater.protocol;

public final class ProtocolConstants {
    public static final int BINDING_SCHEMA_VERSION = 1;
    public static final int RELEASE_SCHEMA_VERSION = 1;
    public static final int PLAYER_PROGRAM_SCHEMA_VERSION = 1;
    public static final String CAPABILITY_FORCED_DIRECTORY_SYNC = "forced-directory-sync-v1";
    public static final String SIGNATURE_HEADER = "X-Dfs-Signature";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String SIGNATURE_ALGORITHM = "Ed25519";

    private ProtocolConstants() {
    }
}
