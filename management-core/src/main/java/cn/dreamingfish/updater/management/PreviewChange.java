package cn.dreamingfish.updater.management;

public record PreviewChange(
        ChangeKind kind,
        String path,
        String oldSha256,
        String newSha256,
        long downloadSize,
        RemovalAction removalAction
) {
    public PreviewChange(ChangeKind kind, String path, String oldSha256,
                         String newSha256, long downloadSize) {
        this(kind, path, oldSha256, newSha256, downloadSize, null);
    }

    public PreviewChange withRemovalAction(RemovalAction action) {
        if (kind != ChangeKind.REMOVED) {
            throw new IllegalStateException("Only removed files have a removal action");
        }
        return new PreviewChange(kind, path, oldSha256, newSha256, downloadSize, action);
    }
}
