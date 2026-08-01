package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.ReleaseHistory;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** UI-agnostic contract used by {@link PlayerController} so the JavaFX window and the Tauri sidecar share identical orchestration. */
public interface PlayerViewPort {
    enum DialogTone {
        INFO,
        WARNING,
        DANGER
    }

    void setPlayerIdentity(String name);

    void setBranding(Branding branding);

    void setBackground(Path localCover);

    void setLogs(List<String> lines);

    void setReleaseHistory(ReleaseHistory history);

    void appendLog(String line);

    void showProgress(ProgressEvent event);

    void showResult(UpdateResult result);

    void showUnverifiedOfflineLaunch();

    void showLocalContentOverrideLaunch();

    void showError(String title, String detail, boolean allowContinue);

    void setLocalMods(List<LocalModEntry> mods);

    void setLocalFiles(List<LocalFileEntry> files);

    void showLaunchCountdown(int seconds);

    void showLaunchKeptOpen();

    boolean confirmDialog(DialogTone tone, String title, String heading, String message,
                          String actionText, String cancelText);

    void openPlayerDirectory(Path playerHome);

    void openArchiveDirectory(Path archiveDirectory);

    void openExternalLink(URI uri);

    void fadeOut(long durationMillis, Runnable finished);

    /** Called after configuration is loaded (or initialization failed); the host may show the window and play the entrance. */
    void ready();
}
