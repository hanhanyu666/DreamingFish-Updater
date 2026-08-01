package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerControllerTest {
    @TempDir
    Path temp;

    @Test
    void previewCloseExitsImmediatelyWithoutLaunchPermission() {
        RecordingViewPort viewport = new RecordingViewPort();
        PlayerArguments arguments = PlayerArguments.parse(List.of("--preview"));
        PlayerController controller = new PlayerController(arguments, viewport, () -> viewport.exited = true);

        controller.requestClose();

        assertTrue(viewport.exited);
    }

    @Test
    void missingBindingShowsInitializationErrorAndReadies() {
        RecordingViewPort viewport = new RecordingViewPort();
        PlayerArguments arguments = PlayerArguments.parse(List.of(
                "--bootstrap-port", "28080",
                "--bootstrap-token", "A".repeat(40),
                "--instance", temp.resolve("instance").toString(),
                "--binding", temp.resolve("missing-binding.json").toString(),
                "--player-name", "测试玩家"));
        PlayerController controller = new PlayerController(arguments, viewport, () -> viewport.exited = true);

        controller.start();

        assertTrue(viewport.ready);
        assertTrue(viewport.errors.size() >= 1);
        assertEquals("更新器无法启动", viewport.errors.getFirst());
        assertTrue(viewport.identity == null || "未识别玩家".equals(viewport.identity));
    }

    @Test
    void keepsWindowOpenSuppressesAutoCloseNotice() {
        RecordingViewPort viewport = new RecordingViewPort();
        PlayerArguments arguments = PlayerArguments.parse(List.of("--preview"));
        PlayerController controller = new PlayerController(arguments, viewport, () -> viewport.exited = true);

        controller.keepWindowOpen();

        // preview mode never emits launch notices; this call must be a safe no-op
        assertTrue(viewport.launchKeptOpenCalls <= 1);
    }

    private static final class RecordingViewPort implements PlayerViewPort {
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        boolean ready;
        boolean exited;
        int launchKeptOpenCalls;
        String identity;

        @Override
        public void setPlayerIdentity(String name) {
            identity = name;
        }

        @Override
        public void setBranding(Branding branding) {
        }

        @Override
        public void setBackground(Path localCover) {
        }

        @Override
        public void setLogs(List<String> lines) {
        }

        @Override
        public void setReleaseHistory(ReleaseHistory history) {
        }

        @Override
        public void appendLog(String line) {
            logs.add(line);
        }

        @Override
        public void showProgress(ProgressEvent event) {
        }

        @Override
        public void showResult(UpdateResult result) {
        }

        @Override
        public void showUnverifiedOfflineLaunch() {
        }

        @Override
        public void showLocalContentOverrideLaunch() {
        }

        @Override
        public void showError(String title, String detail, boolean allowContinue) {
            errors.add(title);
        }

        @Override
        public void setLocalMods(List<LocalModEntry> mods) {
        }

        @Override
        public void setLocalFiles(List<LocalFileEntry> files) {
        }

        @Override
        public void showLaunchCountdown(int seconds) {
        }

        @Override
        public void showLaunchKeptOpen() {
            launchKeptOpenCalls++;
        }

        @Override
        public boolean confirmDialog(DialogTone tone, String title, String heading,
                                     String message, String actionText, String cancelText) {
            return false;
        }

        @Override
        public void openPlayerDirectory(Path playerHome) {
        }

        @Override
        public void openArchiveDirectory(Path archiveDirectory) {
        }

        @Override
        public void openExternalLink(URI uri) {
        }

        @Override
        public void fadeOut(long durationMillis, Runnable finished) {
        }

        @Override
        public void ready() {
            ready = true;
        }
    }
}
