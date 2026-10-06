package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.FilePolicy;

/**
 * One file or directory of the published content as the player window lists it.
 *
 * @param forced       whether the player cannot change it (see {@code lockReason})
 * @param componentId  the mod ID, used to keep a self-managed mod across renames
 * @param preset       the owner's maintenance preset of a file
 * @param group        the optional group of a file, if any
 * @param lockReason   why the switch is unavailable, or {@code null}
 * @param modified     for default configurations: whether the local copy differs from the shipped one
 * @param resetPending whether the player asked to restore the default at the next update
 */
record LocalFileEntry(
        String path,
        String displayName,
        boolean directory,
        boolean directlyExcluded,
        String inheritedExclusion,
        boolean partiallyExcluded,
        boolean present,
        boolean forced,
        FilePolicy policy,
        int managedFileCount,
        String componentId,
        String preset,
        String group,
        String lockReason,
        Boolean modified,
        boolean resetPending
) {
    LocalFileEntry(String path, String displayName, boolean directory, boolean directlyExcluded,
                   String inheritedExclusion, boolean partiallyExcluded, boolean present,
                   boolean forced, FilePolicy policy, int managedFileCount) {
        this(path, displayName, directory, directlyExcluded, inheritedExclusion, partiallyExcluded,
                present, forced, policy, managedFileCount, null, null, null, null, null, false);
    }

    boolean managed() {
        return forced || (!directlyExcluded && inheritedExclusion == null);
    }
}
