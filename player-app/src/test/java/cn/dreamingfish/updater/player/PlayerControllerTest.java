package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    void closeAfterInitializationFailureDeniesLaunchAndAlwaysExits() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0));
            CompletableFuture<String> denial = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.US_ASCII))) {
                    return reader.readLine();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            RecordingViewPort viewport = new RecordingViewPort();
            PlayerArguments arguments = PlayerArguments.parse(List.of(
                    "--bootstrap-port", Integer.toString(server.getLocalPort()),
                    "--bootstrap-token", "A".repeat(40),
                    "--instance", temp.resolve("instance").toString(),
                    "--binding", temp.resolve("missing-binding.json").toString(),
                    "--player-name", "测试玩家"));
            PlayerController controller = new PlayerController(
                    arguments, viewport, () -> viewport.exited = true);

            controller.start();
            controller.requestClose();

            assertTrue(viewport.exited);
            assertTrue(denial.get(2, TimeUnit.SECONDS).startsWith("DFS1 DENY "));
        }
    }

    @Test
    void retryAfterInitializationFailureRepeatsInitializationInsteadOfStartingUpdate() {
        RecordingViewPort viewport = new RecordingViewPort();
        PlayerArguments arguments = PlayerArguments.parse(List.of(
                "--bootstrap-port", "28080",
                "--bootstrap-token", "A".repeat(40),
                "--instance", temp.resolve("instance").toString(),
                "--binding", temp.resolve("missing-binding.json").toString(),
                "--player-name", "测试玩家"));
        PlayerController controller = new PlayerController(arguments, viewport, () -> viewport.exited = true);

        controller.start();
        controller.retry();

        assertEquals(2, viewport.errors.size());
        assertEquals(0, viewport.progressCalls);
    }

    @Test
    void postLaunchManagementRefreshFailureRemainsNonFatal() {
        AtomicReference<Exception> warning = new AtomicReference<>();

        boolean refreshed = PlayerController.runNonFatalPostLaunchRefresh(
                () -> { throw new IOException("scan failed"); }, warning::set);

        assertTrue(!refreshed);
        assertEquals("scan failed", warning.get().getMessage());
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

    @Test
    void playerProgramProgressUsesAVisibleUserFacingStatus() {
        AtomicReference<ProgressEvent> forwarded = new AtomicReference<>();

        PlayerController.playerProgramProgress(forwarded::set).onProgress(
                new ProgressEvent(cn.dreamingfish.updater.engine.UpdateStage.DOWNLOADING,
                        "Downloading files", "object-hash", 25, 100));

        assertEquals("正在更新玩家端程序", forwarded.get().message());
        assertEquals(null, forwarded.get().currentPath());
        assertEquals(25, forwarded.get().completedBytes());
        assertEquals(100, forwarded.get().totalBytes());
    }

    private static final class RecordingViewPort implements PlayerViewPort {
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        boolean ready;
        boolean exited;
        int launchKeptOpenCalls;
        int progressCalls;
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
            progressCalls++;
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
