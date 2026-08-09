package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateErrorCode;
import cn.dreamingfish.updater.engine.UpdateException;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;

public final class PlayerApplication extends Application {
    static final String VERSION = "0.1.33";
    static final String BOOTSTRAP_AGENT_VERSION = "0.1.2";

    private PlayerArguments arguments;
    private PlayerController controller;
    private PlayerView view;
    private Stage stage;

    public static void main(String[] args) {
        System.setProperty("prism.lcdtext", "true");
        System.setProperty("prism.allowhidpi", "true");
        launch(args);
    }

    @Override
    public void init() {
        arguments = PlayerArguments.parse(getParameters().getRaw());
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
        stage.setResizable(true);

        view = new PlayerView(stage);
        Scene scene = new Scene(view.root(), 1180, 680, Color.TRANSPARENT);
        String stylesheet = PlayerApplication.class.getResource("player.css").toExternalForm();
        scene.getStylesheets().add(stylesheet);
        stage.setScene(scene);

        controller = new PlayerController(arguments, new JavaFxViewPort(view, stage),
                this::exitApplication);
        view.setCloseAction(() -> controller.requestClose());
        view.setRetryAction(() -> controller.retry());
        view.setContinueLaunchAction(() -> controller.continueLaunch());
        view.setOpenDirectoryAction(() -> controller.openPlayerDirectory());
        view.setOpenArchiveAction(() -> controller.openArchiveDirectory());
        view.setDetailsOpenedAction(() -> controller.keepWindowOpen());
        view.setOpenExternalLinkAction(uri -> controller.openExternalLink(uri));
        view.setLocalModToggleAction((entry, disabled) ->
                controller.changeLocalModPreference(entry, Boolean.TRUE.equals(disabled)));
        view.setRestoreModsAction(() -> controller.restoreLocalModDefaults());
        view.setLocalFileToggleAction((entry, managed) ->
                controller.changeLocalFilePreference(entry, Boolean.TRUE.equals(managed)));
        view.setRestoreFilesAction(() -> controller.restoreLocalFileDefaults());
        view.setPlayerIdentity(arguments.playerName());
        stage.setOnCloseRequest(event -> {
            event.consume();
            controller.requestClose();
        });

        if (arguments.preview()) {
            showPreview();
            return;
        }

        controller.start();
    }

    private void showPreview() {
        ProjectBinding binding = new ProjectBinding(1, "dreamhaven", "http://127.0.0.1:8080", "preview",
                "DreamingFishUpdater", null,
                new Branding("梦屿", "灾变之后，仍有人在这里守望。",
                        "", null, "#2ee8df", "#b06cff"));
        view.setLocalModToggleAction((entry, disabled) -> { });
        view.setRestoreModsAction(() -> { });
        view.setLocalFileToggleAction((entry, managed) -> { });
        view.setRestoreFilesAction(() -> { });
        view.setBranding(binding.fallbackBranding());
        view.setBackground(null);
        view.showPreview();
        view.showLaunchCountdown(15);
        view.appendLog("12:08:41  INFO  已连接到守望梦屿更新服务");
        view.appendLog("12:08:42  INFO  正在下载 mods/dreamingfish-core.jar");
        stage.show();
        stage.centerOnScreen();
        view.playEntrance();
    }

    private void exitApplication() {
        stage.close();
        Platform.exit();
        System.exit(0);
    }

    private static void loadBundledFont() {
        try (InputStream fontStream = PlayerApplication.class
                .getResourceAsStream("fonts/HarmonyOS_Sans_SC_Bold.ttf")) {
            if (fontStream != null) Font.loadFont(fontStream, 12);
        } catch (IOException ignored) {
            // The CSS keeps system-font fallbacks so a font-loading failure is non-fatal.
        }
    }

    static boolean allowsUnverifiedOfflineLaunch(Throwable failure) {
        return failure instanceof UpdateException update
                && update.code() == UpdateErrorCode.NETWORK_UNAVAILABLE;
    }

    static boolean allowsLocalContentOverride(Throwable failure) {
        return failure instanceof UpdateException update
                && update.code() == UpdateErrorCode.LOCAL_CONTENT_CHANGED;
    }

    private static final class JavaFxViewPort implements PlayerViewPort {
        private final PlayerView view;
        private final Stage stage;

        JavaFxViewPort(PlayerView view, Stage stage) {
            this.view = view;
            this.stage = stage;
        }

        @Override
        public void setPlayerIdentity(String name) {
            onFxThread(() -> view.setPlayerIdentity(name));
        }

        @Override
        public void setBranding(Branding branding) {
            onFxThread(() -> view.setBranding(branding));
        }

        @Override
        public void setBackground(Path localCover) {
            onFxThread(() -> view.setBackground(localCover));
        }

        @Override
        public void setLogs(List<String> lines) {
            onFxThread(() -> view.setLogs(lines));
        }

        @Override
        public void setReleaseHistory(ReleaseHistory history) {
            onFxThread(() -> view.setReleaseHistory(history));
        }

        @Override
        public void appendLog(String line) {
            onFxThread(() -> view.appendLog(line));
        }

        @Override
        public void showProgress(ProgressEvent event) {
            onFxThread(() -> view.showProgress(event));
        }

        @Override
        public void showResult(UpdateResult result) {
            onFxThread(() -> view.showResult(result));
        }

        @Override
        public void showUnverifiedOfflineLaunch() {
            onFxThread(view::showUnverifiedOfflineLaunch);
        }

        @Override
        public void showLocalContentOverrideLaunch() {
            onFxThread(view::showLocalContentOverrideLaunch);
        }

        @Override
        public void showError(String title, String detail, boolean allowContinue) {
            onFxThread(() -> view.showError(title, detail, allowContinue));
        }

        @Override
        public void setLocalMods(List<LocalModEntry> mods) {
            onFxThread(() -> view.setLocalMods(mods));
        }

        @Override
        public void setLocalFiles(List<LocalFileEntry> files) {
            onFxThread(() -> view.setLocalFiles(files));
        }

        @Override
        public void showLaunchCountdown(int seconds) {
            onFxThread(() -> view.showLaunchCountdown(seconds));
        }

        @Override
        public void showLaunchKeptOpen() {
            onFxThread(view::showLaunchKeptOpen);
        }

        @Override
        public boolean confirmDialog(DialogTone tone, String title, String heading,
                                     String message, String actionText, String cancelText) {
            PlayerDialog.Tone mapped = switch (tone) {
                case INFO -> PlayerDialog.Tone.INFO;
                case WARNING -> PlayerDialog.Tone.WARNING;
                case DANGER -> PlayerDialog.Tone.DANGER;
            };
            if (Platform.isFxApplicationThread()) {
                return PlayerDialog.confirm(stage, mapped, title, heading, message,
                        actionText, cancelText);
            }
            FutureTask<Boolean> task = new FutureTask<>(() ->
                    PlayerDialog.confirm(stage, mapped, title, heading, message,
                            actionText, cancelText));
            Platform.runLater(task);
            try {
                return task.get();
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void openPlayerDirectory(Path playerHome) {
            onFxThread(() -> openDesktop(playerHome));
        }

        @Override
        public void openArchiveDirectory(Path archiveDirectory) {
            onFxThread(() -> openDesktop(archiveDirectory));
        }

        @Override
        public void openExternalLink(URI uri) {
            onFxThread(() -> {
                try {
                    if (java.awt.Desktop.isDesktopSupported()
                            && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                        java.awt.Desktop.getDesktop().browse(uri);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        @Override
        public void fadeOut(long durationMillis, Runnable finished) {
            onFxThread(() -> view.fadeOut(Duration.millis(durationMillis), finished));
        }

        @Override
        public void ready() {
            onFxThread(() -> {
                stage.show();
                stage.centerOnScreen();
                view.playEntrance();
            });
        }

        private static void openDesktop(Path path) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(path.toFile());
                }
            } catch (Exception ignored) {
            }
        }

        private static void onFxThread(Runnable action) {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        }
    }
}
