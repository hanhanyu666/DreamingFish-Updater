package cn.dreamingfish.updater.engine;

public record ProgressEvent(
        UpdateStage stage,
        String message,
        String currentPath,
        long completedBytes,
        long totalBytes
) {
    public double fraction() {
        if (totalBytes <= 0) return -1;
        return Math.min(1.0, Math.max(0.0, (double) completedBytes / totalBytes));
    }
}
