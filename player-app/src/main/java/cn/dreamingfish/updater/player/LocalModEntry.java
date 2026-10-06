package cn.dreamingfish.updater.player;

/**
 * One mod as the player window lists it.
 *
 * @param forced          whether the player cannot switch it (see {@code lockReason})
 * @param version         the mod version from its metadata or the release
 * @param preset          the owner's maintenance preset, or {@code null} for player-added mods
 * @param group           the optional group controlling this mod, if any
 * @param lockReason      why the switch is unavailable, or {@code null}
 * @param withdrawnReason the owner's reason when this exact version was withdrawn
 */
record LocalModEntry(
        String key,
        String displayName,
        String path,
        String componentId,
        boolean managed,
        boolean disabled,
        boolean active,
        boolean forced,
        String version,
        String preset,
        String group,
        String groupTitle,
        String lockReason,
        String withdrawnReason
) {
    LocalModEntry(String key, String displayName, String path, String componentId,
                  boolean managed, boolean disabled, boolean active, boolean forced) {
        this(key, displayName, path, componentId, managed, disabled, active, forced,
                null, null, null, null, null, null);
    }
}
