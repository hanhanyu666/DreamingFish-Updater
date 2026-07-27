package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.CancellationToken;
import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.ProgressListener;
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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerApplication extends Application {
    static final String VERSION = "0.1.5";
    static final String BOOTSTRAP_AGENT_VERSION = "0.1.2";
    private static final int AUTO_CLOSE_SECONDS = 15;

    private final JsonCodec json = new JsonCodec();
    private final AtomicBoolean cancelled = new AtomicBoolean();
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
            Platform.runLater(this::completeFirstRunOrUpdate);
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

    private void loadConfiguration() throws IOException {
        binding = json.read(arguments.bindingFile(), ProjectBinding.class);
        ManifestValidator.validateBinding(binding);
        playerHome = resolvePlayerHome(binding);
        Files.createDirectories(playerHome);
        log = new PlayerLog(playerHome);
        log.setListener(line -> Platform.runLater(() -> view.appendLog(line)));
        view.setBranding(binding.fallbackBranding());
        view.setBackground(resolveBundledCover(binding));
        log.info("Player updater started for project " + binding.projectId());
    }

    private void completeFirstRunOrUpdate() {
        Path marker = playerHome.resolve("state/first-run-complete");
        if (Files.isRegularFile(marker)) {
            startUpdate();
            return;
        }

        ButtonType keep = new ButtonType("保留推荐位置", ButtonBar.ButtonData.OK_DONE);
        ButtonType choose = new ButtonType("选择其他位置", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("取消启动", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "更新器默认保存在当前 Minecraft 实例内。这个位置适合随整合包一起移动，也可以改到你选择的目录。",
                keep, choose, cancel);
        prompt.initOwner(stage);
        prompt.setTitle("首次启动");
        prompt.setHeaderText("更新器保存位置");
        Optional<ButtonType> selected = prompt.showAndWait();
        if (selected.isEmpty() || selected.get() == cancel) {
            closeAndDeny("玩家取消了首次启动");
        } else if (selected.get() == choose) {
            choosePlayerHome();
        } else {
            try {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "1\n", StandardCharsets.US_ASCII);
                startUpdate();
            } catch (IOException e) {
                showFailure("无法保存首次启动设置", e);
            }
        }
    }

    private void choosePlayerHome() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择更新器保存位置");
        Path parent = playerHome.getParent();
        if (parent != null && Files.isDirectory(parent)) chooser.setInitialDirectory(parent.toFile());
        var selected = chooser.showDialog(stage);
        if (selected == null) {
            completeFirstRunOrUpdate();
            return;
        }
        Path target = selected.toPath().toAbsolutePath().normalize();
        if (target.equals(playerHome)) {
            try {
                Path marker = playerHome.resolve("state/first-run-complete");
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "1\n", StandardCharsets.US_ASCII);
                startUpdate();
            } catch (IOException e) {
                showFailure("无法保存首次启动设置", e);
            }
            return;
        }
        working = true;
        view.showProgress(new ProgressEvent(UpdateStage.PREPARING,
                "正在迁移更新器", target.toString(), 0, 0));
        Thread.ofVirtual().name("player-home-relocation").start(() -> {
            try {
                ProjectBinding relocated = new PlayerHomeRelocator().relocate(playerHome, target,
                        arguments.instanceRoot(), arguments.bindingFile(), binding);
                binding = relocated;
                playerHome = target;
                log.info("Player updater relocated to " + target);
                working = false;
                Platform.runLater(this::startUpdate);
            } catch (Exception e) {
                working = false;
                Platform.runLater(() -> showFailure("无法移动更新器", e));
            }
        });
    }

    private void startUpdate() {
        if (arguments.preview() || working || launchPermitted) return;
        cancelled.set(false);
        working = true;
        view.showProgress(new ProgressEvent(UpdateStage.CHECKING,
                "正在连接更新服务", null, 0, 0));
        ProgressListener progress = throttledProgress();
        CancellationToken cancellation = cancelled::get;
        UpdateRequest request = new UpdateRequest(arguments.instanceRoot(), playerHome, binding,
                VERSION, Set.of(ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC),
                null, null, null, cancellation);

        Thread.ofVirtual().name("startup-update").start(() -> {
            try {
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

                UpdateResult result = new UpdateEngine().update(request, progress);
                permitClient.allow();
                launchPermitted = true;
                working = false;
                log.info("Launch permission granted for release " + result.release().releaseId());
                if (!result.archivedFiles().isEmpty()) {
                    log.info("Remote management forced sync for directories: "
                            + String.join(", ", result.release().forcedSyncDirectories()));
                    log.info("Archived " + result.archivedFiles().size()
                            + " local files to " + result.archiveDirectory());
                    result.archivedFiles().forEach(path -> log.info("Archived local file: " + path));
                }
                Platform.runLater(() -> finishSuccessfully(result));
            } catch (Exception e) {
                working = false;
                log.error("Update failed", e);
                Platform.runLater(() -> showFailure(errorTitle(e), e));
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
