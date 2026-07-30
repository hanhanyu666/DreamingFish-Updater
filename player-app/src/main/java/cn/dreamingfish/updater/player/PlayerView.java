package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateOutcome;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.engine.UpdateStage;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.FilePolicy;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import cn.dreamingfish.updater.protocol.ReleaseHistoryEntry;
import javafx.application.Platform;
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
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
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
import java.net.URI;
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
import java.util.function.Consumer;

final class PlayerView {
    private static final PseudoClass MUSIC_PLAYING = PseudoClass.getPseudoClass("playing");
    private static final PseudoClass NAV_SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass DRAWER_SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass WINDOW_MAXIMIZED = PseudoClass.getPseudoClass("maximized");
    private static final PseudoClass DRAWER_EXPANDED = PseudoClass.getPseudoClass("expanded");
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
        FILES("本地文件"),
        PLAYER_MODS("自选模组");

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
    private final NewsPage newsPage = new NewsPage();
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
    private final Tooltip unmanagedTooltip = new Tooltip();
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
    private final Label modWarning = new Label("停用必要模组可能导致游戏崩溃或无法连接服务器。\n"
            + "更改会在本次更新完成前重新校验；游戏已经启动时则从下次启动生效。");
    private final Button restoreMods = new Button("恢复整合包默认");
    private final VBox modPage = new VBox(12);
    private final VBox playerModList = new VBox();
    private final ScrollPane playerModScroll = new ScrollPane(playerModList);
    private final TextField playerModSearch = new TextField();
    private final Label playerModCount = new Label();
    private final Label playerModEmpty = new Label("没有检测到玩家自选模组");
    private final Label playerModWarning = new Label(
            "这些模组不属于服务器整合包，更新器会保留玩家的本地选择。\n"
                    + "停用模组可能导致依赖缺失或无法进入服务器，请确认后再修改。");
    private final VBox playerModPage = new VBox(12);
    private final TreeTableView<LocalFileEntry> localFileTree = new TreeTableView<>();
    private final TreeTableColumn<LocalFileEntry, LocalFileEntry> localFileInfoColumn =
            new TreeTableColumn<>();
    private final TreeTableColumn<LocalFileEntry, LocalFileEntry> localFileControlColumn =
            new TreeTableColumn<>();
    private final Map<String, TreeItem<LocalFileEntry>> localFileItems = new LinkedHashMap<>();
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
    private final Button maximize = new Button();
    private final Button music = new Button();
    private final Button expandDrawer = new Button();
    private HBox titleBar;
    private VBox identityPane;
    private HBox playerIdentityPane;
    private VBox latestNewsPane;
    private VBox progressPane;
    private VBox updateArea;
    private DrawerMode drawerMode;
    private Page currentPage = Page.HOME;
    private List<LocalModEntry> localMods = List.of();
    private List<LocalFileEntry> localFiles = List.of();
    private List<Path> visibleUnmanagedMods = List.of();
    private String renderedLocalFileQuery;
    private int locallyDisabledMods;
    private int locallyExcludedFiles;
    private ReleaseHistory releaseHistory;
    private boolean drawerExpanded;
    private boolean entrancePlayed;
    private boolean launchNoticeShown;
    private Runnable closeAction = () -> { };
    private Runnable retryAction = () -> { };
    private Runnable openDirectoryAction = () -> { };
    private Runnable openArchiveAction = () -> { };
    private Runnable detailsOpenedAction = () -> { };
    private Runnable musicToggleAction = () -> { };
    private Consumer<URI> openExternalLinkAction = ignored -> { };
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

    void showUnverifiedOfflineLaunch() {
        stage.setText("未验证离线启动");
        currentPath.setText("无法连接更新服务器，本次未检查整合包");
        progress.setProgress(0);
        percent.setText("--");
        byteSummary.setText("未执行文件验证");
        unmanaged.setManaged(false);
        unmanaged.setVisible(false);
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
        boolean preserveScroll = !modList.getChildren().isEmpty();
        double scrollPosition = modScroll.getVvalue();
        double playerModScrollPosition = playerModScroll.getVvalue();
        localMods = mods == null ? List.of() : List.copyOf(mods);
        locallyDisabledMods = (int) localMods.stream()
                .filter(entry -> entry.disabled() && !entry.forced()).count();
        rebuildModList();
        rebuildPlayerModList();
        updatePlayerModTabVisibility();
        if (preserveScroll) {
            Platform.runLater(() -> {
                modScroll.setVvalue(scrollPosition);
                playerModScroll.setVvalue(playerModScrollPosition);
            });
        }
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
        showUnmanaged(List.of(
                Path.of("mods/embeddium-options-api.jar"),
                Path.of("mods/xaeros-minimap.jar")));
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
                List.of(Path.of("mods/legacy-renderer.jar")), List.of(), List.of());
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
                        "mods/dreamingfish-core.jar", "dreamingfish", true, false, true, false),
                new LocalModEntry("component:embeddium-options-api", "Embeddium Options API",
                        "mods/embeddium-options-api.jar", "embeddium-options-api",
                        false, false, true, false),
                new LocalModEntry("component:xaerominimap", "Xaero's Minimap",
                        "mods/xaeros-minimap.jar", "xaerominimap",
                        false, false, true, false)));
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

    void showRestartRequired(String restoredItem) {
        if (PlayerDialog.confirm(stageWindow, PlayerDialog.Tone.INFO,
                "需要重新启动游戏", restoredItem,
                "请先关闭 DreamingFish Updater，再回到 MC 启动器重新启动游戏。"
                        + "当前已经启动的游戏不会自动重新加载刚恢复的文件。",
                "关闭更新器", "稍后")) {
            closeAction.run();
        }
    }

    boolean confirmCloseDuringUpdate() {
        return PlayerDialog.confirm(stageWindow, PlayerDialog.Tone.DANGER,
                "取消更新", "确定要关闭更新器吗？",
                "关闭更新器会取消本次更新，并停止 Minecraft 启动。",
                "取消更新", "继续更新");
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

    void setOpenExternalLinkAction(Consumer<URI> action) {
        openExternalLinkAction = action == null ? ignored -> { } : action;
        newsPage.setOpenExternalLink(uri -> openExternalLinkAction.accept(uri));
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
        background.setScaleX(1.045);
        background.setScaleY(1.045);
        refractedBackground.setScaleX(1.055);
        refractedBackground.setScaleY(1.055);

        FadeTransition rootFade = new FadeTransition(Duration.millis(460), root);
        rootFade.setFromValue(0);
        rootFade.setToValue(1);
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

        ParallelTransition entrance = new ParallelTransition(rootFade,
                backgroundScale, refractionScale,
                reveal(titleBar, 0, -10, 70),
                reveal(identityPane, -22, 6, 130),
                reveal(latestNewsPane, 18, 6, 165),
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
        latestNewsPane = createLatestNewsPane();
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
        AnchorPane.setRightAnchor(latestNewsPane, 32.0);
        AnchorPane.setTopAnchor(latestNewsPane, 108.0);
        AnchorPane.setRightAnchor(updateArea, 32.0);
        AnchorPane.setBottomAnchor(updateArea, 52.0);
        AnchorPane.setTopAnchor(contentPageLayer, 52.0);
        AnchorPane.setLeftAnchor(contentPageLayer, 0.0);
        AnchorPane.setRightAnchor(contentPageLayer, 0.0);
        AnchorPane.setBottomAnchor(contentPageLayer, 0.0);
        updaterInfo.getStyleClass().add("updater-info");
        AnchorPane.setRightAnchor(updaterInfo, 34.0);
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

        canvas.getChildren().addAll(identityPane, playerIdentityPane, latestNewsPane, updateArea,
                updaterInfo, contentPageLayer, launchNoticeLayer, detailsDrawer, titleBar, resizeGrip);
        root.widthProperty().addListener((observable, oldValue, newValue) ->
                updateLatestNewsVisibility());
        root.heightProperty().addListener((observable, oldValue, newValue) ->
                updateLatestNewsVisibility());
        root.getChildren().addAll(background, refractedBackground, shade, glassWash,
                canvas, glassRim, glassSweep);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        clip.setArcWidth(58);
        clip.setArcHeight(58);
        root.setClip(clip);

        stageWindow.maximizedProperty().addListener((observable, oldValue, maximizedValue) -> {
            boolean maximizedState = Boolean.TRUE.equals(maximizedValue);
            root.pseudoClassStateChanged(WINDOW_MAXIMIZED, maximizedState);
            clip.setArcWidth(maximizedState ? 0 : 58);
            clip.setArcHeight(maximizedState ? 0 : 58);
            installMaximizeGlyph(maximize, maximizedState);
            maximize.setTooltip(new Tooltip(maximizedState ? "还原窗口" : "最大化"));
            maximize.setAccessibleText(maximizedState ? "还原窗口" : "最大化");
        });
        resizeGrip.visibleProperty().bind(stageWindow.maximizedProperty().not());
        resizeGrip.managedProperty().bind(stageWindow.maximizedProperty().not());

        minimize.setOnAction(event -> stageWindow.setIconified(true));
        maximize.setOnAction(event -> stageWindow.setMaximized(!stageWindow.isMaximized()));
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
        maximize.getStyleClass().addAll("window-button", "maximize-button");
        close.getStyleClass().addAll("window-button", "close-button");
        music.setDisable(true);
        music.setTooltip(new Tooltip("正在载入背景音乐"));
        music.setAccessibleText("正在载入背景音乐");
        installMinimizeGlyph(minimize);
        installMaximizeGlyph(maximize, false);
        installCloseGlyph(close);
        minimize.setTooltip(new Tooltip("最小化"));
        maximize.setTooltip(new Tooltip("最大化"));
        close.setTooltip(new Tooltip("关闭"));
        minimize.setAccessibleText("最小化");
        maximize.setAccessibleText("最大化");
        close.setAccessibleText("关闭");
        bar.getChildren().addAll(brand, navigation, spacer, music, minimize, maximize, close);
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

    private VBox createLatestNewsPane() {
        NewsArticle latest = newsPage.latestArticle();
        VBox pane = new VBox(5);
        pane.getStyleClass().add("home-latest-news");
        pane.setPrefWidth(430);
        pane.setMinWidth(390);
        pane.setMaxWidth(430);
        pane.setCursor(Cursor.HAND);
        pane.setFocusTraversable(true);

        if (latest == null) {
            pane.setManaged(false);
            pane.setVisible(false);
            return pane;
        }

        Label metadata = new Label("最新新闻  ·  "
                + latest.publishedOn().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")));
        metadata.getStyleClass().add("home-latest-news-meta");
        Label title = new Label(latest.title());
        title.getStyleClass().add("home-latest-news-title");
        title.setMaxWidth(Double.MAX_VALUE);
        Label summary = new Label(latest.summary());
        summary.getStyleClass().add("home-latest-news-summary");
        summary.setWrapText(true);
        summary.setMaxHeight(40);
        summary.setMaxWidth(Double.MAX_VALUE);
        Label action = new Label("查看全文  ›");
        action.getStyleClass().add("home-latest-news-action");
        pane.getChildren().addAll(metadata, title, summary, action);
        pane.setAccessibleText("最新新闻，" + latest.title() + "，点击查看全文");
        pane.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                openLatestNews();
            }
        });
        pane.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER
                    || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                openLatestNews();
                event.consume();
            }
        });
        return pane;
    }

    private void openLatestNews() {
        showPage(Page.NEWS);
        newsPage.showLatestArticle();
        playPageReveal(List.of(newsPage.root()));
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
        unmanaged.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                openPlayerModPage();
            }
        });
        unmanaged.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER
                    || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                openPlayerModPage();
                event.consume();
            }
        });
        unmanagedTooltip.getStyleClass().add("unmanaged-mod-tooltip");
        unmanagedTooltip.setAutoFix(true);
        unmanagedTooltip.setWrapText(true);
        unmanagedTooltip.setMaxWidth(520);
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
        newsPage.setInteractionAction(() -> detailsOpenedAction.run());
    }

    private Node createNewsPage() {
        return newsPage.root();
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
        if (requestedPage == currentPage) {
            if (requestedPage == Page.NEWS && newsPage.showingArticle()) {
                newsPage.showList();
                playPageReveal(List.of(newsPage.root()));
            }
            return;
        }

        hideDrawer();
        currentPage = requestedPage;
        if (requestedPage == Page.NEWS) newsPage.showList();
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
            playPageReveal(List.of(identityPane, playerIdentityPane, latestNewsPane,
                    updateArea, updaterInfo));
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
        updateLatestNewsVisibility();
    }

    private void updateLatestNewsVisibility() {
        if (latestNewsPane == null) return;
        boolean visible = currentPage == Page.HOME
                && newsPage.latestArticle() != null
                && root.getHeight() >= 640;
        latestNewsPane.setManaged(visible);
        latestNewsPane.setVisible(visible);
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
        expandDrawer.getStyleClass().addAll("window-button", "drawer-expand-button");
        installMaximizeGlyph(expandDrawer, false);
        expandDrawer.setTooltip(new Tooltip("铺满内容区"));
        expandDrawer.setAccessibleText("铺满内容区");
        expandDrawer.setOnAction(event -> setDrawerExpanded(!drawerExpanded));
        Button hide = new Button();
        hide.getStyleClass().add("window-button");
        installCloseGlyph(hide);
        hide.setTooltip(new Tooltip("收起详情"));
        hide.setAccessibleText("收起详情");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(drawerTitle, spacer, expandDrawer, hide);
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
        modWarning.setMaxWidth(Double.MAX_VALUE);
        modWarning.setMinHeight(Region.USE_PREF_SIZE);
        modList.getStyleClass().add("mod-list");
        modScroll.getStyleClass().add("drawer-scroll");
        modScroll.setFitToWidth(true);
        modScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        modScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(modScroll, Priority.ALWAYS);
        modPage.getChildren().addAll(modTools, modWarning, modScroll);

        playerModSearch.setPromptText("搜索玩家自选模组");
        playerModSearch.getStyleClass().add("mod-search");
        playerModSearch.setMaxWidth(Double.MAX_VALUE);
        playerModSearch.textProperty().addListener(
                (observable, oldValue, newValue) -> rebuildPlayerModList());
        playerModCount.getStyleClass().add("player-mod-count");
        HBox playerModTools = new HBox(14, playerModSearch, playerModCount);
        playerModTools.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(playerModSearch, Priority.ALWAYS);
        playerModWarning.getStyleClass().add("mod-warning");
        playerModWarning.setWrapText(true);
        playerModWarning.setMaxWidth(Double.MAX_VALUE);
        playerModWarning.setMinHeight(Region.USE_PREF_SIZE);
        playerModList.getStyleClass().addAll("mod-list", "player-mod-list");
        playerModScroll.getStyleClass().add("drawer-scroll");
        playerModScroll.setFitToWidth(true);
        playerModScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        playerModScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(playerModScroll, Priority.ALWAYS);
        playerModPage.getChildren().addAll(
                playerModTools, playerModWarning, playerModScroll);
        rebuildPlayerModList();

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
        localFileWarning.setMaxWidth(Double.MAX_VALUE);
        localFileWarning.setMinHeight(Region.USE_PREF_SIZE);
        localFileTree.getStyleClass().add("local-file-tree");
        configureLocalFileColumns();
        localFileTree.setShowRoot(false);
        localFileTree.setFixedCellSize(52);
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
                updateDetailsPage, historyScroll, logs, localManagementPage, playerModPage);
        VBox.setVgrow(drawerContent, Priority.ALWAYS);
        detailsDrawer.getChildren().addAll(header, tabs, drawerContent);
        setDrawerExpanded(false);
        setReleaseHistory(null);
        showDrawerMode(DrawerMode.HISTORY);
        updatePlayerModTabVisibility();
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
        setDrawerNodeVisible(playerModPage, mode == DrawerMode.PLAYER_MODS);
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

    private void setDrawerExpanded(boolean expanded) {
        drawerExpanded = expanded;
        detailsDrawer.pseudoClassStateChanged(DRAWER_EXPANDED, expanded);
        if (expanded) {
            AnchorPane.setLeftAnchor(detailsDrawer, 0.0);
            detailsDrawer.setPrefWidth(Region.USE_COMPUTED_SIZE);
            detailsDrawer.setMaxWidth(Double.MAX_VALUE);
        } else {
            AnchorPane.setLeftAnchor(detailsDrawer, null);
            detailsDrawer.setPrefWidth(620);
            detailsDrawer.setMaxWidth(620);
        }
        installMaximizeGlyph(expandDrawer, expanded);
        String action = expanded ? "恢复侧栏" : "铺满内容区";
        expandDrawer.setTooltip(new Tooltip(action));
        expandDrawer.setAccessibleText(action);
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
                            case "放弃管理" -> "update-operation-release";
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
                                  List<Path> archived, List<Path> released) {
        updateDetailsVersion.setText("版本 " + version);
        updateDetailsChangelog.setText(changelog == null || changelog.isBlank()
                ? "本次发布没有填写更新说明。" : changelog.strip());
        List<String> counts = new ArrayList<>();
        if (size(installed) > 0) counts.add("安装 / 更新 " + installed.size() + " 项");
        if (size(deleted) > 0) counts.add("删除 " + deleted.size() + " 项");
        if (size(archived) > 0) counts.add("移入备份 " + archived.size() + " 项");
        if (size(released) > 0) counts.add("放弃管理 " + released.size() + " 项");
        updateDetailsCounts.setText(counts.isEmpty()
                ? "本次没有修改本地文件" : String.join("  ·  ", counts));
        List<UpdateDetailRow> rows = new ArrayList<>();
        appendUpdateRows(rows, "安装 / 更新", installed);
        appendUpdateRows(rows, "删除", deleted);
        appendUpdateRows(rows, "移入备份", archived);
        appendUpdateRows(rows, "放弃管理", released);
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

    private void openPlayerModPage() {
        if (playerAddedMods(localMods, visibleUnmanagedMods).isEmpty()) return;
        rebuildPlayerModList();
        updatePlayerModTabVisibility();
        showDrawerMode(DrawerMode.PLAYER_MODS);
        detailsDrawer.setManaged(true);
        detailsDrawer.setVisible(true);
        updateDetailToggleLabels(DrawerMode.PLAYER_MODS);
        detailsOpenedAction.run();
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

        List<LocalFileEntry> displayedFiles = localFiles.stream()
                .filter(entry -> visible.contains(foldPath(entry.path())))
                .sorted(java.util.Comparator
                        .comparingInt((LocalFileEntry entry) -> pathDepth(entry.path()))
                        .thenComparing(LocalFileEntry::path, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Set<String> displayedPaths = displayedFiles.stream()
                .map(entry -> foldPath(entry.path()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        if (query.equals(renderedLocalFileQuery)
                && localFileItems.keySet().equals(displayedPaths)) {
            displayedFiles.forEach(entry ->
                    localFileItems.get(foldPath(entry.path())).setValue(entry));
            updateLocalFileEmptyState(query, displayedFiles.isEmpty());
            return;
        }

        Set<String> expandedPaths = localFileItems.entrySet().stream()
                .filter(entry -> entry.getValue().isExpanded())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean hadTree = localFileTree.getRoot() != null;
        TreeItem<LocalFileEntry> rootItem = new TreeItem<>();
        rootItem.setExpanded(true);
        localFileItems.clear();
        displayedFiles.forEach(entry -> {
                    TreeItem<LocalFileEntry> item = new TreeItem<>(entry);
                    String parent = parentPath(entry.path());
                    TreeItem<LocalFileEntry> parentItem = parent == null
                            ? rootItem : localFileItems.getOrDefault(foldPath(parent), rootItem);
                    parentItem.getChildren().add(item);
                    String foldedPath = foldPath(entry.path());
                    localFileItems.put(foldedPath, item);
                    item.setExpanded(!query.isEmpty() || pathDepth(entry.path()) == 0
                            || hadTree && expandedPaths.contains(foldedPath));
                });
        localFileTree.setRoot(rootItem);
        renderedLocalFileQuery = query;
        updateLocalFileEmptyState(query, displayedFiles.isEmpty());
    }

    private void updateLocalFileEmptyState(String query, boolean empty) {
        localFileEmpty.setText(query.isEmpty()
                ? "当前版本没有受管理文件" : "没有匹配的文件或目录");
        localFileEmpty.setManaged(empty);
        localFileEmpty.setVisible(empty);
    }

    private void configureLocalFileColumns() {
        for (TreeTableColumn<LocalFileEntry, LocalFileEntry> column
                : List.of(localFileInfoColumn, localFileControlColumn)) {
            column.setCellValueFactory(data -> data.getValue().valueProperty());
            column.setSortable(false);
            column.setReorderable(false);
        }
        localFileInfoColumn.setMinWidth(160);
        localFileInfoColumn.setCellFactory(column -> createLocalFileInfoCell());
        localFileControlColumn.setMinWidth(82);
        localFileControlColumn.setPrefWidth(82);
        localFileControlColumn.setMaxWidth(82);
        localFileControlColumn.setResizable(false);
        localFileControlColumn.setCellFactory(column -> createLocalFileControlCell());
        localFileTree.getColumns().clear();
        localFileTree.getColumns().add(localFileInfoColumn);
        localFileTree.getColumns().add(localFileControlColumn);
        localFileTree.setTreeColumn(localFileInfoColumn);
        localFileTree.setColumnResizePolicy(
                TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        localFileTree.setTableMenuButtonVisible(false);
    }

    private TreeTableCell<LocalFileEntry, LocalFileEntry> createLocalFileInfoCell() {
        return new TreeTableCell<>() {
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
                name.setWrapText(false);
                name.setMinWidth(0);
                name.setTooltip(new Tooltip(name.getText()));
                Label detail = new Label(localFileDetail(entry));
                detail.getStyleClass().add("local-file-detail");
                detail.setWrapText(false);
                detail.setMinWidth(0);
                detail.setTooltip(new Tooltip(detail.getText()));
                VBox labels = new VBox(2, name, detail);
                labels.getStyleClass().add("local-file-labels");
                labels.setMinWidth(0);
                labels.setMaxWidth(Double.MAX_VALUE);
                getStyleClass().add("local-file-info-cell");
                setText(null);
                setGraphic(labels);
            }
        };
    }

    private TreeTableCell<LocalFileEntry, LocalFileEntry> createLocalFileControlCell() {
        return new TreeTableCell<>() {
            @Override
            protected void updateItem(LocalFileEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                CheckBox managed = createLocalFileCheckBox(entry);
                getStyleClass().add("local-file-control-cell");
                setAlignment(Pos.CENTER_RIGHT);
                setText(null);
                setGraphic(managed);
            }
        };
    }

    private CheckBox createLocalFileCheckBox(LocalFileEntry entry) {
        CheckBox managed = new CheckBox(entry.forced() ? "强制"
                : entry.inheritedExclusion() != null ? "随目录" : "管理");
        managed.getStyleClass().add("mod-toggle");
        managed.setMinWidth(Region.USE_PREF_SIZE);
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
        return managed;
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
        if (PlayerDialog.confirm(stageWindow, PlayerDialog.Tone.INFO,
                "恢复文件管理", "恢复更新器管理全部文件吗？",
                "所有文件和目录的本地豁免都会清除。下次校验时，普通 ENFORCED 文件将恢复为服务器当前版本。",
                "恢复全部管理", "取消")) {
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

    private void rebuildPlayerModList() {
        String query = playerModSearch.getText() == null
                ? "" : playerModSearch.getText().strip().toLowerCase(Locale.ROOT);
        List<LocalModEntry> all = playerAddedMods(localMods, visibleUnmanagedMods);
        long enabled = all.stream().filter(entry -> !entry.disabled()).count();
        playerModCount.setText("共 " + all.size() + " 个  ·  " + enabled + " 个启用");
        playerModList.getChildren().clear();
        List<LocalModEntry> visible = all.stream()
                .filter(entry -> query.isEmpty()
                        || entry.displayName().toLowerCase(Locale.ROOT).contains(query)
                        || entry.path().toLowerCase(Locale.ROOT).contains(query)
                        || entry.componentId() != null
                        && entry.componentId().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        if (visible.isEmpty()) {
            playerModEmpty.setText(all.isEmpty()
                    ? "没有检测到玩家自选模组" : "没有匹配的玩家自选模组");
            playerModEmpty.getStyleClass().setAll("drawer-empty");
            playerModList.getChildren().add(playerModEmpty);
            return;
        }
        for (int index = 0; index < visible.size(); index++) {
            playerModList.getChildren().add(createModRow(visible.get(index)));
            if (index + 1 < visible.size()) {
                Region divider = new Region();
                divider.getStyleClass().add("drawer-divider");
                divider.setMinHeight(1);
                playerModList.getChildren().add(divider);
            }
        }
    }

    static List<LocalModEntry> playerAddedMods(List<LocalModEntry> scanned,
                                                List<Path> detectedPaths) {
        Map<String, LocalModEntry> entries = new LinkedHashMap<>();
        if (scanned != null) {
            scanned.stream().filter(entry -> !entry.managed()).forEach(entry ->
                    entries.put(foldPath(entry.path()), entry));
        }
        if (detectedPaths != null) {
            for (Path detected : detectedPaths) {
                String path = detected.normalize().toString().replace('\\', '/');
                entries.computeIfAbsent(foldPath(path), ignored -> new LocalModEntry(
                        "path:" + foldPath(path), modNameFromPath(detected), path,
                        null, false, false, true, false));
            }
        }
        return entries.values().stream()
                .sorted(java.util.Comparator.comparing(LocalModEntry::disabled).reversed()
                        .thenComparing(LocalModEntry::displayName,
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String modNameFromPath(Path path) {
        Path fileName = path.getFileName();
        String value = fileName == null ? path.toString() : fileName.toString();
        return value.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? value.substring(0, value.length() - 4) : value;
    }

    private void updatePlayerModTabVisibility() {
        boolean hasPlayerMods = !playerAddedMods(localMods, visibleUnmanagedMods).isEmpty();
        Button button = drawerTabs.get(DrawerMode.PLAYER_MODS);
        if (button != null) {
            button.setManaged(hasPlayerMods);
            button.setVisible(hasPlayerMods);
        }
        if (!hasPlayerMods && drawerMode == DrawerMode.PLAYER_MODS) {
            showDrawerMode(DrawerMode.FILES);
        }
    }

    private Node createModRow(LocalModEntry entry) {
        Label name = new Label(entry.displayName());
        name.getStyleClass().add("mod-name");
        name.setWrapText(false);
        name.setTooltip(new Tooltip(name.getText()));
        String source = entry.forced() ? "服务器强制同步"
                : entry.managed() ? "整合包管理" : "玩家添加";
        if (entry.disabled() && !entry.forced()) {
            source += entry.active() ? " · 等待停用" : " · 已停用";
        }
        Label detail = new Label(source + "  ·  " + entry.path());
        detail.getStyleClass().add("mod-detail");
        detail.setWrapText(false);
        detail.setTooltip(new Tooltip(detail.getText()));
        VBox labels = new VBox(3, name, detail);
        labels.setMinWidth(0);
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
        row.setMinHeight(58);
        row.setPrefHeight(58);
        row.setMaxHeight(58);
        return row;
    }

    private boolean confirmDisableMod(LocalModEntry entry) {
        return PlayerDialog.confirm(stageWindow, PlayerDialog.Tone.WARNING,
                "停用本地模组", "确认停用这个模组吗？",
                "停用 “" + entry.displayName() + "” 可能导致依赖它的模组无法加载，"
                        + "也可能使你无法进入服务器。确认后更新器将不再自动恢复它。",
                "确认停用", "取消");
    }

    private void confirmRestoreMods() {
        if (PlayerDialog.confirm(stageWindow, PlayerDialog.Tone.INFO,
                "恢复整合包默认", "恢复全部模组吗？",
                "所有本地停用选择都会清除。整合包模组将恢复为服务器当前版本，"
                        + "玩家自己添加的模组会放回原目录。",
                "恢复默认", "取消")) {
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
        if (!result.releasedPaths().isEmpty()) counts.add("保留并放弃管理 " + result.releasedPaths().size() + " 项");
        if (locallyDisabledMods > 0) counts.add("本地停用 " + locallyDisabledMods + " 项");
        if (locallyExcludedFiles > 0) counts.add("本地不管理 " + locallyExcludedFiles + " 项");
        updateSummaryCounts.setText(counts.isEmpty() ? "本次无需修改本地文件" : String.join("  ·  ", counts));
        updateFileTooltip.setText("版本 " + result.release().displayVersion()
                + " · 文件变更"
                + System.lineSeparator()
                + System.lineSeparator()
                + formatUpdateFileDetails(
                        result.installedPaths(), result.deletedPaths(),
                        result.archivedFiles(), result.releasedPaths()));
        setUpdateDetails(result.release().displayVersion(), result.release().changelog(),
                result.installedPaths(), result.deletedPaths(),
                result.archivedFiles(), result.releasedPaths());
        updateSummary.setManaged(true);
        updateSummary.setVisible(true);
    }

    static String formatUpdateFileDetails(List<Path> installed, List<Path> deleted,
                                          List<Path> archived) {
        return formatUpdateFileDetails(installed, deleted, archived, List.of());
    }

    static String formatUpdateFileDetails(List<Path> installed, List<Path> deleted,
                                          List<Path> archived, List<Path> released) {
        List<String> lines = new ArrayList<>();
        int remaining = 30;
        remaining = appendFileSection(lines, "安装 / 更新", installed, remaining);
        remaining = appendFileSection(lines, "删除", deleted, remaining);
        remaining = appendFileSection(lines, "移入备份", archived, remaining);
        appendFileSection(lines, "放弃管理（保留本地文件）", released, remaining);
        if (lines.isEmpty()) return "本次没有修改本地文件";
        int total = size(installed) + size(deleted) + size(archived)
                + size(released);
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
        String text = present
                ? "检测到 " + mods.size() + " 个玩家自选模组，已保留并继续启动  ›"
                : "";
        updateUnmanagedNotice(text, mods, List.of());
    }

    private void showFileNotices(UpdateResult result) {
        List<Path> archived = result.archivedFiles();
        List<Path> released = result.releasedPaths();
        List<Path> unmanagedMods = result.unmanagedMods();
        if (!archived.isEmpty() || !released.isEmpty()) {
            String directories = result.release().forcedSyncDirectories().stream()
                    .map(value -> value + "/")
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("所选目录");
            String text = "";
            if (!archived.isEmpty()) {
                text = "远程管理端已对 " + directories + " 启用强制同步；已将 "
                        + archived.size() + " 个本地额外文件移入备份";
            }
            if (!released.isEmpty()) {
                if (!text.isEmpty()) text += "；";
                text += "服主已停止管理 " + released.size()
                        + " 个文件，本地副本已保留";
            }
            if (!unmanagedMods.isEmpty()) {
                text += "；另有 " + unmanagedMods.size() + " 个玩家自选模组已保留";
            }
            List<String> noticeLines = new ArrayList<>();
            if (!archived.isEmpty()) {
                noticeLines.add("备份位置：" + result.archiveDirectory());
                archived.stream().map(Path::toString).limit(20)
                        .forEach(path -> noticeLines.add("备份：" + path));
            }
            released.stream().map(Path::toString).limit(20)
                    .forEach(path -> noticeLines.add("保留：" + path));
            if (!unmanagedMods.isEmpty()) text += "  ›";
            updateUnmanagedNotice(text, unmanagedMods, noticeLines);
            openArchive.setManaged(!archived.isEmpty());
            openArchive.setVisible(!archived.isEmpty());
            return;
        }
        openArchive.setManaged(false);
        openArchive.setVisible(false);
        showUnmanaged(unmanagedMods);
    }

    private void updateUnmanagedNotice(String text, List<Path> mods, List<String> contextLines) {
        visibleUnmanagedMods = mods == null ? List.of() : List.copyOf(mods);
        rebuildPlayerModList();
        updatePlayerModTabVisibility();
        boolean present = text != null && !text.isBlank();
        boolean actionable = !visibleUnmanagedMods.isEmpty();
        unmanaged.setText(present ? text : "");
        unmanaged.setManaged(present);
        unmanaged.setVisible(present);
        unmanaged.setCursor(actionable ? Cursor.HAND : Cursor.DEFAULT);
        unmanaged.setFocusTraversable(actionable);
        unmanaged.setAccessibleText(present
                ? text + (actionable ? "，点击打开自选模组标签页" : "")
                : "");
        unmanaged.getStyleClass().remove("unmanaged-action");
        if (actionable) unmanaged.getStyleClass().add("unmanaged-action");

        List<String> details = new ArrayList<>();
        if (contextLines != null) details.addAll(contextLines);
        if (actionable) {
            if (!details.isEmpty()) details.add("");
            details.add(formatUnmanagedModDetails(visibleUnmanagedMods));
        }
        if (details.isEmpty()) {
            unmanaged.setTooltip(null);
        } else {
            unmanagedTooltip.setText(String.join(System.lineSeparator(), details));
            unmanaged.setTooltip(unmanagedTooltip);
        }
    }

    static String formatUnmanagedModDetails(List<Path> mods) {
        List<Path> values = mods == null ? List.of() : mods;
        List<String> lines = new ArrayList<>();
        lines.add("玩家自选模组（" + values.size() + " 个）");
        values.stream().limit(20)
                .map(Path::normalize)
                .map(Path::toString)
                .map(path -> path.replace('\\', '/'))
                .forEach(path -> lines.add("  " + path));
        if (values.size() > 20) lines.add("  另有 " + (values.size() - 20) + " 个未展开");
        lines.add("");
        lines.add("点击进入“自选模组”标签页");
        return String.join(System.lineSeparator(), lines);
    }

    private void setWorking(boolean working) {
        retry.setDisable(working);
    }

    private void installWindowDrag(Stage stageWindow, HBox bar) {
        final double[] offset = new double[2];
        bar.setOnMousePressed(event -> {
            if (isButtonTarget(event.getTarget())) return;
            offset[0] = event.getSceneX();
            offset[1] = event.getSceneY();
        });
        bar.setOnMouseDragged(event -> {
            if (isButtonTarget(event.getTarget()) || stageWindow.isMaximized()) return;
            stageWindow.setX(event.getScreenX() - offset[0]);
            stageWindow.setY(event.getScreenY() - offset[1]);
        });
        bar.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && !isButtonTarget(event.getTarget())) {
                stageWindow.setMaximized(!stageWindow.isMaximized());
            }
        });
    }

    private static boolean isButtonTarget(Object target) {
        Node node = target instanceof Node candidate ? candidate : null;
        while (node != null) {
            if (node instanceof Button) return true;
            node = node.getParent();
        }
        return false;
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

    private static void installMaximizeGlyph(Button button, boolean maximized) {
        if (!maximized) {
            Region square = new Region();
            square.getStyleClass().add("window-glyph-box");
            square.setMinSize(12, 10);
            square.setPrefSize(12, 10);
            square.setMaxSize(12, 10);
            square.setMouseTransparent(true);
            button.setGraphic(square);
            return;
        }
        Region back = new Region();
        Region front = new Region();
        for (Region square : List.of(back, front)) {
            square.getStyleClass().add("window-glyph-box");
            square.setMinSize(10, 8);
            square.setPrefSize(10, 8);
            square.setMaxSize(10, 8);
            square.setMouseTransparent(true);
        }
        back.setTranslateX(2);
        back.setTranslateY(-2);
        front.setTranslateX(-2);
        front.setTranslateY(2);
        StackPane glyph = new StackPane(back, front);
        glyph.setMinSize(14, 12);
        glyph.setPrefSize(14, 12);
        glyph.setMaxSize(14, 12);
        glyph.setMouseTransparent(true);
        button.setGraphic(glyph);
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
