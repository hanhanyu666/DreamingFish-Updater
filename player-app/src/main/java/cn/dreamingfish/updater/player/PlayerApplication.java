package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.CancellationToken;
import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.ProgressListener;
import cn.dreamingfish.updater.engine.GameUpdateLock;
import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.engine.PlayerProgramUpdateOutcome;
import cn.dreamingfish.updater.engine.PlayerProgramUpdateResult;
import cn.dreamingfish.updater.engine.PlayerProgramUpdater;
import cn.dreamingfish.updater.engine.UpdateEngine;
import cn.dreamingfish.updater.engine.UpdateErrorCode;
import cn.dreamingfish.updater.engine.UpdateException;
import cn.dreamingfish.updater.engine.UpdateOutcome;
import cn.dreamingfish.updater.engine.UpdateRequest;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.engine.UpdateStage;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolConstants;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerApplication extends Application {
    static final String VERSION = "0.1.9";
    static final String BOOTSTRAP_AGENT_VERSION = "0.1.2";
    private static final int AUTO_CLOSE_SECONDS = 15;

    private final JsonCodec json = new JsonCodec();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Object localPreferenceLock = new Object();
    private final ReleaseHistoryClient releaseHistoryClient = new ReleaseHistoryClient();
    private PlayerArguments arguments;
    private BootstrapPermitClient permitClient;
    private ProjectBinding binding;
    private Path playerHome;
    private PlayerLog log;
    private PlayerView view;
    private Stage stage;
    private volatile boolean working;
    private volatile boolean launchPermitted;
    private volatile boolean restartPending;
    private volatile boolean autoCloseSuppressed;
    private Timeline autoCloseCountdown;
    private BackgroundMusic backgroundMusic;
    private Path lastArchiveDirectory;
    private LocalModManager localModManager;
    private LocalFileManager localFileManager;

    private record LocalSettingsSnapshot(
            long modRevision,
            long fileRevision,
            LocalFileOverrides overrides
    ) {
        boolean sameRevision(LocalSettingsSnapshot other) {
            return other != null && modRevision == other.modRevision
                    && fileRevision == other.fileRevision;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        arguments = PlayerArguments.parse(getParameters().getRaw());
        permitClient = new BootstrapPermitClient(arguments);
    }

    @Override
    public void start(Stage primaryStage) {
        loadBundledFont();
        stage = primaryStage;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("DreamingFish Updater");
        stage.setWidth(1180);
        stage.setHeight(680);
        stage.setMinWidth(960);
        stage.setMinHeight(560);

        view = new PlayerView(stage);
        Scene scene = new Scene(view.root(), 1180, 680, Color.TRANSPARENT);
        String stylesheet = PlayerApplication.class.getResource("player.css").toExternalForm();
        scene.getStylesheets().add(stylesheet);
        stage.setScene(scene);
        view.setCloseAction(this::requestClose);
        view.setRetryAction(this::startUpdate);
        view.setOpenDirectoryAction(this::openPlayerDirectory);
        view.setOpenArchiveAction(() -> {
            keepWindowOpen();
            openArchiveDirectory();
        });
        view.setDetailsOpenedAction(this::keepWindowOpen);
        view.setMusicToggleAction(this::toggleBackgroundMusic);
        view.setLocalModToggleAction(this::changeLocalModPreference);
        view.setRestoreModsAction(this::restoreLocalModDefaults);
        view.setLocalFileToggleAction(this::changeLocalFilePreference);
        view.setRestoreFilesAction(this::restoreLocalFileDefaults);
        view.setPlayerIdentity(arguments.playerName());
        stage.setOnCloseRequest(event -> {
            event.consume();
            requestClose();
        });

        if (arguments.preview()) {
            showPreview();
            return;
        }

        try {
            loadConfiguration();
            permitClient.ready();
            startBackgroundMusic(playerHome.resolve("state/background-music-muted"));
            stage.show();
            stage.centerOnScreen();
            view.playEntrance();
            refreshLocalManagementAsync();
            Platform.runLater(this::startUpdate);
        } catch (Exception e) {
            showInitializationFailure(e);
        }
    }

    private void showPreview() {
        binding = new ProjectBinding(1, "dreamhaven", "http://127.0.0.1:8080", "preview",
                "DreamingFishUpdater", null,
                new Branding("梦屿", "灾变之后，仍有人在这里守望。",
                        "", null, "#2ee8df", "#b06cff"));
        view.setBranding(binding.fallbackBranding());
        view.setBackground(null);
        view.showPreview();
        view.showLaunchCountdown(AUTO_CLOSE_SECONDS);
        startBackgroundMusic(null);
        view.appendLog("12:08:41  INFO  已连接到守望梦屿更新服务");
        view.appendLog("12:08:42  INFO  正在下载 mods/dreamingfish-core.jar");
        stage.show();
        stage.centerOnScreen();
        view.playEntrance();
    }

    private static void loadBundledFont() {
        try (InputStream fontStream = PlayerApplication.class
                .getResourceAsStream("fonts/HarmonyOS_Sans_SC_Bold.ttf")) {
            if (fontStream != null) Font.loadFont(fontStream, 12);
        } catch (IOException ignored) {
            // The CSS keeps system-font fallbacks so a font-loading failure is non-fatal.
        }
    }

    private void loadConfiguration() throws IOException {
        binding = json.read(arguments.bindingFile(), ProjectBinding.class);
        ManifestValidator.validateBinding(binding);
        playerHome = resolvePlayerHome(binding);
        Files.createDirectories(playerHome);
        log = new PlayerLog(playerHome);
        log.setListener(line -> Platform.runLater(() -> view.appendLog(line)));
        view.setLogs(log.readRecentLines(5000));
        localModManager = new LocalModManager(arguments.instanceRoot(), playerHome);
        localFileManager = new LocalFileManager(playerHome);
        view.setBranding(binding.fallbackBranding());
        view.setBackground(resolveBundledCover(binding));
        view.setReleaseHistory(releaseHistoryClient.loadCached(binding, playerHome));
        log.info("Player updater started for project " + binding.projectId());
    }

    private void startUpdate() {
        if (arguments.preview() || working || launchPermitted) return;
        cancelled.set(false);
        working = true;
        view.showProgress(new ProgressEvent(UpdateStage.CHECKING,
                "正在连接更新服务", null, 0, 0));
        ProgressListener progress = throttledProgress();
        CancellationToken cancellation = cancelled::get;

        Thread.ofVirtual().name("startup-update").start(() -> {
            try {
                LocalSettingsSnapshot snapshot = reconcileLocalState();
                UpdateRequest request = updateRequest(snapshot, cancellation);
                PlayerProgramUpdateResult programResult = new PlayerProgramUpdater()
                        .checkAndInstall(request, BOOTSTRAP_AGENT_VERSION, null, progress);
                if (programResult.outcome() == PlayerProgramUpdateOutcome.CHECK_UNAVAILABLE) {
                    log.info("Player updater version check is unavailable; continuing with the modpack check");
                } else if (programResult.outcome() == PlayerProgramUpdateOutcome.NOT_PUBLISHED) {
                    log.info("No player updater program has been published for this project");
                }
                if (programResult.restartRequired()) {
                    working = false;
                    log.info("Player updater " + programResult.manifest().version()
                            + " installed; restarting through the bootstrap Agent");
                    Platform.runLater(() -> restartWithUpdatedProgram(programResult));
                    return;
                }

                UpdateResult result = null;
                UpdateEngine engine = new UpdateEngine();
                while (true) {
                    UpdateResult pass = engine.update(updateRequest(snapshot, cancellation), progress);
                    result = mergeResults(result, pass);
                    boolean preferencesChanged;
                    try (GameUpdateLock gameLock = acquireGameUpdateLock()) {
                        synchronized (localPreferenceLock) {
                            localModManager.reconcileDesiredState(result.release());
                            LocalSettingsSnapshot latest = localSettingsSnapshot();
                            preferencesChanged = !snapshot.sameRevision(latest);
                            if (preferencesChanged) {
                                snapshot = latest;
                            } else {
                                localModManager.finalizeSuccessfulUpdate();
                                permitClient.allow(gameLock::close);
                                launchPermitted = true;
                            }
                        }
                    }
                    if (!preferencesChanged) break;
                    log.info("Local mod preferences changed during the update; reconciling again");
                }
                working = false;
                log.info("Launch permission granted for release " + result.release().releaseId());
                if (!result.archivedFiles().isEmpty()) {
                    log.info("Remote management forced sync for directories: "
                            + String.join(", ", result.release().forcedSyncDirectories()));
                    log.info("Archived " + result.archivedFiles().size()
                            + " local files to " + result.archiveDirectory());
                    result.archivedFiles().forEach(path -> log.info("Archived local file: " + path));
                }
                List<LocalModEntry> mods = localModManager.scan(result.release());
                List<LocalFileEntry> files = localFileManager.scan(result.release());
                UpdateResult completedResult = result;
                Platform.runLater(() -> {
                    view.setLocalMods(mods);
                    view.setLocalFiles(files);
                    finishSuccessfully(completedResult);
                });
                refreshReleaseHistory();
            } catch (Exception e) {
                working = false;
                log.error("Update failed", e);
                Platform.runLater(() -> showFailure(errorTitle(e), e));
            }
        });
    }

    private LocalSettingsSnapshot reconcileLocalState() throws IOException {
        try (GameUpdateLock gameLock = acquireGameUpdateLock()) {
            synchronized (localPreferenceLock) {
                var installed = localModManager.loadInstalledManifest(binding.projectId());
                localModManager.reconcileDesiredState(installed);
                return localSettingsSnapshot();
            }
        }
    }

    private LocalSettingsSnapshot localSettingsSnapshot() throws IOException {
        LocalModManager.Snapshot mods = localModManager.snapshot();
        LocalFileManager.Snapshot files = localFileManager.snapshot();
        return new LocalSettingsSnapshot(mods.revision(), files.revision(),
                mods.overrides().merge(files.overrides()));
    }

    private GameUpdateLock acquireGameUpdateLock() {
        Path marker = arguments.instanceRoot().resolve(".dreamingfish-bootstrap/game.lock");
        GameUpdateLock lock = GameUpdateLock.tryAcquire(marker);
        if (lock == null) {
            throw new UpdateException(UpdateErrorCode.GAME_RUNNING,
                    "Minecraft is already running in this instance");
        }
        return lock;
    }

    private UpdateRequest updateRequest(LocalSettingsSnapshot snapshot,
                                        CancellationToken cancellation) {
        return new UpdateRequest(arguments.instanceRoot(), playerHome, binding,
                VERSION, Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC),
                null, null, null, cancellation, snapshot.overrides());
    }

    private UpdateResult mergeResults(UpdateResult previous, UpdateResult current) {
        if (previous == null) return current;
        List<Path> installed = combinePaths(previous.installedPaths(), current.installedPaths());
        List<Path> deleted = combinePaths(previous.deletedPaths(), current.deletedPaths());
        List<Path> archived = combinePaths(previous.archivedFiles(), current.archivedFiles());
        return new UpdateResult(
                current.outcome(), current.release(), installed.size(), deleted.size(),
                previous.downloadedBytes() + current.downloadedBytes(),
                current.unmanagedMods(), archived,
                current.archiveDirectory() != null
                        ? current.archiveDirectory() : previous.archiveDirectory(),
                installed, deleted);
    }

    private static List<Path> combinePaths(List<Path> first, List<Path> second) {
        java.util.LinkedHashSet<Path> paths = new java.util.LinkedHashSet<>(first);
        paths.addAll(second);
        return List.copyOf(paths);
    }

    private void changeLocalModPreference(LocalModEntry entry, Boolean disabled) {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localModManager.setDisabled(entry, Boolean.TRUE.equals(disabled));
            }
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("Unable to save local mod preference", e);
            showFailure("无法保存模组设置", e);
        }
    }

    private void restoreLocalModDefaults() {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localModManager.restoreDefaults();
            }
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("Unable to restore local mod defaults", e);
            showFailure("无法恢复模组设置", e);
        }
    }

    private void changeLocalFilePreference(LocalFileEntry entry, Boolean managed) {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localFileManager.setManaged(entry, Boolean.TRUE.equals(managed));
            }
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("Unable to save local file preference", e);
            showFailure("无法保存本地文件设置", e);
        }
    }

    private void restoreLocalFileDefaults() {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localFileManager.restoreDefaults();
            }
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("Unable to restore local file defaults", e);
            showFailure("无法恢复文件管理设置", e);
        }
    }

    private void refreshLocalManagementAsync() {
        if (localModManager == null || localFileManager == null) return;
        Thread.ofVirtual().name("local-management-scan").start(() -> {
            try {
                var release = localModManager.loadInstalledManifest(binding.projectId());
                List<LocalModEntry> mods = localModManager.scan(release);
                List<LocalFileEntry> files = localFileManager.scan(release);
                Platform.runLater(() -> {
                    view.setLocalMods(mods);
                    view.setLocalFiles(files);
                });
            } catch (IOException e) {
                log.error("Unable to scan local management settings", e);
            }
        });
    }

    private void refreshReleaseHistory() {
        Thread.ofVirtual().name("release-history").start(() -> {
            try {
                var history = releaseHistoryClient.fetch(binding, playerHome);
                Platform.runLater(() -> view.setReleaseHistory(history));
            } catch (IOException e) {
                log.info("Release history is unavailable; using locally cached records");
            }
        });
    }

    private void restartWithUpdatedProgram(PlayerProgramUpdateResult result) {
        restartPending = true;
        long downloaded = result.downloadedBytes();
        view.showProgress(new ProgressEvent(UpdateStage.COMPLETE,
                "更新器升级完成", "正在切换到新版本", downloaded, downloaded));
        PauseTransition pause = new PauseTransition(Duration.millis(350));
        pause.setOnFinished(event -> exitApplication());
        pause.play();
    }

    private ProgressListener throttledProgress() {
        AtomicLong lastUi = new AtomicLong();
        AtomicReference<UpdateStage> lastStage = new AtomicReference<>();
        return event -> {
            long now = System.nanoTime();
            boolean stageChanged = lastStage.getAndSet(event.stage()) != event.stage();
            if (stageChanged) log.info(event.message());
            if (stageChanged || now - lastUi.get() >= 50_000_000L
                    || (event.totalBytes() > 0 && event.completedBytes() >= event.totalBytes())) {
                lastUi.set(now);
                Platform.runLater(() -> view.showProgress(event));
            }
        };
    }

    private void finishSuccessfully(UpdateResult result) {
        lastArchiveDirectory = result.archiveDirectory();
        view.showResult(result);
        if (autoCloseSuppressed) {
            view.showLaunchKeptOpen();
            return;
        }
        int[] remaining = {AUTO_CLOSE_SECONDS};
        view.showLaunchCountdown(remaining[0]);
        autoCloseCountdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            remaining[0]--;
            if (remaining[0] > 0) {
                view.showLaunchCountdown(remaining[0]);
            } else {
                view.fadeOut(Duration.millis(320), this::exitApplication);
            }
        }));
        autoCloseCountdown.setCycleCount(AUTO_CLOSE_SECONDS);
        autoCloseCountdown.play();
    }

    private void keepWindowOpen() {
        autoCloseSuppressed = true;
        if (autoCloseCountdown != null) {
            autoCloseCountdown.stop();
            autoCloseCountdown = null;
        }
        if (launchPermitted) view.showLaunchKeptOpen();
    }

    private void showFailure(String title, Throwable error) {
        String detail = error.getMessage() == null || error.getMessage().isBlank()
                ? "请查看日志后重试"
                : error.getMessage();
        view.showError(title, detail);
    }

    private void showInitializationFailure(Exception error) {
        if (log != null) log.error("Player updater initialization failed", error);
        if (binding == null) {
            binding = new ProjectBinding(1, "unknown", "http://127.0.0.1", "invalid",
                    "DreamingFishUpdater", null, Branding.empty());
            view.setBranding(Branding.empty());
            view.setBackground(null);
        }
        view.showError(error instanceof BootstrapPermitClient.PermitException
                        ? "启动许可已失效" : "更新器无法启动",
                error.getMessage());
        startBackgroundMusic(playerHome == null
                ? null
                : playerHome.resolve("state/background-music-muted"));
        stage.show();
        stage.centerOnScreen();
        view.playEntrance();
    }

    private void requestClose() {
        if (arguments.preview() || launchPermitted || restartPending) {
            exitApplication();
            return;
        }
        if (working) {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "关闭更新器会取消本次更新，并停止 Minecraft 启动。",
                    ButtonType.CANCEL, ButtonType.OK);
            confirmation.initOwner(stage);
            confirmation.setTitle("取消更新");
            confirmation.setHeaderText("确定要关闭更新器吗？");
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            cancelled.set(true);
        }
        closeAndDeny("玩家关闭了更新器");
    }

    private void closeAndDeny(String reason) {
        permitClient.deny(reason);
        exitApplication();
    }

    private void exitApplication() {
        if (autoCloseCountdown != null) autoCloseCountdown.stop();
        stopBackgroundMusic();
        stage.close();
        Platform.exit();
        System.exit(0);
    }

    @Override
    public void stop() {
        stopBackgroundMusic();
    }

    private void startBackgroundMusic(Path mutedMarker) {
        stopBackgroundMusic();
        backgroundMusic = new BackgroundMusic(
                mutedMarker,
                state -> Platform.runLater(() -> view.setMusicState(state)),
                error -> {
                    if (log != null) log.error("Background music playback failed", error);
                }
        );
        backgroundMusic.start();
    }

    private void toggleBackgroundMusic() {
        if (backgroundMusic != null) backgroundMusic.toggle();
    }

    private void stopBackgroundMusic() {
        BackgroundMusic current = backgroundMusic;
        backgroundMusic = null;
        if (current != null) current.close();
    }

    private void openPlayerDirectory() {
        if (playerHome == null) return;
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(playerHome.toFile());
        } catch (IOException | UnsupportedOperationException e) {
            if (log != null) log.error("Unable to open player updater directory", e);
        }
    }

    private void openArchiveDirectory() {
        Path archive = lastArchiveDirectory;
        if (archive == null) return;
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(archive.toFile());
        } catch (IOException | UnsupportedOperationException e) {
            if (log != null) log.error("Unable to open forced sync archive directory", e);
        }
    }

    private Path resolvePlayerHome(ProjectBinding current) {
        Path configured = Path.of(current.playerHome());
        return configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : arguments.instanceRoot().resolve(configured).toAbsolutePath().normalize();
    }

    private Path resolveBundledCover(ProjectBinding current) {
        if (current.bundledCoverPath() == null || current.bundledCoverPath().isBlank()) return null;
        try {
            Path cover = PathSafety.resolveInside(arguments.instanceRoot(), current.bundledCoverPath());
            return Files.isRegularFile(cover) ? cover : null;
        } catch (Exception e) {
            if (log != null) log.error("Bundled cover path is invalid", e);
            return null;
        }
    }

    private String errorTitle(Throwable error) {
        if (error instanceof BootstrapPermitClient.PermitException) return "启动许可已失效";
        if (!(error instanceof UpdateException update)) return "更新失败";
        return switch (update.code()) {
            case NETWORK_UNAVAILABLE -> "无法连接更新服务";
            case INVALID_SIGNATURE, INVALID_MANIFEST, WRONG_PROJECT, REPLAY_DETECTED -> "发布验证失败";
            case UNSUPPORTED_PLAYER_VERSION -> "更新器版本过旧";
            case GAME_RUNNING -> "请先关闭正在运行的游戏";
            case INSTANCE_BUSY -> "另一个更新任务正在运行";
            case HASH_MISMATCH, DOWNLOAD_FAILED -> "更新文件下载失败";
            case LOCAL_STATE_INVALID -> "本地整合包需要修复";
            case PATH_UNSAFE -> "发布中包含不安全路径";
            case RECOVERY_FAILED, TRANSACTION_FAILED -> "更新事务未能完成";
            case CANCELLED -> "更新已取消";
            default -> "更新失败";
        };
    }
}
