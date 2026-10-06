package cn.dreamingfish.updater.protocol;

/**
 * One historical file version that a withdrawal removes from players. The
 * content hash always matches; a mod is also matched by mod ID and version so
 * renamed or re-downloaded copies of the same version are found.
 */
public record WithdrawalItem(String sha256, long size, String path, String componentId,
                             String version) {
    public WithdrawalItem {
        componentId = componentId == null || componentId.isBlank() ? null : componentId.trim();
        version = version == null || version.isBlank() ? null : version.trim();
    }

    /** Mods are searched in their whole top-level directory; other files only at their path. */
    public boolean modScoped() {
        return componentId != null || ManagedPaths.isModJar(path);
    }
}
