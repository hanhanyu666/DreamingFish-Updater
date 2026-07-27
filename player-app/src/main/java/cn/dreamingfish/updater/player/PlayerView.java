package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateOutcome;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.engine.UpdateStage;
import cn.dreamingfish.updater.protocol.Branding;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
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
import java.util.List;
import java.util.Map;

final class PlayerView {
    private static final PseudoClass MUSIC_PLAYING = PseudoClass.getPseudoClass("playing");
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
        CHANGELOG,
        LOGS
    }

    private final StackPane root = new StackPane();
    private final ImageView background = new ImageView();
    private final ImageView refractedBackground = new ImageView();
    private final AnchorPane canvas = new AnchorPane();
    private final Region glassSweep = new Region();
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
    private final Button logToggle = new Button("运行日志  ›");
    private final VBox detailsDrawer = new VBox(14);
    private final Label drawerTitle = new Label();
    private final TextArea logs = new TextArea();
    private final TextArea changelog = new TextArea("正在获取发布信息...");
    private final Button close = new Button();
    private final Button minimize = new Button();
    private final Button music = new Button();
    private HBox titleBar;
    private VBox identityPane;
    private HBox playerIdentityPane;
    private VBox progressPane;
    private DrawerMode drawerMode;
    private boolean entrancePlayed;
    private boolean launchNoticeShown;
    private Runnable closeAction = () -> { };
    private Runnable retryAction = () -> { };
    private Runnable openDirectoryAction = () -> { };
    private Runnable openArchiveAction = () -> { };
    private Runnable detailsOpenedAction = () -> { };
    private Runnable musicToggleAction = () -> { };

    PlayerView(Stage stageWindow) {
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
        setChangelog(result.release().displayVersion(), result.release().changelog());
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
        logs.appendText(line + System.lineSeparator());
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
        setChangelog("1.20.1-r12", "新增梦屿群系探索内容\n优化主城区域加载速度\n修复部分任务无法完成的问题");
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
                reveal(progressPane, 18, 14, 190),
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
        buildLaunchNotice();
        buildDetailsDrawer();

        AnchorPane.setTopAnchor(titleBar, 0.0);
        AnchorPane.setLeftAnchor(titleBar, 0.0);
        AnchorPane.setRightAnchor(titleBar, 0.0);
        AnchorPane.setLeftAnchor(identityPane, 56.0);
        AnchorPane.setTopAnchor(identityPane, 154.0);
        AnchorPane.setLeftAnchor(playerIdentityPane, 56.0);
        AnchorPane.setBottomAnchor(playerIdentityPane, 38.0);
        AnchorPane.setRightAnchor(progressPane, 50.0);
        AnchorPane.setBottomAnchor(progressPane, 52.0);
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

        canvas.getChildren().addAll(identityPane, playerIdentityPane, progressPane,
                updaterInfo, launchNoticeLayer, detailsDrawer, titleBar, resizeGrip);
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
        changelogToggle.setOnAction(event -> toggleDrawer(DrawerMode.CHANGELOG));
        logToggle.setOnAction(event -> toggleDrawer(DrawerMode.LOGS));
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
        bar.getChildren().addAll(brand, spacer, music, minimize, close);
        installWindowDrag(stageWindow, bar);
        return bar;
    }

    private VBox createIdentity() {
        VBox box = new VBox(7);
        box.setMaxWidth(500);
        Label welcome = new Label("欢迎来到");
        welcome.getStyleClass().add("welcome-title");
        productName.getStyleClass().add("product-name");
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
        summary.getChildren().addAll(byteSummary, summarySpacer, changelogToggle, logToggle);

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
        detailsDrawer.setPrefWidth(520);
        detailsDrawer.setMaxWidth(520);
        detailsDrawer.setPadding(new Insets(26));
        detailsDrawer.setVisible(false);
        detailsDrawer.setManaged(false);
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        drawerTitle.getStyleClass().add("drawer-title");
        Button hide = new Button();
        hide.getStyleClass().add("window-button");
        installCloseGlyph(hide);
        hide.setTooltip(new Tooltip("收起详情"));
        hide.setAccessibleText("收起详情");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(drawerTitle, spacer, hide);
        hide.setOnAction(event -> hideDrawer());

        configureDetailsText(logs, false);
        configureDetailsText(changelog, true);
        VBox.setVgrow(logs, Priority.ALWAYS);
        VBox.setVgrow(changelog, Priority.ALWAYS);
        detailsDrawer.getChildren().addAll(header, logs, changelog);
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

    private void configureDetailsText(TextArea text, boolean wrap) {
        text.setEditable(false);
        text.setWrapText(wrap);
        text.getStyleClass().add("details-text");
    }

    private void toggleDrawer(DrawerMode requestedMode) {
        if (detailsDrawer.isVisible() && drawerMode == requestedMode) {
            hideDrawer();
            return;
        }
        drawerMode = requestedMode;
        boolean showChangelog = requestedMode == DrawerMode.CHANGELOG;
        drawerTitle.setText(showChangelog ? "更新记录" : "运行日志");
        changelog.setManaged(showChangelog);
        changelog.setVisible(showChangelog);
        logs.setManaged(!showChangelog);
        logs.setVisible(!showChangelog);
        detailsDrawer.setManaged(true);
        detailsDrawer.setVisible(true);
        changelogToggle.setText(showChangelog ? "收起记录  ‹" : "更新记录  ›");
        logToggle.setText(showChangelog ? "运行日志  ›" : "收起日志  ‹");
        detailsOpenedAction.run();
    }

    private void hideDrawer() {
        detailsDrawer.setManaged(false);
        detailsDrawer.setVisible(false);
        changelogToggle.setText("更新记录  ›");
        logToggle.setText("运行日志  ›");
    }

    private void setChangelog(String displayVersion, String content) {
        String body = content == null || content.isBlank() ? "本次发布没有填写更新记录。" : content.trim();
        changelog.setText("版本 " + displayVersion + System.lineSeparator()
                + System.lineSeparator() + body);
        changelog.positionCaret(0);
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
