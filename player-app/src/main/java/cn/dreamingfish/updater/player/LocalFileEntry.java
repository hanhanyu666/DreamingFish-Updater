package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.FilePolicy;

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
        int managedFileCount
) {
    boolean managed() {
        return forced || (!directlyExcluded && inheritedExclusion == null);
    }
}
