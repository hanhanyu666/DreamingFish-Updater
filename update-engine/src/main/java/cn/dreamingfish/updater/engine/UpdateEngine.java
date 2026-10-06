package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.ManifestValidator;
import cn.dreamingfish.updater.protocol.ProjectBinding;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.SemanticVersion;
import cn.dreamingfish.updater.protocol.PlayerMusicTrack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class UpdateEngine {
    private final LocalInstallationStore localStore;
    private final ManifestFetcher manifestFetcher;
    private final UpdatePlanner planner;
    private final ObjectDownloader downloader;
    private final TransactionInstaller installer;
    private final PlayerStorageMaintenance storageMaintenance;

    public UpdateEngine() {
        this(TransactionFaultInjector.NONE);
    }

    UpdateEngine(TransactionFaultInjector faultInjector) {
        localStore = new LocalInstallationStore();
        manifestFetcher = new ManifestFetcher();
        planner = new UpdatePlanner();
        downloader = new ObjectDownloader();
        installer = new TransactionInstaller(localStore, faultInjector);
        storageMaintenance = new PlayerStorageMaintenance();
    }

    public UpdateResult update(UpdateRequest request, ProgressListener listener) {
        ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
        PublicKey publicKey = validateRequest(request);
        EnginePaths paths = EnginePaths.of(request.instanceRoot(), request.playerHome());
        try {
            paths.createDirectories();
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to create player updater directories", e);
        }

        try (InstanceUpdateLock ignored = InstanceUpdateLock.acquire(paths.instanceLock());
             GameUpdateLock gameUpdateLock = GameUpdateLock.tryAcquire(paths.gameLock())) {
            storageMaintenance.cleanExpiredStaging(paths);
            if (gameUpdateLock == null && installer.hasPendingTransactions(paths)) {
                throw gameRunning();
            }
            installer.recover(paths, progress);
            Optional<LocalInstallation> optionalLocal = localStore.loadMetadata(paths,
                    request.binding(), publicKey, request.supportedCapabilities());
            LocalInstallation local = optionalLocal.orElseGet(() -> localStore.loadBundledBaseline(
                    paths, request.binding(), publicKey, request.supportedCapabilities()));

            progress.onProgress(new ProgressEvent(UpdateStage.CHECKING,
                    "正在检查整合包更新", null, 0, 0));
            SignedRelease target;
            try {
                target = manifestFetcher.fetch(request, publicKey,
                        local == null ? null : local.trustState());
            } catch (UpdateException e) {
                if (e.code() != UpdateErrorCode.NETWORK_UNAVAILABLE) throw e;
                return allowOfflineOrFail(paths, local, request, progress, e);
            }
            if (local != null && cn.dreamingfish.updater.protocol.MaintenanceModel.of(local.release().manifest()).simplified()
                    && !cn.dreamingfish.updater.protocol.MaintenanceModel.of(target.manifest()).simplified()) {
                throw new UpdateException(UpdateErrorCode.INVALID_MANIFEST,
                        "这个项目已采用简化维护规则，新发布不能降回旧协议；请升级管理端后重新发布");
            }

            LocalFileOverrides choices = request.localFileOverrides();
            LocalFileIndex index = LocalFileIndex.load(paths.fileIndex());
            UpdatePlan plan = planner.create(paths, target, local, choices, index, progress,
                    request.cancellationToken());
            boolean sameRelease = local != null && local.release().sha256().equals(target.sha256());
            if (sameRelease && plan.operations().isEmpty()) {
                if (gameUpdateLock != null) {
                    syncMusicTracks(request, paths, target.manifest().branding().musicTracks(),
                            progress, local == null ? null : local.release().manifest().branding().musicTracks());
                    persistBundledBaseline(paths, local, plan.nextState());
                }
                index.save();
                storageMaintenance.cleanObjectCache(paths);
                progress.onProgress(new ProgressEvent(UpdateStage.COMPLETE,
                        "本地整合包已是最新版本", null, 1, 1));
                return new UpdateResult(UpdateOutcome.UP_TO_DATE, target.manifest(),
                        0, 0, 0, plan.unmanagedMods(), List.of(), null,
                        List.of(), List.of(), plan.releasedPaths(), List.of(),
                        plan.keptModifiedPaths(), plan.skippedSelfManagedPaths(), List.of());
            }

            if (gameUpdateLock == null) throw gameRunning();

            long downloaded = downloader.download(request, paths, plan.requiredObjects(), progress);
            syncMusicTracks(request, paths, target.manifest().branding().musicTracks(),
                    progress, local == null ? null : local.release().manifest().branding().musicTracks());
            progress.onProgress(new ProgressEvent(UpdateStage.PREPARING,
                    "正在准备安全更新", null, 0, plan.operations().size()));
            InstallResult installResult = installer.install(
                    paths, plan, progress, choices, index,
                    request.cancellationToken());
            index.save();
            storageMaintenance.cleanObjectCache(paths);
            progress.onProgress(new ProgressEvent(UpdateStage.COMPLETE,
                    "整合包更新完成", null, 1, 1));
            return new UpdateResult(UpdateOutcome.UPDATED, target.manifest(),
                    plan.installCount(), plan.deleteCount(), downloaded, plan.unmanagedMods(),
                    installResult.archivedFiles(), installResult.archiveDirectory(),
                    plan.paths(OperationKind.INSTALL), plan.paths(OperationKind.DELETE),
                    plan.releasedPaths(), installResult.archived(),
                    plan.keptModifiedPaths(), plan.skippedSelfManagedPaths(), plan.resetPaths());
        }
    }

    /** Synchronizes optional player music independently of managed Minecraft files. */
    private void syncMusicTracks(UpdateRequest request, EnginePaths paths,
                                 java.util.List<PlayerMusicTrack> tracks,
                                 ProgressListener listener,
                                 java.util.List<PlayerMusicTrack> previousTracks) {
        if (tracks == null) return;
        Set<String> retained = new HashSet<>();
        for (PlayerMusicTrack track : tracks) {
            retained.add(track.fileName().toLowerCase(java.util.Locale.ROOT));
            try {
                java.nio.file.Path target = paths.musicTrack(track.fileName());
                if (isValidMusic(target, track)) {
                    listener.onProgress(new ProgressEvent(UpdateStage.INSTALLING,
                            "音乐已是最新：" + track.title(), track.fileName(), 0, 0));
                    continue;
                }
                downloader.download(request, paths,
                        Map.of(track.sha256(), track.size()), listener);
                java.nio.file.Path source = paths.cacheObject(track.sha256());
                java.nio.file.Files.createDirectories(target.getParent());
                AtomicFileSupport.copyReplace(source, target);
                listener.onProgress(new ProgressEvent(UpdateStage.INSTALLING,
                        "已更新音乐：" + track.title(), track.fileName(), 0, 0));
            } catch (UpdateException error) {
                if (error.code() == UpdateErrorCode.CANCELLED) throw error;
                listener.onProgress(new ProgressEvent(UpdateStage.INSTALLING,
                        "音乐下载失败，已跳过：" + track.title(), track.fileName(), 0, 0));
            } catch (RuntimeException | java.io.IOException error) {
                listener.onProgress(new ProgressEvent(UpdateStage.INSTALLING,
                        "音乐下载失败，已跳过：" + track.title(), track.fileName(), 0, 0));
            }
        }
        if (previousTracks == null) return;
        for (PlayerMusicTrack previous : previousTracks) {
            if (retained.contains(previous.fileName().toLowerCase(java.util.Locale.ROOT))) continue;
            try {
                java.nio.file.Files.deleteIfExists(paths.musicTrack(previous.fileName()));
            } catch (RuntimeException | java.io.IOException ignored) {
                // Stale optional music is best-effort cleanup only.
            }
        }
    }

    private boolean isValidMusic(java.nio.file.Path path, PlayerMusicTrack track) {
        try {
            return java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && java.nio.file.Files.size(path) == track.size()
                    && cn.dreamingfish.updater.protocol.CryptoSupport.sha256(path)
                    .equals(track.sha256());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private UpdateException gameRunning() {
        return new UpdateException(UpdateErrorCode.GAME_RUNNING,
                "Minecraft is running in this instance; close it before applying or recovering an update");
    }

    private UpdateResult allowOfflineOrFail(EnginePaths paths, LocalInstallation local,
                                             UpdateRequest request, ProgressListener progress,
                                             UpdateException networkFailure) {
        if (local == null) {
            throw new UpdateException(UpdateErrorCode.NETWORK_UNAVAILABLE,
                    "The update service is unavailable and this instance has no verified installation",
                    networkFailure);
        }
        progress.onProgress(new ProgressEvent(UpdateStage.OFFLINE,
                "更新服务暂时不可用，正在验证上次安装", null, 0, 0));
        LocalFileOverrides choices = request.localFileOverrides();
        LocalFileIndex index = LocalFileIndex.load(paths.fileIndex());
        UpdatePlan localPlan = planner.create(paths, local.release(), local,
                choices, index, progress,
                request.cancellationToken());
        cn.dreamingfish.updater.protocol.MaintenanceModel model =
                cn.dreamingfish.updater.protocol.MaintenanceModel.of(local.release().manifest());
        boolean ownerActions = localPlan.operations().stream().anyMatch(operation ->
                operation.reason() == ArchiveReason.OWNER_REMOVED || operation.reason() == ArchiveReason.WITHDRAWN
                || (model.simplified() && (operation.reason() == ArchiveReason.CORRECTED
                || operation.reason() == ArchiveReason.RESET_DEFAULT || operation.reason() == ArchiveReason.CLEANUP
                || operation.reason() == ArchiveReason.DUPLICATE || model.locked(operation.path()))));
        ownerActions |= model.simplified() && localPlan.nextState().appliedCorrections().stream()
                .anyMatch(id -> local.state() == null || !local.state().correctionApplied(id));
        if (!localPlan.operations().isEmpty() && ownerActions
                && objectsAvailableOffline(paths, localPlan)) {
            InstallResult installed = installer.install(paths, localPlan, progress, choices, index, request.cancellationToken());
            index.save();
            return new UpdateResult(UpdateOutcome.OFFLINE_ALLOWED, local.release().manifest(),
                    localPlan.installCount(), localPlan.deleteCount(), 0, localPlan.unmanagedMods(),
                    installed.archivedFiles(), installed.archiveDirectory(), localPlan.paths(OperationKind.INSTALL),
                    localPlan.paths(OperationKind.DELETE), localPlan.releasedPaths(), installed.archived(),
                    localPlan.keptModifiedPaths(), localPlan.skippedSelfManagedPaths(), localPlan.resetPaths());
        }
        if (ownerActions) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "上次已验证发布要求移除或修复这些文件；完成处理前不能保留它们继续启动", networkFailure);
        }
        if (!localStore.verifyFiles(paths, local, progress, choices, index, request.cancellationToken())) {
            throw new UpdateException(UpdateErrorCode.LOCAL_CONTENT_CHANGED,
                    "The update service is unavailable and managed files were changed locally", networkFailure);
        }
        if (!localPlan.operations().isEmpty()) {
            throw new UpdateException(UpdateErrorCode.LOCAL_CONTENT_CHANGED,
                    "The offline installation has managed files that require repair", networkFailure);
        }
        index.save();
        persistBundledBaseline(paths, local, localPlan.nextState());
        progress.onProgress(new ProgressEvent(UpdateStage.OFFLINE,
                "已使用上次完整验证的本地版本", null, 1, 1));
        return new UpdateResult(UpdateOutcome.OFFLINE_ALLOWED, local.release().manifest(),
                0, 0, 0, localPlan.unmanagedMods(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), localPlan.keptModifiedPaths(),
                localPlan.skippedSelfManagedPaths(), List.of());
    }

    private boolean objectsAvailableOffline(EnginePaths paths, UpdatePlan plan) {
        try {
            for (var object : plan.requiredObjects().entrySet()) {
                Path file = paths.cacheObject(object.getKey());
                cn.dreamingfish.updater.protocol.PathSafety.assertSafePathTree(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != object.getValue()
                        || !cn.dreamingfish.updater.protocol.CryptoSupport.sha256(file).equals(object.getKey())) return false;
            }
            return true;
        } catch (IOException | RuntimeException unavailable) { return false; }
    }

    /**
     * Records a bundled baseline as the verified installation, together with
     * maintenance memory gathered without file changes.
     */
    private void persistBundledBaseline(EnginePaths paths, LocalInstallation local,
                                        MaintenanceState state) {
        boolean stateChanged = state != null && !state.equals(local.state());
        if (!local.bundledBaseline() && !stateChanged) return;
        try {
            if (local.bundledBaseline()) {
                localStore.save(paths, local.release(), state);
            } else {
                AtomicFileSupport.write(paths.maintenanceState(), new cn.dreamingfish.updater.protocol
                        .JsonCodec().writePretty(state));
            }
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID,
                    "Unable to activate the verified bundled release baseline", e);
        }
    }

    private PublicKey validateRequest(UpdateRequest request) {
        if (request == null) {
            throw new UpdateException(UpdateErrorCode.INVALID_BINDING, "Update request is missing");
        }
        try {
            PublicKey key = ManifestValidator.validateBinding(request.binding());
            SemanticVersion.parse(request.playerVersion());
            if (!Files.isDirectory(request.instanceRoot(), LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateException(UpdateErrorCode.INVALID_BINDING,
                        "Minecraft instance directory does not exist");
            }
            if (request.instanceRoot().equals(request.playerHome())) {
                throw new UpdateException(UpdateErrorCode.INVALID_BINDING,
                        "Player updater directory cannot be the instance root");
            }
            Path bootstrap = request.instanceRoot().resolve(".dreamingfish-bootstrap");
            if (request.playerHome().startsWith(bootstrap)) {
                throw new UpdateException(UpdateErrorCode.INVALID_BINDING,
                        "Player updater directory cannot be inside the bootstrap directory");
            }
            return key;
        } catch (UpdateException e) {
            throw e;
        } catch (ProtocolException | NullPointerException e) {
            throw new UpdateException(UpdateErrorCode.INVALID_BINDING,
                    "Project binding is invalid", e);
        }
    }
}
