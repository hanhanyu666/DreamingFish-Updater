package cn.dreamingfish.updater.engine;

@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = event -> { };

    void onProgress(ProgressEvent event);
}
