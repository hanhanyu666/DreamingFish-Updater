package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateOutcome;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.engine.UpdateStage;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import cn.dreamingfish.updater.protocol.ReleaseHistoryEntry;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.Alert;
import javafx.scene.effect.DisplacementMap;
import javafx.scene.effect.Effect;
import javafx.scene.effect.FloatMap;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.css.PseudoClass;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

final class PlayerView {
    private static final PseudoClass MUSIC_PLAYING = PseudoClass.getPseudoClass("playing");
    private static final PseudoClass NAV_SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass DRAWER_SELECTED = PseudoClass.getPseudoClass("selected");
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter
            .ofPattern("yyyy.MM.dd  HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Map<UpdateStage, String> STAGE_NAMES = Map.of(
            UpdateStage.RECOVERING, "正在恢复更新",
            UpdateStage.CHECKING, "正在检查更新",
            UpdateStage.SCANNING, "正在校验文件",
            UpdateStage.DOWNLOADING, "正在下载更新",
            UpdateStage.PREPARING, "正在准备安装",
            UpdateStage.INSTALLING, "正在安装更新",
            UpdateStage.VERIFYING, "正在完成校验",
            UpdateStage.COMPLETE, "准备完成",
            UpdateStage.OFFLINE, "离线启动"
    );

    private enum DrawerMode {
        UPDATE("本次更新"),
        HISTORY("更新记录"),
        LOGS("运行记录"),
        FILES("本地文件");

        private final String label;

        DrawerMode(String label) {
            this.label = label;
        }
    }

    private enum LocalManagementMode {
        FILES,
        MODS
    }

    private record UpdateDetailRow(String operation, String path) {
    }

    private enum Page {
        HOME("主页"),
        NEWS("新闻"),
        DREAM_HAVEN("守望梦屿"),
        ABOUT("关于");

        private final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private final StackPane root = new StackPane();
    private final Stage stageWindow;
    private final ImageView background = new ImageView();
    private final ImageView refractedBackground = new ImageView();
    private final AnchorPane canvas = new AnchorPane();
    private final Region glassSweep = new Region();
    private final StackPane contentPageLayer = new StackPane();
    private final Map<Page, Button> navigationButtons = new EnumMap<>(Page.class);
    private final Map<Page, Node> contentPages = new EnumMap<>(Page.class);
    private final Label productName = new Label();
    private final Label subtitle = new Label();
    private final Label playerName = new Label("未识别玩家");
    private final Label updaterInfo = new Label("DreamingFish Updater " + PlayerApplication.VERSION);
    private final StackPane launchNoticeLayer = new StackPane();
    private final HBox launchNotice = new HBox(9);
    private final Label launchNoticeText = new Label();
    private final Label stage = new Label("正在启动更新器");
    private final Label percent = new Label("--");
    private final Label currentPath = new Label("准备本地环境");
    private final Label byteSummary = new Label("-- / --");
    private final ProgressBar progress = new ProgressBar(-1);
    private final Label unmanaged = new Label();
    private final Button openArchive = new Button("打开备份目录");
    private final HBox actionRow = new HBox(10);
    private final Button retry = new Button("重试");
    private final Button openDirectory = new Button("打开目录");
    private final Button changelogToggle = new Button("更新记录  ›");
    private final Button logToggle = new Button("运行记录  ›");
    private final Button localFilesToggle = new Button("本地文件  ›");
    private final VBox detailsDrawer = new VBox(12);
    private final Label drawerTitle = new Label();
    private final Map<DrawerMode, Button> drawerTabs = new EnumMap<>(DrawerMode.class);
    private final StackPane drawerContent = new StackPane();
    private final Label updateDetailsVersion = new Label("尚未完成更新");
    private final Label updateDetailsChangelog = new Label(
            "完成更新后，可在这里查看本次修改的全部文件。");
    private final Label updateDetailsCounts = new Label("本次暂无文件变更");
    private final ListView<UpdateDetailRow> updateDetailsList = new ListView<>();
    private final VBox updateDetailsPage = new VBox(10);
    private final VBox historyList = new VBox();
    private final ScrollPane historyScroll = new ScrollPane(historyList);
    private final ListView<String> logs = new ListView<>();
    private final VBox modList = new VBox();
    private final ScrollPane modScroll = new ScrollPane(modList);
    private final TextField modSearch = new TextField();
    private final Label modEmpty = new Label("没有检测到模组");
    private final Label modWarning = new Label("停用必要模组可能导致游戏崩溃或无法连接服务器。更改会在本次更新完成前重新校验；游戏已经启动时则从下次启动生效。");
    private final Button restoreMods = new Button("恢复整合包默认");
    private final VBox modPage = new VBox(12);
    private final TreeView<LocalFileEntry> localFileTree = new TreeView<>();
    private final Label localFileEmpty = new Label();
    private final StackPane localFileTreePane = new StackPane(localFileTree, localFileEmpty);
    private final TextField localFileSearch = new TextField();
    private final Label localFileWarning = new Label(
            "关闭管理后，普通更新不会再安装、覆盖或删除该文件。服务器强制同步目录不能在本机关闭。");
    private final Button restoreFiles = new Button("恢复全部管理");
    private final VBox localFilePage = new VBox(12);
    private final StackPane localManagementContent = new StackPane();
    private final VBox localManagementPage = new VBox(12);
    private final ToggleButton fileManagementMode = new ToggleButton("管理范围");
    private final ToggleButton modManagementMode = new ToggleButton("模组启停");
    private final VBox updateSummary = new VBox(5);
    private final Label updateSummaryVersion = new Label();
    private final Label updateSummaryChangelog = new Label();
    private final Label updateSummaryCounts = new Label();
    private final Tooltip updateFileTooltip = new Tooltip();
    private final Button close = new Button();
    private final Button minimize = new Button();
    private final Button music = new Button();
    private HBox titleBar;
    private VBox identityPane;
    private HBox playerIdentityPane;
    private VBox progressPane;
    private VBox updateArea;
    private DrawerMode drawerMode;
    private Page currentPage = Page.HOME;
    private List<LocalModEntry> localMods = List.of();
    private List<LocalFileEntry> localFiles = List.of();
    private int locallyDisabledMods;
    private int locallyExcludedFiles;
    private ReleaseHistory releaseHistory;
    private boolean entrancePlayed;
    private boolean launchNoticeShown;
    private Runnable closeAction = () -> { };
    private Runnable retryAction = () -> { };
    private Runnable openDirectoryAction = () -> { };
    private Runnable openArchiveAction = () -> { };
    private Runnable detailsOpenedAction = () -> { };
    private Runnable musicToggleAction = () -> { };
    private BiConsumer<LocalModEntry, Boolean> localModToggleAction = (entry, disabled) -> { };
    private Runnable restoreModsAction = () -> { };
    private BiConsumer<LocalFileEntry, Boolean> localFileToggleAction = (entry, managed) -> { };
    private Runnable restoreFilesAction = () -> { };

    PlayerView(Stage stageWindow) {
        this.stageWindow = stageWindow;
        build(stageWindow);
    }

    Parent root() {
        return root;
    }

    void setBranding(Branding branding) {
        Branding display = displayBranding(branding);
        productName.setText(display.productName());
        subtitle.setText(display.subtitle());
        String accent = validColor(display.accentColor(), "#2ee8df");
        String secondary = validColor(display.secondaryAccentColor(), "#b06cff");
        root.setStyle("-dfs-accent: " + accent + "; -dfs-secondary: " + secondary + ";");
    }

    static Branding displayBranding(Branding branding) {
        Branding defaults = Branding.empty();
        if (branding == null || unusableText(branding.productName())) {
            return defaults;
        }
        String subtitle = unusableText(branding.subtitle()) ? defaults.subtitle() : branding.subtitle();
        return new Branding(branding.productName(), subtitle, branding.serverAddress(),
                branding.coverObject(), branding.accentColor(), branding.secondaryAccentColor());
    }

    private static boolean unusableText(String value) {
        return value == null || value.isBlank() || value.indexOf('\uFFFD') >= 0;
    }

    void setPlayerIdentity(String name) {
        String displayName = name == null || name.isBlank() ? "未识别玩家" : name;
        playerName.setText(displayName);
    }

    void setBackground(Path localCover) {
        Image image = null;
        if (localCover != null) {
            try {
                image = new Image(localCover.toUri().toString(), false);
                if (image.isError()) image = null;
            } catch (RuntimeException ignored) {
            }
        }
        if (image == null) {
            InputStream resource = PlayerView.class.getResourceAsStream("images/hero-dreamhaven.png");
            if (resource != null) image = new Image(resource);
        }
        background.setImage(image);
        refractedBackground.setImage(image);
    }

    void showProgress(ProgressEvent event) {
        stage.setText(STAGE_NAMES.getOrDefault(event.stage(), "正在处理更新"));
        currentPath.setText(displayPath(event.currentPath(), event.message()));
        if (event.totalBytes() > 0) {
            progress.setProgress(event.fraction());
            percent.setText(Math.round(event.fraction() * 100) + "%");
            byteSummary.setText(formatAmount(event.completedBytes()) + " / " + formatAmount(event.totalBytes()));
        } else {
            progress.setProgress(-1);
            percent.setText("--");
            byteSummary.setText("正在计算变更");
        }
        setWorking(true);
    }

    void showResult(UpdateResult result) {
        setBranding(result.release().branding());
        showUpdateSummary(result);
        progress.setProgress(1);
        percent.setText("100%");
        byteSummary.setText(result.downloadedBytes() > 0
                ? "已下载 " + formatBytes(result.downloadedBytes())
                : "本地文件已验证");
        if (result.outcome() == UpdateOutcome.OFFLINE_ALLOWED) {
            stage.setText("已使用离线许可");
            currentPath.setText("正在使用最近一次完整验证的版本");
        } else if (result.outcome() == UpdateOutcome.UP_TO_DATE) {
            stage.setText("已是最新版本");
            currentPath.setText("Minecraft 正在继续启动");
        } else {
            stage.setText("更新已经完成");
            currentPath.setText("Minecraft 正在继续启动");
        }
        showFileNotices(result);
        setWorking(false);
        actionRow.setVisible(false);
        actionRow.setManaged(false);
    }

    void showError(String title, String detail) {
        stage.setText(title);
        currentPath.setText(detail);
        progress.setProgress(0);
        percent.setText("!");
        byteSummary.setText("Minecraft 启动已暂停");
        setWorking(false);
        actionRow.setManaged(true);
        actionRow.setVisible(true);
    }

    void appendLog(String line) {
        logs.getItems().add(line);
        logs.scrollTo(Math.max(0, logs.getItems().size() - 1));
    }

    void setLogs(List<String> lines) {
        logs.getItems().setAll(lines == null ? List.of() : lines);
        if (!logs.getItems().isEmpty()) logs.scrollTo(logs.getItems().size() - 1);
    }

    void setReleaseHistory(ReleaseHistory history) {
        releaseHistory = history;
        historyList.getChildren().clear();
        if (history == null || history.releases().isEmpty()) {
            historyList.getChildren().add(emptyDrawerMessage("还没有可显示的发布记录"));
            return;
        }
        for (int index = 0; index < history.releases().size(); index++) {
            historyList.getChildren().add(createHistoryEntry(history.releases().get(index), index == 0));
            if (index + 1 < history.releases().size()) {
                Region divider = new Region();
                divider.getStyleClass().add("drawer-divider");
                divider.setMinHeight(1);
                historyList.getChildren().add(divider);
            }
        }
    }

    void setLocalMods(List<LocalModEntry> mods) {
        localMods = mods == null ? List.of() : List.copyOf(mods);
        locallyDisabledMods = (int) localMods.stream()
                .filter(entry -> entry.disabled() && !entry.forced()).count();
        rebuildModList();
    }

    void setLocalFiles(List<LocalFileEntry> files) {
        localFiles = files == null ? List.of() : List.copyOf(files);
        locallyExcludedFiles = (int) localFiles.stream()
                .filter(entry -> !entry.directory() && entry.present()
                        && !entry.managed() && !entry.forced())
                .count();
        rebuildLocalFileTree();
    }

    void showPreview() {
        stage.setText("正在下载更新");
        currentPath.setText("mods/dreamingfish-core.jar");
        progress.setProgress(0.68);
        percent.setText("68%");
        byteSummary.setText("184 MB / 271 MB");
        unmanaged.setText("检测到 2 个玩家自选模组  ›");
        unmanaged.setManaged(true);
        unmanaged.setVisible(true);
        updateSummaryVersion.setText("版本 1.20.1-r12");
        updateSummaryChangelog.setText("新增梦屿群系探索内容");
        updateSummaryCounts.setText("安装 / 更新 6 项  ·  删除 1 项  ·  本地停用 2 项");
        updateFileTooltip.setText("""
                版本 1.20.1-r12 · 文件变更

                安装 / 更新
                  mods/dreamingfish-core.jar
                  mods/dreamingfish-world.jar
                  config/dreamingfish/client.toml

                删除
                  mods/legacy-renderer.jar
                """.strip());
        setUpdateDetails("1.20.1-r12", "新增梦屿群系探索内容",
                List.of(Path.of("mods/dreamingfish-core.jar"),
                        Path.of("mods/dreamingfish-world.jar"),
                        Path.of("config/dreamingfish/client.toml")),
                List.of(Path.of("mods/legacy-renderer.jar")), List.of());
        setLocalFiles(List.of(
                new LocalFileEntry("config", "config", true,
                        false, null, true, true, false, null, 2),
                new LocalFileEntry("config/dreamingfish", "dreamingfish", true,
                        true, null, false, true, false, null, 1),
                new LocalFileEntry("config/dreamingfish/client.toml", "client.toml", false,
                        false, "config/dreamingfish", false, true, false,
                        FilePolicy.ENFORCED, 0),
                new LocalFileEntry("config/voice.toml", "voice.toml", false,
                        false, null, false, true, false, FilePolicy.ENFORCED, 0),
                new LocalFileEntry("mods", "mods", true,
                        false, null, false, true, false, null, 2),
                new LocalFileEntry("mods/dreamingfish-core.jar", "DreamingFish Core", false,
                        false, null, false, true, false, FilePolicy.ENFORCED, 0),
                new LocalFileEntry("mods/dreamingfish-world.jar", "DreamingFish World", false,
                        false, null, false, true, false, FilePolicy.ENFORCED, 0),
                new LocalFileEntry("defaultconfigs", "defaultconfigs", true,
                        false, null, false, true, true, null, 1),
                new LocalFileEntry("defaultconfigs/server.toml", "server.toml", false,
                        false, null, false, true, true, FilePolicy.ENFORCED, 0)));
        setLocalMods(List.of(
                new LocalModEntry("component:renderer", "旧版渲染优化",
                        "mods/legacy-renderer.jar", "renderer", true, true, false, false),
                new LocalModEntry("component:dreamingfish", "DreamingFish Core",
                        "mods/dreamingfish-core.jar", "dreamingfish", true, false, true, false)));
        updateSummary.setManaged(true);
        updateSummary.setVisible(true);
    }

    void showLaunchCountdown(int seconds) {
        launchNoticeText.setText("Minecraft 已开始启动 · " + seconds + " 秒后自动关闭");
        showLaunchNotice();
    }

    void showLaunchKeptOpen() {
        launchNoticeText.setText("Minecraft 已开始启动 · 窗口将保持打开");
        showLaunchNotice();
    }

    void setCloseAction(Runnable action) {
        closeAction = action;
    }

    void setRetryAction(Runnable action) {
        retryAction = action;
    }

    void setOpenDirectoryAction(Runnable action) {
        openDirectoryAction = action;
    }

    void setOpenArchiveAction(Runnable action) {
        openArchiveAction = action;
    }

    void setDetailsOpenedAction(Runnable action) {
        detailsOpenedAction = action;
    }

    void setMusicToggleAction(Runnable action) {
        musicToggleAction = action;
    }

    void setLocalModToggleAction(BiConsumer<LocalModEntry, Boolean> action) {
        localModToggleAction = action == null ? (entry, disabled) -> { } : action;
    }

    void setRestoreModsAction(Runnable action) {
        restoreModsAction = action == null ? () -> { } : action;
    }

    void setLocalFileToggleAction(BiConsumer<LocalFileEntry, Boolean> action) {
        localFileToggleAction = action == null ? (entry, managed) -> { } : action;
    }

    void setRestoreFilesAction(Runnable action) {
        restoreFilesAction = action == null ? () -> { } : action;
    }

    void setMusicState(BackgroundMusic.State state) {
        boolean available = state != BackgroundMusic.State.UNAVAILABLE;
        boolean playing = state == BackgroundMusic.State.PLAYING;
        music.setDisable(!available);
        installMusicGlyph(music, playing);
        music.pseudoClassStateChanged(MUSIC_PLAYING, playing);
        String description = available
                ? (playing ? "暂停背景音乐" : "播放背景音乐")
                : "背景音乐不可用";
        music.setTooltip(new Tooltip(description));
        music.setAccessibleText(description);
    }

    void fadeOut(Duration duration, Runnable finished) {
        FadeTransition fade = new FadeTransition(duration, root);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setOnFinished(event -> finished.run());
        fade.play();
    }

    void playEntrance() {
        if (entrancePlayed) return;
        entrancePlayed = true;

        root.setOpacity(0);
        root.setScaleX(0.985);
        root.setScaleY(0.985);
        background.setScaleX(1.045);
        background.setScaleY(1.045);
        refractedBackground.setScaleX(1.055);
        refractedBackground.setScaleY(1.055);

        FadeTransition rootFade = new FadeTransition(Duration.millis(460), root);
        rootFade.setFromValue(0);
        rootFade.setToValue(1);
        ScaleTransition rootScale = new ScaleTransition(Duration.millis(720), root);
        rootScale.setFromX(0.985);
        rootScale.setFromY(0.985);
        rootScale.setToX(1);
        rootScale.setToY(1);
        ScaleTransition backgroundScale = new ScaleTransition(Duration.millis(1100), background);
        backgroundScale.setToX(1);
        backgroundScale.setToY(1);
        ScaleTransition refractionScale = new ScaleTransition(Duration.millis(1200), refractedBackground);
        refractionScale.setToX(1);
        refractionScale.setToY(1);

        glassSweep.setOpacity(0);
        glassSweep.setTranslateX(-280);
        TranslateTransition sweepMove = new TranslateTransition(Duration.millis(1050), glassSweep);
        sweepMove.setDelay(Duration.millis(130));
        sweepMove.setFromX(-280);
        sweepMove.setToX(Math.max(1280, root.getWidth() + 260));
        FadeTransition sweepIn = new FadeTransition(Duration.millis(180), glassSweep);
        sweepIn.setFromValue(0);
        sweepIn.setToValue(0.62);
        FadeTransition sweepOut = new FadeTransition(Duration.millis(520), glassSweep);
        sweepOut.setFromValue(0.62);
        sweepOut.setToValue(0);
        SequentialTransition sweepOpacity = new SequentialTransition(
                new PauseTransition(Duration.millis(130)), sweepIn, sweepOut);

        ParallelTransition entrance = new ParallelTransition(rootFade, rootScale,
                backgroundScale, refractionScale,
                reveal(titleBar, 0, -10, 70),
                reveal(identityPane, -22, 6, 130),
                reveal(playerIdentityPane, -14, 10, 230),
                reveal(updateArea, 18, 14, 190),
                reveal(updaterInfo, 0, 8, 310),
                sweepMove, sweepOpacity);
        entrance.setInterpolator(Interpolator.SPLINE(0.18, 0.78, 0.20, 1.0));
        entrance.play();
    }

    private void build(Stage stageWindow) {
        root.getStyleClass().add("app-root");
        root.setPrefSize(1180, 680);

        background.setSmooth(true);
        background.fitWidthProperty().bind(root.widthProperty());
        background.fitHeightProperty().bind(root.heightProperty());
        background.setPreserveRatio(false);

        refractedBackground.setSmooth(true);
        refractedBackground.fitWidthProperty().bind(root.widthProperty());
        refractedBackground.fitHeightProperty().bind(root.heightProperty());
        refractedBackground.setPreserveRatio(false);
        refractedBackground.setOpacity(0.20);
        refractedBackground.setEffect(createRefractionEffect());
        refractedBackground.setMouseTransparent(true);

        Region shade = new Region();
        shade.getStyleClass().add("image-shade");
        StackPane.setAlignment(shade, Pos.CENTER);

        Region glassWash = new Region();
        glassWash.getStyleClass().add("liquid-glass-wash");
        glassWash.setMouseTransparent(true);
        Region glassRim = new Region();
        glassRim.getStyleClass().add("liquid-glass-rim");
        glassRim.setMouseTransparent(true);
        glassSweep.getStyleClass().add("liquid-glass-sweep");
        glassSweep.setMinWidth(190);
        glassSweep.setPrefWidth(190);
        glassSweep.setMaxWidth(190);
        glassSweep.setMouseTransparent(true);
        StackPane.setAlignment(glassSweep, Pos.CENTER_LEFT);

        titleBar = createTitleBar(stageWindow);
        identityPane = createIdentity();
        playerIdentityPane = createPlayerIdentity();
        progressPane = createProgressRegion();
        updateArea = createUpdateArea();
        buildContentPages();
        buildLaunchNotice();
        buildDetailsDrawer();

        AnchorPane.setTopAnchor(titleBar, 0.0);
        AnchorPane.setLeftAnchor(titleBar, 0.0);
        AnchorPane.setRightAnchor(titleBar, 0.0);
        AnchorPane.setLeftAnchor(identityPane, 56.0);
        AnchorPane.setTopAnchor(identityPane, 154.0);
        AnchorPane.setLeftAnchor(playerIdentityPane, 56.0);
        AnchorPane.setBottomAnchor(playerIdentityPane, 38.0);
        AnchorPane.setRightAnchor(updateArea, 50.0);
        AnchorPane.setBottomAnchor(updateArea, 52.0);
        AnchorPane.setTopAnchor(contentPageLayer, 52.0);
        AnchorPane.setLeftAnchor(contentPageLayer, 0.0);
        AnchorPane.setRightAnchor(contentPageLayer, 0.0);
        AnchorPane.setBottomAnchor(contentPageLayer, 0.0);
        updaterInfo.getStyleClass().add("updater-info");
        AnchorPane.setRightAnchor(updaterInfo, 52.0);
        AnchorPane.setBottomAnchor(updaterInfo, 17.0);
        AnchorPane.setTopAnchor(launchNoticeLayer, 67.0);
        AnchorPane.setLeftAnchor(launchNoticeLayer, 0.0);
        AnchorPane.setRightAnchor(launchNoticeLayer, 0.0);
        AnchorPane.setTopAnchor(detailsDrawer, 52.0);
        AnchorPane.setRightAnchor(detailsDrawer, 0.0);
        AnchorPane.setBottomAnchor(detailsDrawer, 0.0);

        Region resizeGrip = new Region();
        resizeGrip.setPrefSize(18, 18);
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        installResize(stageWindow, resizeGrip);
        AnchorPane.setRightAnchor(resizeGrip, 0.0);
        AnchorPane.setBottomAnchor(resizeGrip, 0.0);

        canvas.getChildren().addAll(identityPane, playerIdentityPane, updateArea,
                updaterInfo, contentPageLayer, launchNoticeLayer, detailsDrawer, titleBar, resizeGrip);
        root.getChildren().addAll(background, refractedBackground, shade, glassWash,
                canvas, glassRim, glassSweep);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        clip.setArcWidth(58);
        clip.setArcHeight(58);
        root.setClip(clip);

        minimize.setOnAction(event -> stageWindow.setIconified(true));
        close.setOnAction(event -> closeAction.run());
        music.setOnAction(event -> musicToggleAction.run());
        retry.setOnAction(event -> retryAction.run());
        openDirectory.setOnAction(event -> openDirectoryAction.run());
        openArchive.setOnAction(event -> openArchiveAction.run());
        changelogToggle.setOnAction(event -> toggleDrawer(DrawerMode.HISTORY));
        logToggle.setOnAction(event -> toggleDrawer(DrawerMode.LOGS));
        localFilesToggle.setOnAction(event -> toggleDrawer(DrawerMode.FILES));
    }

    private HBox createTitleBar(Stage stageWindow) {
        HBox bar = new HBox();
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 12, 0, 24));
        bar.setPrefHeight(52);

        Label chineseBrand = new Label("梦鱼服");
        chineseBrand.getStyleClass().add("brand-chinese");
        Label dreaming = new Label("Dreaming");
        dreaming.getStyleClass().add("brand-dreaming");
        Label fish = new Label("Fish");
        fish.getStyleClass().add("brand-fish");
        HBox englishBrand = new HBox(0, dreaming, fish);
        englishBrand.setAlignment(Pos.CENTER_LEFT);
        HBox brand = new HBox(9, chineseBrand, englishBrand);
        brand.getStyleClass().add("top-brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setAccessibleText("梦鱼服 DreamingFish");

        HBox navigation = new HBox(24);
        navigation.getStyleClass().add("top-navigation");
        navigation.setAlignment(Pos.CENTER);
        for (Page page : Page.values()) {
            Button button = new Button(page.label);
            button.getStyleClass().add("top-nav-button");
            button.pseudoClassStateChanged(NAV_SELECTED, page == currentPage);
            button.setAccessibleText("切换到" + page.label);
            button.setOnAction(event -> showPage(page));
            navigationButtons.put(page, button);
            navigation.getChildren().add(button);
        }
        HBox.setMargin(navigation, new Insets(0, 0, 0, 44));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        music.getStyleClass().addAll("window-button", "music-button");
        installMusicGlyph(music, false);
        minimize.getStyleClass().addAll("window-button", "minimize-button");
        close.getStyleClass().addAll("window-button", "close-button");
        music.setDisable(true);
        music.setTooltip(new Tooltip("正在载入背景音乐"));
        music.setAccessibleText("正在载入背景音乐");
        installMinimizeGlyph(minimize);
        installCloseGlyph(close);
        minimize.setTooltip(new Tooltip("最小化"));
        close.setTooltip(new Tooltip("关闭"));
        minimize.setAccessibleText("最小化");
        close.setAccessibleText("关闭");
        bar.getChildren().addAll(brand, navigation, spacer, music, minimize, close);
        installWindowDrag(stageWindow, bar);
        return bar;
    }

    private VBox createIdentity() {
        VBox box = new VBox(7);
        box.setPrefWidth(390);
        box.setMaxWidth(390);
        Label welcome = new Label("欢迎来到");
        welcome.getStyleClass().add("welcome-title");
        productName.getStyleClass().add("product-name");
        productName.setWrapText(true);
        productName.setMaxWidth(390);
        subtitle.getStyleClass().add("subtitle");
        subtitle.setWrapText(true);
        VBox.setMargin(subtitle, new Insets(9, 0, 0, 1));
        box.getChildren().addAll(welcome, productName, subtitle);
        return box;
    }

    private HBox createPlayerIdentity() {
        HBox box = new HBox(11);
        box.getStyleClass().add("player-identity");
        box.setAlignment(Pos.CENTER_LEFT);
        Region accent = new Region();
        accent.getStyleClass().add("player-accent");
        accent.setMinSize(3, 34);
        accent.setPrefSize(3, 34);
        accent.setMaxSize(3, 34);
        Label caption = new Label("当前玩家");
        caption.getStyleClass().add("player-caption");
        playerName.getStyleClass().add("player-name");
        VBox labels = new VBox(2, caption, playerName);
        box.getChildren().addAll(accent, labels);
        return box;
    }

    private VBox createProgressRegion() {
        VBox box = new VBox(9);
        box.getStyleClass().add("progress-region");
        box.setPrefWidth(430);
        box.setMinWidth(390);
        box.setMaxWidth(430);
        box.setMinHeight(190);

        HBox heading = new HBox(12);
        heading.setAlignment(Pos.CENTER_LEFT);
        stage.getStyleClass().add("stage-label");
        percent.getStyleClass().add("percent-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        heading.getChildren().addAll(stage, spacer, percent);

        currentPath.getStyleClass().add("current-path");
        currentPath.setMaxWidth(Double.MAX_VALUE);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("update-progress");

        HBox summary = new HBox(12);
        summary.setAlignment(Pos.CENTER_LEFT);
        byteSummary.getStyleClass().add("byte-summary");
        Region summarySpacer = new Region();
        HBox.setHgrow(summarySpacer, Priority.ALWAYS);
        changelogToggle.getStyleClass().add("link-button");
        logToggle.getStyleClass().add("link-button");
        localFilesToggle.getStyleClass().add("link-button");
        summary.getChildren().addAll(byteSummary, summarySpacer,
                changelogToggle, logToggle, localFilesToggle);

        unmanaged.getStyleClass().add("unmanaged-notice");
        unmanaged.setVisible(false);
        unmanaged.setManaged(false);
        unmanaged.setWrapText(true);
        openArchive.getStyleClass().add("archive-button");
        openArchive.setVisible(false);
        openArchive.setManaged(false);

        actionRow.setAlignment(Pos.CENTER_RIGHT);
        retry.getStyleClass().add("primary-button");
        openDirectory.getStyleClass().add("secondary-button");
        actionRow.getChildren().addAll(openDirectory, retry);
        actionRow.setVisible(false);
        actionRow.setManaged(false);

        box.getChildren().addAll(heading, currentPath, progress, summary,
                unmanaged, openArchive, actionRow);
        return box;
    }

    private VBox createUpdateArea() {
        updateSummary.getStyleClass().add("update-summary");
        updateSummary.setPrefWidth(430);
        updateSummary.setMinWidth(390);
        updateSummary.setMaxWidth(430);
        updateSummary.setVisible(false);
        updateSummary.setManaged(false);
        updateSummary.setAccessibleText("本次更新摘要，点击打开完整文件变更");
        updateSummary.setCursor(Cursor.HAND);
        updateSummary.setOnMouseEntered(event -> showUpdateFileTooltip());
        updateSummary.setOnMouseExited(event -> updateFileTooltip.hide());
        updateSummary.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            updateFileTooltip.hide();
            toggleDrawer(DrawerMode.UPDATE);
        });
        updateSummaryVersion.getStyleClass().add("update-summary-version");
        updateSummaryChangelog.getStyleClass().add("update-summary-changelog");
        updateSummaryChangelog.setWrapText(true);
        updateSummaryChangelog.setMaxWidth(Double.MAX_VALUE);
        updateSummaryCounts.getStyleClass().add("update-summary-counts");
        updateSummaryCounts.setWrapText(true);
        updateFileTooltip.getStyleClass().add("update-file-tooltip");
        updateFileTooltip.setAutoFix(true);
        updateFileTooltip.setAutoHide(true);
        updateFileTooltip.setWrapText(true);
        updateFileTooltip.setMaxWidth(520);
        updateSummary.getChildren().addAll(
                updateSummaryVersion, updateSummaryChangelog, updateSummaryCounts);

        VBox area = new VBox(10, updateSummary, progressPane);
        area.setPrefWidth(430);
        area.setMinWidth(390);
        area.setMaxWidth(430);
        return area;
    }

    private void showUpdateFileTooltip() {
        Bounds summaryBounds = updateSummary.localToScreen(updateSummary.getBoundsInLocal());
        if (summaryBounds == null || updateFileTooltip.getText().isBlank()) return;
        double availableWidth = summaryBounds.getMinX() - stageWindow.getX() - 28;
        double tooltipWidth = Math.min(500, Math.max(320, availableWidth));
        updateFileTooltip.setPrefWidth(tooltipWidth);
        updateFileTooltip.setMaxWidth(tooltipWidth);
        double x = Math.max(stageWindow.getX() + 14,
                summaryBounds.getMinX() - tooltipWidth - 14);
        updateFileTooltip.show(updateSummary, x, summaryBounds.getMinY());
    }

    private void buildContentPages() {
        contentPageLayer.getStyleClass().add("content-page-layer");
        contentPages.put(Page.NEWS, createNewsPage());
        contentPages.put(Page.DREAM_HAVEN, createDreamHavenPage());
        contentPages.put(Page.ABOUT, createAboutPage());
        contentPageLayer.getChildren().addAll(contentPages.values());
        for (Node page : contentPages.values()) {
            page.setManaged(false);
            page.setVisible(false);
        }
        contentPageLayer.setManaged(false);
        contentPageLayer.setVisible(false);
    }

    private Node createNewsPage() {
        VBox page = createPageBody();
        page.getChildren().addAll(
                pageLabel("DREAMINGFISH NEWS  ·  2026.07.28", "page-eyebrow"),
                pageLabel("来自另一维度的求助", "page-title"),
                pageLabel("守望梦屿 · 建筑先行服现已开启", "page-lead"),
                pageDivider(),
                pageLabel("公元 5060 年，人类首次收到来自宇宙深处的信号。破译结果只有一个字：\"助\"。多年后，第二组信号抵达，画面中是一座染血的小镇，以及在街道上蹒跚前行的类人生物。", "page-copy"),
                pageLabel("DingDua 大学那位曾破译信号的学者认定，这不是外星文明，而是来自平行宇宙地球的求助。另一个世界正在爆发丧尸灾难，NVSV 航天局因此发出公告：我们需要重建那座小镇的布局，为救援，也为验证一种可能性。", "page-copy"),
                pageLabel("在正式踏入梦屿以前，建筑先行服将由玩家共同建造初始小镇和更多场景。你亲手放下的每一个方块，都会成为未来剧情的见证；砖瓦之间，也将藏下两个世界的真相。", "page-copy"),
                pageLabel("下载群内最新整合包，在多人游戏中输入 dreamingfish.top 即可开始建造。", "page-callout")
        );
        return pageScroll(page);
    }

    private Node createDreamHavenPage() {
        VBox page = createPageBody();
        page.getChildren().addAll(
                pageLabel("NEXT SEASON", "page-eyebrow"),
                pageLabel("灾变之后，梦屿仍在", "page-title"),
                pageLabel("一段由全服玩家共同发现、共同判断，也共同书写结局的新故事。", "page-lead"),
                pageDivider(),
                pageLabel("守望梦屿是梦鱼服正在精心开发的下一周目，也是一个全新的故事起点。梦屿曾是一片让人慢下来生活、重新开始做梦的温柔之地；如今，污染、失序与未知危机正在改变它。", "page-copy"),
                pageLabel("玩家将以幸存者的身份踏入灾变后的大陆，在建造据点、寻找记录、整理线索、推进剧情任务、发展人物关系与多人协作中，一步步推动服务器的故事。探索不只是收集物资，每一段广播、每一次对话和每一份残缺记录，都可能改变大家对灾难的理解。", "page-copy"),
                pageLabel("线索不会直接给出唯一答案。你需要与伙伴分享发现，判断记录是否可信，推理灾难真正的成因，并为全服的下一步行动作出选择。错误的结论可能将梦屿推向毁灭，而真正的破局之法，也许就藏在某位玩家的发现之中。", "page-copy"),
                pageLabel("守望梦屿将于今年夏天推出。", "page-callout")
        );
        return pageScroll(page);
    }

    private Node createAboutPage() {
        VBox page = createPageBody();
        page.getChildren().addAll(
                pageLabel("DREAMINGFISH UPDATER", "page-eyebrow"),
                pageLabel("版本 " + PlayerApplication.VERSION, "page-version"),
                pageLabel("关于梦鱼服", "page-title"),
                pageDivider(),
                pageLabel("梦鱼服最早成立于 2021 年 7 月 10 日。成立之初，我们就希望为玩家带来一个公平、自由、富有探索感的模组生存体验，让大家能在同一个世界里建设、冒险、交流，并留下属于自己的故事。", "page-copy"),
                pageLabel("在最初的基岩版时期，服务器尝试了大量优质模组内容，也积累了一批活跃而稳定的玩家。梦鱼服始终坚持公益运营，不售卖强度，不以付费优势破坏玩家体验；服务器的更新方向，也主要来自腐竹筛选与玩家推荐。", "page-copy"),
                pageLabel("2022 年 11 月，服务器因腐竹学业暂时停服。2024 年高考结束后，梦鱼服重新启动，并从基岩版模组服转向 Java 版模组服，以追求更稳定、更自由、更适合长期开发的体验。", "page-copy"),
                pageLabel("现在，我们正围绕服务器自研模组、设计玩法系统，并持续打磨属于梦鱼服自己的内容。我们想创造的不只是一个\"装了很多模组\"的服务器，而是一种更完整、更有参与感，也更值得玩家长期投入的新体验。", "page-copy")
        );
        return pageScroll(page);
    }

    private static VBox createPageBody() {
        VBox page = new VBox();
        page.getStyleClass().add("content-page");
        page.setMaxWidth(900);
        return page;
    }

    private static Label pageLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Region pageDivider() {
        Region divider = new Region();
        divider.getStyleClass().add("page-divider");
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        VBox.setMargin(divider, new Insets(24, 0, 5, 0));
        return divider;
    }

    private static ScrollPane pageScroll(VBox page) {
        StackPane alignment = new StackPane(page);
        alignment.getStyleClass().add("content-page-alignment");
        alignment.setAlignment(Pos.TOP_LEFT);
        StackPane.setAlignment(page, Pos.TOP_LEFT);

        ScrollPane scroll = new ScrollPane(alignment);
        scroll.getStyleClass().add("content-page-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPannable(true);
        return scroll;
    }

    private void showPage(Page requestedPage) {
        if (requestedPage == currentPage) return;

        hideDrawer();
        currentPage = requestedPage;
        navigationButtons.forEach((page, button) ->
                button.pseudoClassStateChanged(NAV_SELECTED, page == requestedPage));

        boolean showHome = requestedPage == Page.HOME;
        setHomeContentVisible(showHome);
        contentPageLayer.setManaged(!showHome);
        contentPageLayer.setVisible(!showHome);

        for (Map.Entry<Page, Node> entry : contentPages.entrySet()) {
            boolean selected = entry.getKey() == requestedPage;
            entry.getValue().setManaged(selected);
            entry.getValue().setVisible(selected);
        }

        if (showHome) {
            playPageReveal(List.of(identityPane, playerIdentityPane, updateArea, updaterInfo));
            return;
        }

        Node page = contentPages.get(requestedPage);
        playPageReveal(List.of(page));
        detailsOpenedAction.run();
    }

    private void setHomeContentVisible(boolean visible) {
        for (Node node : List.of(identityPane, playerIdentityPane, updateArea, updaterInfo)) {
            node.setManaged(visible);
            node.setVisible(visible);
        }
    }

    private static void playPageReveal(List<Node> nodes) {
        ParallelTransition transition = new ParallelTransition();
        for (Node node : nodes) {
            node.setOpacity(0);
            node.setTranslateY(8);
            FadeTransition fade = new FadeTransition(Duration.millis(220), node);
            fade.setToValue(1);
            TranslateTransition move = new TranslateTransition(Duration.millis(340), node);
            move.setToY(0);
            transition.getChildren().add(new ParallelTransition(fade, move));
        }
        transition.play();
    }

    private static ParallelTransition reveal(Node node, double fromX, double fromY,
                                             double delayMillis) {
        node.setOpacity(0);
        node.setTranslateX(fromX);
        node.setTranslateY(fromY);
        FadeTransition fade = new FadeTransition(Duration.millis(430), node);
        fade.setDelay(Duration.millis(delayMillis));
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(650), node);
        move.setDelay(Duration.millis(delayMillis));
        move.setToX(0);
        move.setToY(0);
        return new ParallelTransition(fade, move);
    }

    private static Effect createRefractionEffect() {
        int width = 64;
        int height = 36;
        FloatMap map = new FloatMap(width, height);
        for (int y = 0; y < height; y++) {
            double vertical = y / (double) (height - 1);
            for (int x = 0; x < width; x++) {
                double horizontal = x / (double) (width - 1);
                float offsetX = (float) (Math.sin(vertical * Math.PI * 4.0) * 0.010
                        + (horizontal - 0.5) * 0.004);
                float offsetY = (float) (Math.sin(horizontal * Math.PI * 3.0) * 0.008);
                map.setSamples(x, y, offsetX, offsetY);
            }
        }
        DisplacementMap displacement = new DisplacementMap(map);
        displacement.setWrap(true);
        GaussianBlur blur = new GaussianBlur(5.5);
        blur.setInput(displacement);
        return blur;
    }

    private void buildDetailsDrawer() {
        detailsDrawer.getStyleClass().add("details-drawer");
        detailsDrawer.setPrefWidth(620);
        detailsDrawer.setMaxWidth(620);
        detailsDrawer.setPadding(new Insets(24, 26, 26, 26));
        detailsDrawer.setVisible(false);
        detailsDrawer.setManaged(false);
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        drawerTitle.getStyleClass().add("drawer-title");
        drawerTitle.setText("更新与本地管理");
        Button hide = new Button();
        hide.getStyleClass().add("window-button");
        installCloseGlyph(hide);
        hide.setTooltip(new Tooltip("收起详情"));
        hide.setAccessibleText("收起详情");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(drawerTitle, spacer, hide);
        hide.setOnAction(event -> hideDrawer());

        HBox tabs = new HBox(8);
        tabs.getStyleClass().add("drawer-tabs");
        for (DrawerMode mode : DrawerMode.values()) {
            Button button = new Button(mode.label);
            button.getStyleClass().add("drawer-tab");
            button.setOnAction(event -> showDrawerMode(mode));
            drawerTabs.put(mode, button);
            tabs.getChildren().add(button);
        }

        updateDetailsVersion.getStyleClass().add("update-detail-version");
        updateDetailsChangelog.getStyleClass().add("update-detail-changelog");
        updateDetailsChangelog.setWrapText(true);
        updateDetailsCounts.getStyleClass().add("update-detail-counts");
        updateDetailsList.getStyleClass().add("update-detail-list");
        updateDetailsList.setPlaceholder(emptyDrawerMessage("本次没有修改本地文件"));
        updateDetailsList.setCellFactory(list -> createUpdateDetailCell());
        VBox.setVgrow(updateDetailsList, Priority.ALWAYS);
        updateDetailsPage.getChildren().addAll(
                updateDetailsVersion, updateDetailsChangelog,
                updateDetailsCounts, updateDetailsList);

        historyList.getStyleClass().add("history-list");
        historyScroll.getStyleClass().add("drawer-scroll");
        historyScroll.setFitToWidth(true);
        historyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        historyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        logs.getStyleClass().add("log-list");
        logs.setPlaceholder(emptyDrawerMessage("本次运行还没有日志"));

        modSearch.setPromptText("搜索模组名称或文件名");
        modSearch.getStyleClass().add("mod-search");
        modSearch.textProperty().addListener((observable, oldValue, newValue) -> rebuildModList());
        restoreMods.getStyleClass().add("restore-mods-button");
        restoreMods.setOnAction(event -> confirmRestoreMods());
        HBox modTools = new HBox(10, modSearch, restoreMods);
        modTools.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modSearch, Priority.ALWAYS);
        modWarning.getStyleClass().add("mod-warning");
        modWarning.setWrapText(true);
        modList.getStyleClass().add("mod-list");
        modScroll.getStyleClass().add("drawer-scroll");
        modScroll.setFitToWidth(true);
        modScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        modScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(modScroll, Priority.ALWAYS);
        modPage.getChildren().addAll(modTools, modWarning, modScroll);

        localFileSearch.setPromptText("搜索目录、文件名或路径");
        localFileSearch.getStyleClass().add("mod-search");
        localFileSearch.textProperty().addListener(
                (observable, oldValue, newValue) -> rebuildLocalFileTree());
        restoreFiles.getStyleClass().add("restore-mods-button");
        restoreFiles.setOnAction(event -> confirmRestoreFiles());
        HBox fileTools = new HBox(10, localFileSearch, restoreFiles);
        fileTools.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(localFileSearch, Priority.ALWAYS);
        localFileWarning.getStyleClass().add("mod-warning");
        localFileWarning.setWrapText(true);
        localFileTree.getStyleClass().add("local-file-tree");
        localFileTree.setShowRoot(false);
        localFileTree.setCellFactory(tree -> createLocalFileCell());
        localFileEmpty.getStyleClass().add("drawer-empty");
        localFileEmpty.setMouseTransparent(true);
        StackPane.setAlignment(localFileEmpty, Pos.TOP_LEFT);
        VBox.setVgrow(localFileTreePane, Priority.ALWAYS);
        localFilePage.getChildren().addAll(fileTools, localFileWarning, localFileTreePane);

        ToggleGroup localModes = new ToggleGroup();
        fileManagementMode.setToggleGroup(localModes);
        modManagementMode.setToggleGroup(localModes);
        fileManagementMode.getStyleClass().add("local-mode-button");
        modManagementMode.getStyleClass().add("local-mode-button");
        fileManagementMode.setSelected(true);
        fileManagementMode.setOnAction(event -> {
            fileManagementMode.setSelected(true);
            showLocalManagementMode(LocalManagementMode.FILES);
        });
        modManagementMode.setOnAction(event -> {
            modManagementMode.setSelected(true);
            showLocalManagementMode(LocalManagementMode.MODS);
        });
        HBox localModeBar = new HBox(6, fileManagementMode, modManagementMode);
        localModeBar.getStyleClass().add("local-mode-bar");
        localManagementContent.getChildren().addAll(localFilePage, modPage);
        VBox.setVgrow(localManagementContent, Priority.ALWAYS);
        localManagementPage.getChildren().addAll(localModeBar, localManagementContent);
        showLocalManagementMode(LocalManagementMode.FILES);

        drawerContent.getChildren().addAll(
                updateDetailsPage, historyScroll, logs, localManagementPage);
        VBox.setVgrow(drawerContent, Priority.ALWAYS);
        detailsDrawer.getChildren().addAll(header, tabs, drawerContent);
        setReleaseHistory(null);
        showDrawerMode(DrawerMode.HISTORY);
    }

    private void buildLaunchNotice() {
        Label check = new Label("✓");
        check.getStyleClass().add("launch-notice-glyph");
        launchNoticeText.getStyleClass().add("launch-notice-text");
        launchNotice.getStyleClass().add("launch-notice");
        launchNotice.setAlignment(Pos.CENTER_LEFT);
        launchNotice.setMaxWidth(Region.USE_PREF_SIZE);
        launchNotice.getChildren().addAll(check, launchNoticeText);
        launchNoticeLayer.getChildren().add(launchNotice);
        launchNoticeLayer.setPickOnBounds(false);
        launchNoticeLayer.setMouseTransparent(true);
        launchNoticeLayer.setManaged(false);
        launchNoticeLayer.setVisible(false);
    }

    private void showLaunchNotice() {
        launchNoticeLayer.setManaged(true);
        launchNoticeLayer.setVisible(true);
        if (launchNoticeShown) return;
        launchNoticeShown = true;
        launchNotice.setOpacity(0);
        launchNotice.setTranslateY(-8);
        FadeTransition fade = new FadeTransition(Duration.millis(260), launchNotice);
        fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(420), launchNotice);
        move.setToY(0);
        new ParallelTransition(fade, move).play();
    }

    private void toggleDrawer(DrawerMode requestedMode) {
        if (detailsDrawer.isVisible() && drawerMode == requestedMode) {
            hideDrawer();
            return;
        }
        showDrawerMode(requestedMode);
        detailsDrawer.setManaged(true);
        detailsDrawer.setVisible(true);
        updateDetailToggleLabels(requestedMode);
        detailsOpenedAction.run();
    }

    private void showDrawerMode(DrawerMode mode) {
        drawerMode = mode;
        drawerTabs.forEach((candidate, button) ->
                button.pseudoClassStateChanged(DRAWER_SELECTED, candidate == mode));
        setDrawerNodeVisible(updateDetailsPage, mode == DrawerMode.UPDATE);
        setDrawerNodeVisible(historyScroll, mode == DrawerMode.HISTORY);
        setDrawerNodeVisible(logs, mode == DrawerMode.LOGS);
        setDrawerNodeVisible(localManagementPage, mode == DrawerMode.FILES);
        if (detailsDrawer.isVisible()) updateDetailToggleLabels(mode);
    }

    private static void setDrawerNodeVisible(Node node, boolean visible) {
        node.setManaged(visible);
        node.setVisible(visible);
    }

    private void updateDetailToggleLabels(DrawerMode mode) {
        changelogToggle.setText(mode == DrawerMode.HISTORY ? "收起记录  ‹" : "更新记录  ›");
        logToggle.setText(mode == DrawerMode.LOGS ? "收起记录  ‹" : "运行记录  ›");
        localFilesToggle.setText(mode == DrawerMode.FILES ? "收起管理  ‹" : "本地文件  ›");
    }

    private void hideDrawer() {
        detailsDrawer.setManaged(false);
        detailsDrawer.setVisible(false);
        changelogToggle.setText("更新记录  ›");
        logToggle.setText("运行记录  ›");
        localFilesToggle.setText("本地文件  ›");
    }

    private Node createHistoryEntry(ReleaseHistoryEntry release, boolean latest) {
        Label version = new Label("版本 " + release.displayVersion());
        version.getStyleClass().add("history-version");
        Label time = new Label(HISTORY_TIME.format(release.createdAt()));
        time.getStyleClass().add("history-time");
        HBox heading = new HBox(10, version);
        heading.setAlignment(Pos.CENTER_LEFT);
        if (latest) {
            Label badge = new Label("当前");
            badge.getStyleClass().add("history-current");
            heading.getChildren().add(badge);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        heading.getChildren().addAll(spacer, time);
        String changelog = release.changelog() == null || release.changelog().isBlank()
                ? "本次发布没有填写更新说明。" : release.changelog().trim();
        Label body = new Label(changelog);
        body.getStyleClass().add("history-changelog");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        VBox item = new VBox(9, heading, body);
        item.getStyleClass().add("history-entry");
        return item;
    }

    private Label emptyDrawerMessage(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("drawer-empty");
        label.setWrapText(true);
        return label;
    }

    private ListCell<UpdateDetailRow> createUpdateDetailCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(UpdateDetailRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label operation = new Label(item.operation());
                operation.getStyleClass().addAll("update-operation",
                        switch (item.operation()) {
                            case "删除" -> "update-operation-delete";
                            case "移入备份" -> "update-operation-archive";
                            default -> "update-operation-install";
                        });
                Label path = new Label(item.path());
                path.getStyleClass().add("update-detail-path");
                path.setWrapText(true);
                path.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(path, Priority.ALWAYS);
                HBox row = new HBox(12, operation, path);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
    }

    private void setUpdateDetails(String version, String changelog,
                                  List<Path> installed, List<Path> deleted,
                                  List<Path> archived) {
        updateDetailsVersion.setText("版本 " + version);
        updateDetailsChangelog.setText(changelog == null || changelog.isBlank()
                ? "本次发布没有填写更新说明。" : changelog.strip());
        List<String> counts = new ArrayList<>();
        if (size(installed) > 0) counts.add("安装 / 更新 " + installed.size() + " 项");
        if (size(deleted) > 0) counts.add("删除 " + deleted.size() + " 项");
        if (size(archived) > 0) counts.add("移入备份 " + archived.size() + " 项");
        updateDetailsCounts.setText(counts.isEmpty()
                ? "本次没有修改本地文件" : String.join("  ·  ", counts));
        List<UpdateDetailRow> rows = new ArrayList<>();
        appendUpdateRows(rows, "安装 / 更新", installed);
        appendUpdateRows(rows, "删除", deleted);
        appendUpdateRows(rows, "移入备份", archived);
        updateDetailsList.getItems().setAll(rows);
    }

    private static void appendUpdateRows(List<UpdateDetailRow> rows, String operation,
                                         List<Path> paths) {
        if (paths == null) return;
        paths.stream().map(Path::normalize)
                .sorted(java.util.Comparator.comparing(Path::toString,
                        String.CASE_INSENSITIVE_ORDER))
                .forEach(path -> rows.add(new UpdateDetailRow(
                        operation, path.toString().replace('\\', '/'))));
    }

    private void showLocalManagementMode(LocalManagementMode mode) {
        boolean files = mode == LocalManagementMode.FILES;
        setDrawerNodeVisible(localFilePage, files);
        setDrawerNodeVisible(modPage, !files);
        fileManagementMode.setSelected(files);
        modManagementMode.setSelected(!files);
    }

    private void rebuildLocalFileTree() {
        String query = localFileSearch.getText() == null
                ? "" : localFileSearch.getText().strip().toLowerCase(Locale.ROOT);
        Set<String> visible = new LinkedHashSet<>();
        if (query.isEmpty()) {
            localFiles.forEach(entry -> visible.add(foldPath(entry.path())));
        } else {
            for (LocalFileEntry entry : localFiles) {
                boolean matches = entry.path().toLowerCase(Locale.ROOT).contains(query)
                        || entry.displayName().toLowerCase(Locale.ROOT).contains(query);
                if (!matches) continue;
                visible.add(foldPath(entry.path()));
                addVisibleAncestors(visible, entry.path());
                if (entry.directory()) {
                    String prefix = foldPath(entry.path()) + "/";
                    localFiles.stream()
                            .map(LocalFileEntry::path)
                            .filter(path -> foldPath(path).startsWith(prefix))
                            .forEach(path -> visible.add(foldPath(path)));
                }
            }
        }

        TreeItem<LocalFileEntry> rootItem = new TreeItem<>();
        rootItem.setExpanded(true);
        Map<String, TreeItem<LocalFileEntry>> items = new LinkedHashMap<>();
        localFiles.stream()
                .filter(entry -> visible.contains(foldPath(entry.path())))
                .sorted(java.util.Comparator
                        .comparingInt((LocalFileEntry entry) -> pathDepth(entry.path()))
                        .thenComparing(LocalFileEntry::path, String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    TreeItem<LocalFileEntry> item = new TreeItem<>(entry);
                    String parent = parentPath(entry.path());
                    TreeItem<LocalFileEntry> parentItem = parent == null
                            ? rootItem : items.getOrDefault(foldPath(parent), rootItem);
                    parentItem.getChildren().add(item);
                    items.put(foldPath(entry.path()), item);
                    item.setExpanded(!query.isEmpty() || pathDepth(entry.path()) == 0);
                });
        localFileTree.setRoot(rootItem);
        boolean empty = rootItem.getChildren().isEmpty();
        localFileEmpty.setText(query.isEmpty()
                ? "当前版本没有受管理文件" : "没有匹配的文件或目录");
        localFileEmpty.setManaged(empty);
        localFileEmpty.setVisible(empty);
    }

    private TreeCell<LocalFileEntry> createLocalFileCell() {
        return new TreeCell<>() {
            @Override
            protected void updateItem(LocalFileEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(entry.displayName() + (entry.directory() ? "/" : ""));
                name.getStyleClass().add("local-file-name");
                name.setWrapText(true);
                Label detail = new Label(localFileDetail(entry));
                detail.getStyleClass().add("local-file-detail");
                detail.setWrapText(true);
                VBox labels = new VBox(2, name, detail);
                labels.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(labels, Priority.ALWAYS);

                CheckBox managed = new CheckBox(entry.forced() ? "强制"
                        : entry.inheritedExclusion() != null ? "随目录" : "管理");
                managed.getStyleClass().add("mod-toggle");
                managed.setSelected(entry.managed());
                managed.setIndeterminate(entry.partiallyExcluded() && !entry.forced());
                managed.setDisable(entry.forced() || entry.inheritedExclusion() != null);
                if (entry.forced()) {
                    managed.setTooltip(new Tooltip("管理端已为该目录启用强制同步"));
                } else if (entry.inheritedExclusion() != null) {
                    managed.setTooltip(new Tooltip(
                            "由目录 " + entry.inheritedExclusion() + " 控制"));
                }
                managed.setOnAction(event -> {
                    boolean requested = entry.partiallyExcluded() || managed.isSelected();
                    localFileToggleAction.accept(entry, requested);
                });
                HBox row = new HBox(12, labels, managed);
                row.getStyleClass().add("local-file-row");
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
    }

    private static String localFileDetail(LocalFileEntry entry) {
        List<String> details = new ArrayList<>();
        if (entry.forced()) {
            details.add("服务器强制同步");
        } else if (entry.inheritedExclusion() != null) {
            details.add("随 " + entry.inheritedExclusion() + " 不受管理");
        } else if (entry.directlyExcluded()) {
            details.add("本机不受管理");
        } else if (entry.partiallyExcluded()) {
            details.add("部分子项不受管理");
        } else {
            details.add("由更新器管理");
        }
        if (entry.directory()) {
            details.add(entry.managedFileCount() + " 个远程文件");
        } else if (!entry.present()) {
            details.add("当前版本中已不存在");
        } else if (entry.policy() == FilePolicy.DEFAULT) {
            details.add("DEFAULT · 仅缺失时安装");
        } else {
            details.add("ENFORCED · 校验并同步");
        }
        details.add(entry.path());
        return String.join("  ·  ", details);
    }

    private void confirmRestoreFiles() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "所有文件和目录的本地豁免都会清除。下次校验时，普通 ENFORCED 文件将恢复为服务器当前版本。",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.initOwner(stageWindow);
        confirmation.setTitle("恢复文件管理");
        confirmation.setHeaderText("恢复更新器管理全部文件吗？");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            restoreFilesAction.run();
        }
    }

    private static void addVisibleAncestors(Set<String> visible, String path) {
        String parent = parentPath(path);
        while (parent != null) {
            visible.add(foldPath(parent));
            parent = parentPath(parent);
        }
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    private static int pathDepth(String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }

    private static String foldPath(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private void rebuildModList() {
        if (modList == null) return;
        String query = modSearch.getText() == null
                ? "" : modSearch.getText().strip().toLowerCase(Locale.ROOT);
        modList.getChildren().clear();
        List<LocalModEntry> visible = localMods.stream()
                .filter(entry -> query.isEmpty()
                        || entry.displayName().toLowerCase(Locale.ROOT).contains(query)
                        || entry.path().toLowerCase(Locale.ROOT).contains(query)
                        || entry.componentId() != null
                        && entry.componentId().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        if (visible.isEmpty()) {
            modEmpty.setText(query.isEmpty() ? "没有检测到模组" : "没有匹配的模组");
            modEmpty.getStyleClass().setAll("drawer-empty");
            modList.getChildren().add(modEmpty);
            return;
        }
        for (int index = 0; index < visible.size(); index++) {
            modList.getChildren().add(createModRow(visible.get(index)));
            if (index + 1 < visible.size()) {
                Region divider = new Region();
                divider.getStyleClass().add("drawer-divider");
                divider.setMinHeight(1);
                modList.getChildren().add(divider);
            }
        }
    }

    private Node createModRow(LocalModEntry entry) {
        Label name = new Label(entry.displayName());
        name.getStyleClass().add("mod-name");
        name.setWrapText(true);
        String source = entry.forced() ? "服务器强制同步"
                : entry.managed() ? "整合包管理" : "玩家添加";
        if (entry.disabled() && !entry.forced()) {
            source += entry.active() ? " · 等待停用" : " · 已停用";
        }
        Label detail = new Label(source + "  ·  " + entry.path());
        detail.getStyleClass().add("mod-detail");
        detail.setWrapText(true);
        VBox labels = new VBox(3, name, detail);
        labels.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(labels, Priority.ALWAYS);

        CheckBox enabled = new CheckBox(entry.forced() ? "强制启用" : "启用");
        enabled.getStyleClass().add("mod-toggle");
        enabled.setSelected(entry.forced() || !entry.disabled());
        enabled.setDisable(entry.forced());
        if (entry.forced()) {
            enabled.setTooltip(new Tooltip("管理端强制同步目录中的模组不能在本机停用"));
        }
        enabled.setOnAction(event -> {
            boolean disabled = !enabled.isSelected();
            if (disabled && !confirmDisableMod(entry)) {
                enabled.setSelected(true);
                return;
            }
            localModToggleAction.accept(entry, disabled);
        });
        HBox row = new HBox(14, labels, enabled);
        row.getStyleClass().add("mod-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private boolean confirmDisableMod(LocalModEntry entry) {
        Alert warning = new Alert(Alert.AlertType.CONFIRMATION,
                "停用 “" + entry.displayName() + "” 可能导致依赖它的模组无法加载，"
                        + "也可能使你无法进入服务器。确认后更新器将不再自动恢复它。",
                ButtonType.CANCEL, ButtonType.OK);
        warning.initOwner(stageWindow);
        warning.setTitle("停用本地模组");
        warning.setHeaderText("确认停用这个模组吗？");
        return warning.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void confirmRestoreMods() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "所有本地停用选择都会清除。整合包模组将恢复为服务器当前版本，"
                        + "玩家自己添加的模组会放回原目录。",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.initOwner(stageWindow);
        confirmation.setTitle("恢复整合包默认");
        confirmation.setHeaderText("恢复全部模组吗？");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            restoreModsAction.run();
        }
    }

    private void showUpdateSummary(UpdateResult result) {
        ensureCurrentReleaseInHistory(result);
        updateSummaryVersion.setText("版本 " + result.release().displayVersion());
        String changelog = result.release().changelog() == null
                || result.release().changelog().isBlank()
                ? "本次发布没有填写更新说明。"
                : result.release().changelog().strip().lines().findFirst().orElse("");
        updateSummaryChangelog.setText(ellipsize(changelog, 74));
        List<String> counts = new ArrayList<>();
        if (!result.installedPaths().isEmpty()) counts.add("安装 / 更新 " + result.installedPaths().size() + " 项");
        if (!result.deletedPaths().isEmpty()) counts.add("删除 " + result.deletedPaths().size() + " 项");
        if (!result.archivedFiles().isEmpty()) counts.add("备份 " + result.archivedFiles().size() + " 项");
        if (locallyDisabledMods > 0) counts.add("本地停用 " + locallyDisabledMods + " 项");
        if (locallyExcludedFiles > 0) counts.add("本地不管理 " + locallyExcludedFiles + " 项");
        updateSummaryCounts.setText(counts.isEmpty() ? "本次无需修改本地文件" : String.join("  ·  ", counts));
        updateFileTooltip.setText("版本 " + result.release().displayVersion()
                + " · 文件变更"
                + System.lineSeparator()
                + System.lineSeparator()
                + formatUpdateFileDetails(
                        result.installedPaths(), result.deletedPaths(), result.archivedFiles()));
        setUpdateDetails(result.release().displayVersion(), result.release().changelog(),
                result.installedPaths(), result.deletedPaths(), result.archivedFiles());
        updateSummary.setManaged(true);
        updateSummary.setVisible(true);
    }

    static String formatUpdateFileDetails(List<Path> installed, List<Path> deleted,
                                          List<Path> archived) {
        List<String> lines = new ArrayList<>();
        int remaining = 30;
        remaining = appendFileSection(lines, "安装 / 更新", installed, remaining);
        remaining = appendFileSection(lines, "删除", deleted, remaining);
        appendFileSection(lines, "移入备份", archived, remaining);
        if (lines.isEmpty()) return "本次没有修改本地文件";
        int total = size(installed) + size(deleted) + size(archived);
        int displayed = Math.min(total, 30);
        if (total > displayed) {
            lines.add("");
            lines.add("另外 " + (total - displayed) + " 项未展开");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static int appendFileSection(List<String> lines, String title,
                                         List<Path> paths, int remaining) {
        if (paths == null || paths.isEmpty() || remaining <= 0) return remaining;
        if (!lines.isEmpty()) lines.add("");
        lines.add(title);
        int shown = Math.min(paths.size(), remaining);
        for (int index = 0; index < shown; index++) {
            lines.add("  " + paths.get(index).normalize().toString().replace('\\', '/'));
        }
        return remaining - shown;
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private void ensureCurrentReleaseInHistory(UpdateResult result) {
        if (releaseHistory != null && releaseHistory.releases().stream()
                .anyMatch(entry -> entry.releaseId().equals(result.release().releaseId()))) return;
        List<ReleaseHistoryEntry> entries = new ArrayList<>();
        entries.add(new ReleaseHistoryEntry(
                result.release().releaseId(), result.release().sequence(),
                result.release().displayVersion(), result.release().createdAt(),
                result.release().changelog()));
        if (releaseHistory != null) entries.addAll(releaseHistory.releases());
        setReleaseHistory(new ReleaseHistory(
                cn.dreamingfish.updater.protocol.ProtocolConstants.RELEASE_HISTORY_SCHEMA_VERSION,
                result.release().projectId(), entries));
    }

    private static String ellipsize(String value, int maximum) {
        if (value.length() <= maximum) return value;
        return value.substring(0, Math.max(0, maximum - 1)).stripTrailing() + "…";
    }

    private void showUnmanaged(List<Path> mods) {
        boolean present = !mods.isEmpty();
        unmanaged.setText(present ? "检测到 " + mods.size() + " 个玩家自选模组，已保留并继续启动  ›" : "");
        unmanaged.setManaged(present);
        unmanaged.setVisible(present);
        if (present) {
            unmanaged.setTooltip(new Tooltip(mods.stream().map(Path::toString)
                    .limit(20).reduce((left, right) -> left + System.lineSeparator() + right).orElse("")));
        }
    }

    private void showFileNotices(UpdateResult result) {
        List<Path> archived = result.archivedFiles();
        List<Path> unmanagedMods = result.unmanagedMods();
        if (!archived.isEmpty()) {
            String directories = result.release().forcedSyncDirectories().stream()
                    .map(value -> value + "/")
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("所选目录");
            String text = "远程管理端已对 " + directories + " 启用强制同步；已将 "
                    + archived.size() + " 个本地额外文件移入备份";
            if (!unmanagedMods.isEmpty()) {
                text += "；另有 " + unmanagedMods.size() + " 个玩家自选模组已保留";
            }
            unmanaged.setText(text);
            unmanaged.setManaged(true);
            unmanaged.setVisible(true);
            unmanaged.setTooltip(new Tooltip("备份位置：" + result.archiveDirectory()
                    + System.lineSeparator()
                    + archived.stream().map(Path::toString).limit(20)
                    .reduce((left, right) -> left + System.lineSeparator() + right).orElse("")));
            openArchive.setManaged(true);
            openArchive.setVisible(true);
            return;
        }
        openArchive.setManaged(false);
        openArchive.setVisible(false);
        showUnmanaged(unmanagedMods);
    }

    private void setWorking(boolean working) {
        retry.setDisable(working);
    }

    private void installWindowDrag(Stage stageWindow, HBox bar) {
        final double[] offset = new double[2];
        bar.setOnMousePressed(event -> {
            if (event.getTarget() instanceof Button) return;
            offset[0] = event.getSceneX();
            offset[1] = event.getSceneY();
        });
        bar.setOnMouseDragged(event -> {
            if (event.getTarget() instanceof Button) return;
            stageWindow.setX(event.getScreenX() - offset[0]);
            stageWindow.setY(event.getScreenY() - offset[1]);
        });
    }

    private void installResize(Stage stageWindow, Region grip) {
        final double[] origin = new double[4];
        grip.setOnMousePressed(event -> {
            origin[0] = event.getScreenX();
            origin[1] = event.getScreenY();
            origin[2] = stageWindow.getWidth();
            origin[3] = stageWindow.getHeight();
        });
        grip.setOnMouseDragged(event -> {
            stageWindow.setWidth(Math.max(stageWindow.getMinWidth(),
                    origin[2] + event.getScreenX() - origin[0]));
            stageWindow.setHeight(Math.max(stageWindow.getMinHeight(),
                    origin[3] + event.getScreenY() - origin[1]));
        });
    }

    private static void installMinimizeGlyph(Button button) {
        Region line = new Region();
        line.getStyleClass().add("window-glyph-line");
        line.setMinSize(13, 2);
        line.setPrefSize(13, 2);
        line.setMaxSize(13, 2);
        line.setMouseTransparent(true);
        button.setGraphic(line);
    }

    private static void installCloseGlyph(Button button) {
        Region forward = new Region();
        Region backward = new Region();
        for (Region line : List.of(forward, backward)) {
            line.getStyleClass().add("window-glyph-line");
            line.setMinSize(14, 2);
            line.setPrefSize(14, 2);
            line.setMaxSize(14, 2);
            line.setMouseTransparent(true);
        }
        forward.setRotate(45);
        backward.setRotate(-45);
        StackPane glyph = new StackPane(forward, backward);
        glyph.setMinSize(14, 14);
        glyph.setPrefSize(14, 14);
        glyph.setMaxSize(14, 14);
        glyph.setMouseTransparent(true);
        button.setGraphic(glyph);
    }

    private static void installMusicGlyph(Button button, boolean playing) {
        if (!playing) {
            Label note = new Label("♪");
            note.getStyleClass().add("music-note-glyph");
            note.setMouseTransparent(true);
            button.setGraphic(note);
            return;
        }
        Region left = new Region();
        Region right = new Region();
        for (Region line : List.of(left, right)) {
            line.getStyleClass().add("music-pause-line");
            line.setMinSize(3, 14);
            line.setPrefSize(3, 14);
            line.setMaxSize(3, 14);
            line.setMouseTransparent(true);
        }
        HBox glyph = new HBox(4, left, right);
        glyph.setAlignment(Pos.CENTER);
        glyph.setMinSize(10, 14);
        glyph.setPrefSize(10, 14);
        glyph.setMaxSize(10, 14);
        glyph.setMouseTransparent(true);
        button.setGraphic(glyph);
    }

    private static String displayPath(String path, String fallback) {
        if (path != null && !path.isBlank()) return path;
        return fallback == null || fallback.isBlank() ? "正在处理" : fallback;
    }

    private static String formatAmount(long amount) {
        return amount < 1024 ? Long.toString(amount) : formatBytes(amount);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format("%.1f KB", kib);
        double mib = kib / 1024.0;
        if (mib < 1024) return String.format("%.1f MB", mib);
        return String.format("%.2f GB", mib / 1024.0);
    }

    private static String validColor(String value, String fallback) {
        return value != null && value.matches("#[0-9a-fA-F]{6}") ? value : fallback;
    }
}
