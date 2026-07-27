package cn.dreamingfish.updater.player;

record LocalModEntry(
        String key,
        String displayName,
        String path,
        String componentId,
        boolean managed,
        boolean disabled,
        boolean active,
        boolean forced
) {
}
