package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ArchiveCatalog;
import cn.dreamingfish.updater.engine.ArchiveReason;
import cn.dreamingfish.updater.engine.CancellationToken;
import cn.dreamingfish.updater.engine.GameUpdateLock;
import cn.dreamingfish.updater.engine.LocalFileOverrides;
import cn.dreamingfish.updater.engine.PlayerProgramUpdateOutcome;
import cn.dreamingfish.updater.engine.PlayerProgramUpdateResult;
import cn.dreamingfish.updater.engine.PlayerProgramUpdater;
import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.ProgressListener;
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
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import cn.dreamingfish.updater.protocol.ReleaseHistoryEntry;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import cn.dreamingfish.updater.player.PlayerViewPort.DialogTone;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Headless orchestration shared by the Tauri window and the Java sidecar. */
public final class PlayerController {
    private static final String PLAYER_PROGRAM_UPDATE_MESSAGE = "正在更新玩家端程序";
    private static final int AUTO_CLOSE_SECONDS = 15;

    private final JsonCodec json = new JsonCodec();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean viewportReady = new AtomicBoolean();
    private final Object localPreferenceLock = new Object();
    private final ReleaseHistoryClient releaseHistoryClient = new ReleaseHistoryClient();
    private final PlayerPresentationClient presentationClient = new PlayerPresentationClient();
    private final PlayerArguments arguments;
    private final PlayerViewPort viewport;
    private final Runnable exitAction;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "player-auto-close");
                thread.setDaemon(true);
                return thread;
            });

    private final BootstrapPermitClient permitClient;
    private ProjectBinding binding;
    private Path playerHome;
    private PlayerLog log;
    private volatile boolean working;
    private volatile boolean launchPermitted;
    private volatile boolean restartPending;
    private volatile boolean initializationFailed;
    private volatile boolean autoCloseSuppressed;
    private Path lastArchiveDirectory;
    private LocalModManager localModManager;
    private LocalFileManager localFileManager;
    private LocalOptionManager localOptionManager;
    private ArchiveCatalog archiveCatalog;
    private volatile Branding releaseBranding;
    private volatile Branding presentationBranding;
    private ScheduledFuture<?> autoCloseTask;

    private record LocalSettingsSnapshot(
            long modRevision,
            long fileRevision,
            long optionRevision,
            Map<String, Boolean> groupChoices,
            LocalFileOverrides overrides
    ) {
        boolean sameRevision(LocalSettingsSnapshot other) {
            return other != null && modRevision == other.modRevision
                    && fileRevision == other.fileRevision
                    && optionRevision == other.optionRevision;
        }
    }

    @FunctionalInterface
    interface CheckedAction {
        void run() throws Exception;
    }

    public PlayerController(PlayerArguments arguments, PlayerViewPort viewport, Runnable exitAction) {
        this.arguments = arguments;
        this.viewport = viewport;
        this.exitAction = exitAction == null ? () -> { } : exitAction;
        this.permitClient = new BootstrapPermitClient(arguments);
    }

    public void start() {
        try {
            loadConfiguration();
            permitClient.ready();
            refreshLocalManagementAsync();
            if (arguments.preview()) readyViewport();
            startUpdate();
        } catch (Exception e) {
            initializationFailed = true;
            showInitializationFailure(e);
        }
    }

    public void requestClose() {
        if (arguments.preview() || launchPermitted || restartPending) {
            exitApplication();
            return;
        }
        if (initializationFailed) {
            closeAndDeny("玩家在初始化失败后关闭了更新器");
            return;
        }
        if (working) {
            if (!viewport.confirmDialog(DialogTone.DANGER,
                    "取消更新", "确定要关闭更新器吗？",
                    "关闭更新器会取消本次更新，并停止 Minecraft 启动。",
                    "取消更新", "继续更新")) {
                return;
            }
            cancelled.set(true);
        }
        closeAndDeny("玩家关闭了更新器");
    }

    public synchronized void retry() {
        if (initializationFailed) {
            initializationFailed = false;
            working = false;
            start();
            return;
        }
        startUpdate();
    }

    public void continueLaunch() {
        if (!viewport.confirmDialog(DialogTone.WARNING,
                "忽略本地文件变更", "仍然启动 Minecraft？",
                "更新服务器当前不可用，部分受管理的本地文件与上次验证版本不一致。"
                        + "继续后会保留当前文件，本次不会把它标记为已验证。",
                "仍然启动", "返回检查")) {
            return;
        }
        Thread.ofVirtual().name("manual-local-content-launch").start(() -> {
            try {
                permitClient.allow();
                launchPermitted = true;
                log.warn("玩家操作", "玩家选择保留已修改的本地文件并继续启动 Minecraft");
                viewport.showLocalContentOverrideLaunch();
                startAutoCloseCountdown();
            } catch (Exception e) {
                log.error("启动许可", "保留本地文件后无法授予 Minecraft 启动许可", e);
                showFailure(errorTitle(e), e);
            }
        });
    }

    public void changeLocalModPreference(LocalModEntry entry, boolean disabled) {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localModManager.setDisabled(entry, disabled);
            }
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("本地设置", "无法保存模组启停设置", e);
            showFailure("无法保存模组设置", e);
        }
    }

    public void restoreLocalModDefaults() {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localModManager.restoreDefaults();
            }
            refreshLocalManagementAsync();
            showRestartRequired("模组启停设置已恢复");
        } catch (IOException e) {
            log.error("本地设置", "无法恢复默认模组设置", e);
            showFailure("无法恢复模组设置", e);
        }
    }

    public void changeLocalFilePreference(LocalFileEntry entry, boolean managed) {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localFileManager.setManaged(entry, managed);
            }
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("本地设置", "无法保存文件管理范围", e);
            showFailure("无法保存本地文件设置", e);
        }
    }

    public void restoreLocalFileDefaults() {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localFileManager.restoreDefaults();
            }
            refreshLocalManagementAsync();
            showRestartRequired("文件管理设置已恢复");
        } catch (IOException e) {
            log.error("本地设置", "无法恢复默认文件管理范围", e);
            showFailure("无法恢复文件管理设置", e);
        }
    }

    /** Switches an optional group; {@code null} returns it to the published default. */
    public void changeOptionalGroup(String groupId, Boolean enabled) {
        keepWindowOpen();
        try {
            synchronized (localPreferenceLock) {
                localOptionManager.setChoice(groupId, enabled);
            }
            log.info("可选内容", "已" + (enabled == null ? "恢复默认设置" : enabled ? "开启" : "关闭")
                    + "可选内容：" + groupId);
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("本地设置", "无法保存可选内容设置", e);
            showFailure("无法保存可选内容设置", e);
        }
    }

    /** Asks the next update to restore the published default configuration. */
    public void requestDefaultReset(LocalFileEntry entry) {
        keepWindowOpen();
        if (!viewport.confirmDialog(DialogTone.WARNING, "恢复默认配置", "恢复“" + entry.displayName() + "”？",
                "下次更新时会换回服主当前提供的默认配置，你现在的这份会先移入备份，"
                        + "之后可以在“备份与恢复”里找到。",
                "恢复默认", "取消")) {
            return;
        }
        try {
            synchronized (localPreferenceLock) {
                localFileManager.requestReset(entry);
            }
            log.info("本地设置", "已请求恢复默认配置：" + entry.path());
            refreshLocalManagementAsync();
        } catch (IOException e) {
            log.error("本地设置", "无法保存恢复默认请求", e);
            showFailure("无法恢复默认配置", e);
        }
    }

    public void refreshArchives() {
        Thread.ofVirtual().name("player-archives").start(() -> {
            try {
                viewport.setArchives(archiveCatalog.list());
            } catch (RuntimeException e) {
                log.warn("备份与恢复", "无法读取备份列表：" + e.getMessage());
            }
        });
    }

    /** Puts one backed-up file back into the game after checking the next update would keep it. */
    public void restoreArchivedFile(String archiveId, String originalPath) {
        keepWindowOpen();
        ArchiveCatalog.RestoreCheck check;
        try (GameUpdateLock ignored = acquireGameUpdateLock()) {
            synchronized (localPreferenceLock) {
                ReleaseManifest installed = localModManager.loadInstalledManifest(binding.projectId());
                LocalFileOverrides overrides = localSettingsSnapshot().overrides();
                check = archiveCatalog.check(archiveId, originalPath, installed, overrides);
                if (check.allowed()) archiveCatalog.restore(archiveId, originalPath, installed, overrides);
            }
        } catch (IOException | RuntimeException e) {
            log.error("备份与恢复", "无法恢复备份文件", e);
            showFailure("无法恢复备份文件", e);
            return;
        }
        if (!check.allowed()) {
            // Explained after the locks are released so the dialog never holds up an update.
            viewport.confirmDialog(DialogTone.INFO, "无法恢复", "这个文件暂时不能恢复",
                    check.reason(), "知道了", "关闭");
            return;
        }
        log.info("备份与恢复", "已从备份恢复：" + originalPath);
        refreshArchives();
        refreshLocalManagementAsync();
    }

    public void deleteArchive(String archiveId) {
        keepWindowOpen();
        if (!viewport.confirmDialog(DialogTone.DANGER, "删除备份", "永久删除这份备份？",
                "删除后无法找回其中的文件。确认不再需要后再删除。", "永久删除", "取消")) {
            return;
        }
        try {
            archiveCatalog.delete(archiveId);
            log.info("备份与恢复", "已删除备份：" + archiveId);
            refreshArchives();
        } catch (IOException | RuntimeException e) {
            log.error("备份与恢复", "无法删除备份", e);
            showFailure("无法删除备份", e);
        }
    }

    public void openArchive(String archiveId) {
        archiveCatalog.find(archiveId).ifPresent(archive -> viewport.openArchiveDirectory(archive.directory()));
    }

    public void openPlayerDirectory() {
        if (playerHome == null) return;
        viewport.openPlayerDirectory(playerHome);
    }

    public void openArchiveDirectory() {
        if (lastArchiveDirectory == null) return;
        viewport.openArchiveDirectory(lastArchiveDirectory);
    }

    public void openExternalLink(URI uri) {
        keepWindowOpen();
        viewport.openExternalLink(uri);
    }

    public void keepWindowOpen() {
        autoCloseSuppressed = true;
        if (autoCloseTask != null) {
            autoCloseTask.cancel(false);
            autoCloseTask = null;
        }
        if (launchPermitted) viewport.showLaunchKeptOpen();
    }

    public void exitApplication() {
        scheduler.shutdownNow();
        exitAction.run();
    }

    private void loadConfiguration() throws IOException {
        binding = json.read(arguments.bindingFile(), ProjectBinding.class);
        ManifestValidator.validateBinding(binding);
        playerHome = resolvePlayerHome(binding);
        Files.createDirectories(playerHome);
        log = new PlayerLog(playerHome);
        log.setListener(viewport::appendLog);
        viewport.setLogs(log.readRecentLines(5000));
        log.startSession(binding.projectId(), PlayerRuntime.VERSION);
        localModManager = new LocalModManager(arguments.instanceRoot(), playerHome);
        localFileManager = new LocalFileManager(arguments.instanceRoot(), playerHome);
        localOptionManager = new LocalOptionManager(playerHome);
        archiveCatalog = new ArchiveCatalog(arguments.instanceRoot(), playerHome);
        viewport.setPlayerIdentity(arguments.playerName());
        releaseBranding = loadInitialBranding();
        presentationBranding = presentationClient.loadCached(binding, playerHome);
        applyActiveBranding();
        viewport.setBackground(resolveBundledCover(binding));
        viewport.setReleaseHistory(releaseHistoryClient.loadCached(binding, playerHome));
    }

    private void startUpdate() {
        if (arguments.preview() || working || launchPermitted) return;
        cancelled.set(false);
        working = true;
        viewport.showProgress(new ProgressEvent(UpdateStage.CHECKING,
                "正在连接更新服务", null, 0, 0));
        ProgressListener progress = throttledProgress();
        CancellationToken cancellation = cancelled::get;

        Thread.ofVirtual().name("startup-update").start(() -> {
            try {
                LocalSettingsSnapshot snapshot = reconcileLocalState();
                UpdateRequest startupRequest = updateRequest(snapshot, cancellation);
                PlayerProgramUpdateResult programResult = new PlayerProgramUpdater()
                        .checkAndInstall(startupRequest,
                                PlayerRuntime.BOOTSTRAP_AGENT_VERSION, null,
                                playerProgramProgress(progress));
                if (programResult.outcome() == PlayerProgramUpdateOutcome.CHECK_UNAVAILABLE) {
                    log.warn("玩家端更新", "暂时无法检查玩家端程序版本，继续检查整合包内容");
                } else if (programResult.outcome() == PlayerProgramUpdateOutcome.NOT_PUBLISHED) {
                    log.info("玩家端更新", "此项目还没有发布玩家端程序，继续检查整合包内容");
                }
                if (programResult.restartRequired()) {
                    working = false;
                    log.info("玩家端更新", "玩家端 " + programResult.manifest().version()
                            + " 已安装，正在通过 Bootstrap Agent 重新启动");
                    restartWithUpdatedProgram(programResult);
                    return;
                }

                refreshPlayerPresentation(startupRequest);

                // The Tauri shell may already be visible during a slow program
                // self-update; this signal marks the transition to the modpack check.
                readyViewport();

                UpdateResult result = null;
                UpdateEngine engine = new UpdateEngine();
                while (true) {
                    UpdateResult pass = engine.update(updateRequest(snapshot, cancellation), progress);
                    result = mergeResults(result, pass);
                    boolean preferencesChanged;
                    try (GameUpdateLock gameLock = acquireGameUpdateLock()) {
                        synchronized (localPreferenceLock) {
                            localModManager.reconcileDesiredState(result.release(), snapshot.groupChoices());
                            localFileManager.clearResetRequests(pass.resetPaths().stream()
                                    .map(path -> path.toString().replace('\\', '/')).toList());
                            LocalSettingsSnapshot latest = localSettingsSnapshot();
                            preferencesChanged = !snapshot.sameRevision(latest);
                            if (preferencesChanged) {
                                snapshot = latest;
                            } else {
                                localModManager.finalizeSuccessfulUpdate(result.release());
                                result = mergeStoredArchives(result);
                                permitClient.allow(gameLock::close);
                                launchPermitted = true;
                            }
                        }
                    }
                    if (!preferencesChanged) break;
                    log.info("本地设置", "更新期间模组启停设置发生变化，正在重新核对文件");
                }
                working = false;
                log.info("启动许可", "已允许 Minecraft 启动 · 整合包发布 "
                        + result.release().releaseId());
                if (!result.archivedFiles().isEmpty()) {
                    log.info("备份与恢复", "已将 " + result.archivedFiles().size()
                            + " 个本地文件移入备份：" + result.archiveDirectory());
                    if (result.archived().isEmpty()) {
                        result.archivedFiles().forEach(path ->
                                log.info("备份与恢复", "已备份本地文件：" + path));
                    }
                    result.archived().forEach(file -> log.info("备份与恢复", file.originalPath()
                            + "：" + file.reason().description()
                            + (file.detail().isBlank() || file.reason() == ArchiveReason.CLEANUP
                            || file.reason() == ArchiveReason.DUPLICATE ? "" : "（" + file.detail() + "）")));
                }
                result.keptModifiedPaths().forEach(path -> log.info("默认配置",
                        "服主更新了默认配置，你修改过的版本已保留：" + path));
                result.skippedSelfManagedPaths().forEach(path -> log.info("自行管理",
                        "服主提供了新版本，你自行管理的文件未被更新：" + path));
                result.resetPaths().forEach(path -> log.info("默认配置",
                        "已恢复默认配置：" + path));
                if (!result.releasedPaths().isEmpty()) {
                    log.info("文件管理", "管理端已放弃管理 "
                            + result.releasedPaths().size() + " 个文件，并保留玩家本地副本");
                    result.releasedPaths().forEach(path ->
                            log.info("文件管理", "已放弃管理：" + path));
                }
                UpdateResult completedResult = result;
                runNonFatalPostLaunchRefresh(() -> {
                    List<LocalModEntry> mods = localModManager.scan(completedResult.release());
                    List<LocalFileEntry> files = localFileManager.scan(completedResult.release());
                    viewport.setLocalMods(mods);
                    viewport.setLocalFiles(files);
                    viewport.setOptionalGroups(localOptionManager.view(completedResult.release(),
                            localOptionManager.snapshot().choices()));
                    viewport.setArchives(archiveCatalog.list());
                }, error -> log.warn("本地设置",
                        "Minecraft 获得启动许可后无法刷新本地管理界面：" + error));
                releaseBranding = completedResult.release().branding();
                applyActiveBranding();
                finishSuccessfully(completedResult);
                refreshReleaseHistory();
            } catch (Exception e) {
                working = false;
                readyViewport();
                if (handleUnverifiedOfflineLaunch(e)) return;
                log.error("整合包更新", "更新失败", e);
                showFailure(errorTitle(e), e);
            }
        });
    }

    static boolean runNonFatalPostLaunchRefresh(CheckedAction refresh,
                                                 java.util.function.Consumer<Exception> warning) {
        try {
            refresh.run();
            return true;
        } catch (Exception error) {
            warning.accept(error);
            return false;
        }
    }

    private LocalSettingsSnapshot reconcileLocalState() throws IOException {
        try (GameUpdateLock gameLock = acquireGameUpdateLock()) {
            synchronized (localPreferenceLock) {
                var installed = localModManager.loadInstalledManifest(binding.projectId());
                LocalSettingsSnapshot current = localSettingsSnapshot();
                // Local JSON is only a planning hint; owner actions run after engine signature verification.
                localModManager.reconcileDesiredState(installed, current.groupChoices(), false);
                return localSettingsSnapshot();
            }
        }
    }

    private LocalSettingsSnapshot localSettingsSnapshot() throws IOException {
        LocalModManager.Snapshot mods = localModManager.snapshot();
        LocalFileManager.Snapshot files = localFileManager.snapshot();
        LocalOptionManager.Snapshot options = localOptionManager.snapshot();
        return new LocalSettingsSnapshot(mods.revision(), files.revision(), options.revision(),
                options.choices(),
                mods.overrides().merge(files.overrides()).withGroupChoices(options.choices()));
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
                PlayerRuntime.VERSION, ProtocolConstants.RELEASE_CAPABILITIES,
                null, null, null, cancellation, snapshot.overrides());
    }

    private UpdateResult mergeResults(UpdateResult previous, UpdateResult current) {
        if (previous == null) return current;
        List<Path> installed = combinePaths(previous.installedPaths(), current.installedPaths());
        List<Path> deleted = combinePaths(previous.deletedPaths(), current.deletedPaths());
        List<Path> archived = combinePaths(previous.archivedFiles(), current.archivedFiles());
        List<Path> released = combinePaths(previous.releasedPaths(), current.releasedPaths());
        List<cn.dreamingfish.updater.engine.ArchivedFile> archivedEntries = new java.util.ArrayList<>(
                previous.archived());
        archivedEntries.addAll(current.archived());
        return new UpdateResult(
                current.outcome(), current.release(), installed.size(), deleted.size(),
                previous.downloadedBytes() + current.downloadedBytes(),
                current.unmanagedMods(), archived,
                current.archiveDirectory() != null
                        ? current.archiveDirectory() : previous.archiveDirectory(),
                installed, deleted, released, archivedEntries,
                current.keptModifiedPaths(), current.skippedSelfManagedPaths(),
                combinePaths(previous.resetPaths(), current.resetPaths()));
    }

    private static List<Path> combinePaths(List<Path> first, List<Path> second) {
        java.util.LinkedHashSet<Path> paths = new java.util.LinkedHashSet<>(first);
        paths.addAll(second);
        return List.copyOf(paths);
    }

    private UpdateResult mergeStoredArchives(UpdateResult result) {
        List<LocalModManager.StoredArchive> stored = localModManager.drainStoredArchives();
        if (stored.isEmpty()) return result;
        List<Path> paths = new java.util.ArrayList<>(result.archivedFiles());
        List<cn.dreamingfish.updater.engine.ArchivedFile> files = new java.util.ArrayList<>(result.archived());
        for (var archive : stored) {
            files.addAll(archive.files());
            archive.files().forEach(file -> paths.add(Path.of(file.originalPath())));
        }
        return new UpdateResult(result.outcome(), result.release(), result.installedFiles(), result.deletedFiles(),
                result.downloadedBytes(), result.unmanagedMods(), paths,
                result.archiveDirectory() == null ? stored.getLast().directory() : result.archiveDirectory(),
                result.installedPaths(), result.deletedPaths(), result.releasedPaths(), files,
                result.keptModifiedPaths(), result.skippedSelfManagedPaths(), result.resetPaths());
    }

    private void refreshLocalManagementAsync() {
        if (localModManager == null || localFileManager == null) return;
        Thread.ofVirtual().name("local-management-scan").start(() -> {
            try {
                var release = localModManager.loadInstalledManifest(binding.projectId());
                List<LocalModEntry> mods = localModManager.scan(release);
                List<LocalFileEntry> files = localFileManager.scan(release);
                viewport.setLocalMods(mods);
                viewport.setLocalFiles(files);
                viewport.setOptionalGroups(localOptionManager.view(release,
                        localOptionManager.snapshot().choices()));
                viewport.setArchives(archiveCatalog.list());
            } catch (IOException e) {
                log.error("本地设置", "无法扫描本地文件和模组设置", e);
            }
        });
    }

    private void refreshReleaseHistory() {
        Thread.ofVirtual().name("release-history").start(() -> {
            try {
                ReleaseHistory history = releaseHistoryClient.fetch(binding, playerHome);
                viewport.setReleaseHistory(history);
            } catch (IOException e) {
                log.warn("更新记录", "暂时无法获取远程发布记录，已显示本地缓存");
            }
        });
    }

    private void refreshPlayerPresentation(UpdateRequest request) {
        try {
            presentationBranding = presentationClient.fetch(request);
            applyActiveBranding();
            log.info("个性化", "已从管理端刷新玩家端界面内容");
        } catch (IOException e) {
            log.warn("个性化", "暂时无法获取远程界面内容，已使用上次验证的本地版本");
        }
    }

    private void applyActiveBranding() {
        viewport.setBranding(mergePresentation(releaseBranding, presentationBranding));
    }

    static Branding mergePresentation(Branding release, Branding presentation) {
        Branding base = release == null ? Branding.empty() : release;
        if (presentation == null) return base;
        return new Branding(
                presentation.productName(),
                presentation.subtitle(),
                presentation.serverAddress(),
                base.coverObject(),
                presentation.accentColor(),
                presentation.secondaryAccentColor(),
                presentation.brandName(),
                presentation.brandEnglishName(),
                presentation.newsArticles(),
                presentation.customPage(),
                presentation.contentPages(),
                base.musicTracks(),
                presentation.welcomeText(),
                presentation.topBarColor(),
                presentation.cardColor(),
                presentation.topBarOpacity(),
                presentation.titleColor());
    }

    private void restartWithUpdatedProgram(PlayerProgramUpdateResult result) {
        restartPending = true;
        long downloaded = result.downloadedBytes();
        viewport.showProgress(new ProgressEvent(UpdateStage.COMPLETE,
                PLAYER_PROGRAM_UPDATE_MESSAGE, "正在切换到新版本", downloaded, downloaded));
        scheduler.schedule(this::exitApplication, 350, TimeUnit.MILLISECONDS);
    }

    private ProgressListener throttledProgress() {
        AtomicLong lastUi = new AtomicLong();
        AtomicReference<UpdateStage> lastStage = new AtomicReference<>();
        return event -> {
            long now = System.nanoTime();
            boolean stageChanged = lastStage.getAndSet(event.stage()) != event.stage();
            if (stageChanged) log.info(stageCategory(event.stage()), event.message());
            if (stageChanged || now - lastUi.get() >= 50_000_000L
                    || (event.totalBytes() > 0 && event.completedBytes() >= event.totalBytes())) {
                lastUi.set(now);
                viewport.showProgress(event);
            }
        };
    }

    static ProgressListener playerProgramProgress(ProgressListener listener) {
        return event -> listener.onProgress(new ProgressEvent(event.stage(),
                PLAYER_PROGRAM_UPDATE_MESSAGE, null,
                event.completedBytes(), event.totalBytes()));
    }

    private void finishSuccessfully(UpdateResult result) {
        lastArchiveDirectory = result.archiveDirectory();
        viewport.showResult(result);
        startAutoCloseCountdown();
    }

    private void startAutoCloseCountdown() {
        if (autoCloseSuppressed) {
            viewport.showLaunchKeptOpen();
            return;
        }
        int[] remaining = {AUTO_CLOSE_SECONDS};
        viewport.showLaunchCountdown(remaining[0]);
        autoCloseTask = scheduler.scheduleAtFixedRate(() -> {
            remaining[0]--;
            if (remaining[0] > 0) {
                viewport.showLaunchCountdown(remaining[0]);
            } else {
                autoCloseTask.cancel(false);
                viewport.fadeOut(Duration.ofMillis(320).toMillis(), this::exitApplication);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private boolean handleUnverifiedOfflineLaunch(Exception failure) {
        if (!PlayerRuntime.allowsUnverifiedOfflineLaunch(failure)) return false;
        try (GameUpdateLock gameLock = acquireGameUpdateLock()) {
            permitClient.allow(gameLock::close);
            launchPermitted = true;
        } catch (Exception permitFailure) {
            log.error("启动许可", "离线状态下无法授予 Minecraft 启动许可", permitFailure);
            showFailure(errorTitle(permitFailure), permitFailure);
            return true;
        }
        log.warn("离线启动", "更新服务不可用，本次未验证本地文件便继续启动 Minecraft");
        viewport.showUnverifiedOfflineLaunch();
        startAutoCloseCountdown();
        return true;
    }

    private void showFailure(String title, Throwable error) {
        String detail = error.getMessage() == null || error.getMessage().isBlank()
                ? "请查看日志后重试"
                : error.getMessage();
        viewport.showError(title, detail, PlayerRuntime.allowsLocalContentOverride(error));
    }

    private void showInitializationFailure(Exception error) {
        if (log != null) log.error("初始化", "玩家端初始化失败", error);
        if (binding == null) {
            binding = new ProjectBinding(1, "unknown", "http://127.0.0.1", "invalid",
                    "DreamingFishUpdater", null, Branding.empty());
            viewport.setBranding(Branding.empty());
            viewport.setBackground(null);
        }
        viewport.showError(error instanceof BootstrapPermitClient.PermitException
                        ? "启动许可已失效" : "更新器无法启动",
                error.getMessage(), false);
        readyViewport();
    }

    private void readyViewport() {
        if (viewportReady.compareAndSet(false, true)) viewport.ready();
    }

    private Branding loadInitialBranding() {
        ReleaseManifest installed = localModManager.loadInstalledManifest(binding.projectId());
        if (installed != null) return installed.branding();

        Path bundled = arguments.instanceRoot()
                .resolve(".dreamingfish-bootstrap/bundled-release/manifest.json");
        try {
            if (Files.isRegularFile(bundled, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(bundled)) {
                ReleaseManifest release = json.read(bundled, ReleaseManifest.class);
                if (binding.projectId().equals(release.projectId())) return release.branding();
            }
        } catch (Exception ignored) {
            // The bundled manifest is only a startup hint; the signed manifest
            // received by the update engine remains authoritative.
        }
        return binding.fallbackBranding();
    }

    private void closeAndDeny(String reason) {
        try {
            permitClient.deny(reason);
        } finally {
            exitApplication();
        }
    }

    private void showRestartRequired(String restoredItem) {
        if (viewport.confirmDialog(DialogTone.INFO,
                "需要重新启动游戏", restoredItem,
                "请先关闭 DreamingFish Updater，再回到 MC 启动器重新启动游戏。"
                        + "当前已经启动的游戏不会自动重新加载刚恢复的文件。",
                "关闭更新器", "稍后")) {
            exitApplication();
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
            if (log != null) log.error("界面资源", "整合包内置封面路径无效", e);
            return null;
        }
    }

    private static String stageCategory(UpdateStage stage) {
        return switch (stage) {
            case CHECKING -> "检查更新";
            case SCANNING -> "扫描文件";
            case DOWNLOADING -> "下载文件";
            case PREPARING -> "准备更新";
            case INSTALLING -> "安装文件";
            case VERIFYING -> "校验文件";
            case RECOVERING -> "恢复文件";
            case OFFLINE -> "离线启动";
            case COMPLETE -> "更新完成";
        };
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
            case LOCAL_CONTENT_CHANGED -> "本地托管文件已变更";
            case LOCAL_STATE_INVALID -> "本地整合包需要修复";
            case PATH_UNSAFE -> "发布中包含不安全路径";
            case RECOVERY_FAILED, TRANSACTION_FAILED -> "更新事务未能完成";
            case CANCELLED -> "更新已取消";
            default -> "更新失败";
        };
    }

}
