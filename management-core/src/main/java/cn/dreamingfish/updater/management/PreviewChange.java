package cn.dreamingfish.updater.management;

public record PreviewChange(
        ChangeKind kind,
        String path,
        String oldSha256,
        String newSha256,
        long downloadSize
) {
}
