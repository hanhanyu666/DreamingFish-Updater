package cn.dreamingfish.updater.player;

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
import cn.dreamingfish.updater.player.PlayerViewPort.DialogTone;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Headless-friendly orchestration shared by the JavaFX window and the Tauri sidecar. */
public final class PlayerController {
    private static final int AUTO_CLOSE_SECONDS = 15;

    private final JsonCodec json = new JsonCodec();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Object localPreferenceLock = new Object();
    private final ReleaseHistoryClient releaseHistoryClient = new ReleaseHistoryClient();
    private final PlayerArguments arguments;
    private final PlayerViewPort viewport;
    private final Runnable exitAction;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "player-auto-close");
                thread.setDaemon(true);
                return thread;
            });

    private BootstrapPermitClient permitClient;
    private ProjectBinding binding;
    private Path playerHome;
    private PlayerLog log;
    private volatile boolean working;
    private volatile boolean launchPermitted;
    private volatile boolean restartPending;
    private volatile boolean autoCloseSuppressed;
    private Path lastArchiveDirectory;
    private LocalModManager localModManager;
    private LocalFileManager localFileManager;
    private ScheduledFuture<?> autoCloseTask;

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

    public PlayerController(PlayerArguments arguments, PlayerViewPort viewport, Runnable exitAction) {
        this.arguments = arguments;
        this.viewport = viewport;
        this.exitAction = exitAction == null ? () -> { } : exitAction;
    }

    public void start() {
        try {
            loadConfiguration();
            permitClient.ready();
            viewport.ready();
            refreshLocalManagementAsync();
            startUpdate();
        } catch (Exception e) {
            showInitializationFailure(e);
        }
    }

    public void requestClose() {
        if (arguments.preview() || launchPermitted || restartPending) {
            exitApplication();
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

    public void retry() {
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
                log.warn("Player chose to launch with locally changed managed files");
                viewport.showLocalContentOverrideLaunch();
                startAutoCloseCountdown();
            } catch (Exception e) {
                log.error("Unable to grant launch permission after local content override", e);
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
            log.error("Unable to save local mod preference", e);
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
            log.error("Unable to restore local mod defaults", e);
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
            log.error("Unable to save local file preference", e);
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
            log.error("Unable to restore local file defaults", e);
            showFailure("无法恢复文件管理设置", e);
        }
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
        localModManager = new LocalModManager(arguments.instanceRoot(), playerHome);
        localFileManager = new LocalFileManager(playerHome);
        viewport.setPlayerIdentity(arguments.playerName());
        viewport.setBranding(binding.fallbackBranding());
        viewport.setBackground(resolveBundledCover(binding));
        viewport.setReleaseHistory(releaseHistoryClient.loadCached(binding, playerHome));
        log.info("Player updater started for project " + binding.projectId());
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
                PlayerProgramUpdateResult programResult = new PlayerProgramUpdater()
                        .checkAndInstall(updateRequest(snapshot, cancellation),
                                PlayerApplication.BOOTSTRAP_AGENT_VERSION, null, progress);
                if (programResult.outcome() == PlayerProgramUpdateOutcome.CHECK_UNAVAILABLE) {
                    log.info("Player updater version check is unavailable; continuing with the modpack check");
                } else if (programResult.outcome() == PlayerProgramUpdateOutcome.NOT_PUBLISHED) {
                    log.info("No player updater program has been published for this project");
                }
                if (programResult.restartRequired()) {
                    working = false;
                    log.info("Player updater " + programResult.manifest().version()
                            + " installed; restarting through the bootstrap Agent");
                    restartWithUpdatedProgram(programResult);
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
                if (!result.releasedPaths().isEmpty()) {
                    log.info("Remote management released "
                            + result.releasedPaths().size()
                            + " files and kept local copies");
                    result.releasedPaths().forEach(path ->
                            log.info("Released managed file: " + path));
                }
                List<LocalModEntry> mods = localModManager.scan(result.release());
                List<LocalFileEntry> files = localFileManager.scan(result.release());
                viewport.setLocalMods(mods);
                viewport.setLocalFiles(files);
                finishSuccessfully(result);
                refreshReleaseHistory();
            } catch (Exception e) {
                working = false;
                if (handleUnverifiedOfflineLaunch(e)) return;
                log.error("Update failed", e);
                showFailure(errorTitle(e), e);
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
                PlayerApplication.VERSION, Set.of(
                ProtocolConstants.CAPABILITY_FORCED_DIRECTORY_SYNC,
                ProtocolConstants.CAPABILITY_FORCED_FILE_SYNC,
                ProtocolConstants.CAPABILITY_RELEASED_PATHS),
                null, null, null, cancellation, snapshot.overrides());
    }

    private UpdateResult mergeResults(UpdateResult previous, UpdateResult current) {
        if (previous == null) return current;
        List<Path> installed = combinePaths(previous.installedPaths(), current.installedPaths());
        List<Path> deleted = combinePaths(previous.deletedPaths(), current.deletedPaths());
        List<Path> archived = combinePaths(previous.archivedFiles(), current.archivedFiles());
        List<Path> released = combinePaths(previous.releasedPaths(), current.releasedPaths());
        return new UpdateResult(
                current.outcome(), current.release(), installed.size(), deleted.size(),
                previous.downloadedBytes() + current.downloadedBytes(),
                current.unmanagedMods(), archived,
                current.archiveDirectory() != null
                        ? current.archiveDirectory() : previous.archiveDirectory(),
                installed, deleted, released);
    }

    private static List<Path> combinePaths(List<Path> first, List<Path> second) {
        java.util.LinkedHashSet<Path> paths = new java.util.LinkedHashSet<>(first);
        paths.addAll(second);
        return List.copyOf(paths);
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
            } catch (IOException e) {
                log.error("Unable to scan local management settings", e);
            }
        });
    }

    private void refreshReleaseHistory() {
        Thread.ofVirtual().name("release-history").start(() -> {
            try {
                ReleaseHistory history = releaseHistoryClient.fetch(binding, playerHome);
                viewport.setReleaseHistory(history);
            } catch (IOException e) {
                log.info("Release history is unavailable; using locally cached records");
            }
        });
    }

    private void restartWithUpdatedProgram(PlayerProgramUpdateResult result) {
        restartPending = true;
        long downloaded = result.downloadedBytes();
        viewport.showProgress(new ProgressEvent(UpdateStage.COMPLETE,
                "更新器升级完成", "正在切换到新版本", downloaded, downloaded));
        scheduler.schedule(this::exitApplication, 350, TimeUnit.MILLISECONDS);
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
                viewport.showProgress(event);
            }
        };
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
        if (!PlayerApplication.allowsUnverifiedOfflineLaunch(failure)) return false;
        try (GameUpdateLock gameLock = acquireGameUpdateLock()) {
            permitClient.allow(gameLock::close);
            launchPermitted = true;
        } catch (Exception permitFailure) {
            log.error("Unable to grant unverified offline launch permission", permitFailure);
            showFailure(errorTitle(permitFailure), permitFailure);
            return true;
        }
        log.warn("Update service unavailable; launch granted without validating this installation");
        viewport.showUnverifiedOfflineLaunch();
        startAutoCloseCountdown();
        return true;
    }

    private void showFailure(String title, Throwable error) {
        String detail = error.getMessage() == null || error.getMessage().isBlank()
                ? "请查看日志后重试"
                : error.getMessage();
        viewport.showError(title, detail, PlayerApplication.allowsLocalContentOverride(error));
    }

    private void showInitializationFailure(Exception error) {
        if (log != null) log.error("Player updater initialization failed", error);
        if (binding == null) {
            binding = new ProjectBinding(1, "unknown", "http://127.0.0.1", "invalid",
                    "DreamingFishUpdater", null, Branding.empty());
            viewport.setBranding(Branding.empty());
            viewport.setBackground(null);
        }
        viewport.showError(error instanceof BootstrapPermitClient.PermitException
                        ? "启动许可已失效" : "更新器无法启动",
                error.getMessage(), false);
        viewport.ready();
    }

    private void closeAndDeny(String reason) {
        permitClient.deny(reason);
        exitApplication();
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
            case LOCAL_CONTENT_CHANGED -> "本地托管文件已变更";
            case LOCAL_STATE_INVALID -> "本地整合包需要修复";
            case PATH_UNSAFE -> "发布中包含不安全路径";
            case RECOVERY_FAILED, TRANSACTION_FAILED -> "更新事务未能完成";
            case CANCELLED -> "更新已取消";
            default -> "更新失败";
        };
    }

}
