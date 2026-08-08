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
                    "Checking for updates", null, 0, 0));
            SignedRelease target;
            try {
                target = manifestFetcher.fetch(request, publicKey,
                        local == null ? null : local.trustState());
            } catch (UpdateException e) {
                if (e.code() != UpdateErrorCode.NETWORK_UNAVAILABLE) throw e;
                return allowOfflineOrFail(paths, local, request, progress, e);
            }

            LocalFileOverrides effectiveOverrides = request.localFileOverrides()
                    .withForcedManagement(
                            target.manifest().forcedSyncFiles(),
                            target.manifest().forcedSyncDirectories());
            UpdatePlan plan = planner.create(paths, target, local, effectiveOverrides, progress,
                    request.cancellationToken());
            boolean sameRelease = local != null && local.release().sha256().equals(target.sha256());
            if (sameRelease && plan.operations().isEmpty()) {
                if (gameUpdateLock != null) {
                    syncMusicTracks(request, paths, target.manifest().branding().musicTracks(),
                            progress, local == null ? null : local.release().manifest().branding().musicTracks());
                }
                persistBundledBaseline(paths, local);
                storageMaintenance.cleanObjectCache(paths);
                progress.onProgress(new ProgressEvent(UpdateStage.COMPLETE,
                        "Installation is up to date", null, 1, 1));
                return new UpdateResult(UpdateOutcome.UP_TO_DATE, target.manifest(),
                        0, 0, 0, plan.unmanagedMods(), List.of(), null,
                        List.of(), List.of(), plan.releasedPaths());
            }

            if (gameUpdateLock == null) throw gameRunning();

            long downloaded = downloader.download(request, paths, plan.requiredObjects(), progress);
            syncMusicTracks(request, paths, target.manifest().branding().musicTracks(),
                    progress, local == null ? null : local.release().manifest().branding().musicTracks());
            progress.onProgress(new ProgressEvent(UpdateStage.PREPARING,
                    "Preparing update transaction", null, 0, plan.operations().size()));
            InstallResult installResult = installer.install(
                    paths, plan, progress, effectiveOverrides,
                    request.cancellationToken());
            storageMaintenance.cleanObjectCache(paths);
            progress.onProgress(new ProgressEvent(UpdateStage.COMPLETE,
                    "Update installed", null, 1, 1));
            return new UpdateResult(UpdateOutcome.UPDATED, target.manifest(),
                    plan.installCount(), plan.deleteCount(), downloaded, plan.unmanagedMods(),
                    installResult.archivedFiles(), installResult.archiveDirectory(),
                    plan.paths(OperationKind.INSTALL), plan.paths(OperationKind.DELETE),
                    plan.releasedPaths());
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
                "Update service unavailable; verifying the last installation", null, 0, 0));
        LocalFileOverrides effectiveOverrides = request.localFileOverrides()
                .withForcedManagement(
                        local.release().manifest().forcedSyncFiles(),
                        local.release().manifest().forcedSyncDirectories());
        if (!localStore.verifyFiles(paths, local, progress, effectiveOverrides,
                request.cancellationToken())) {
            throw new UpdateException(UpdateErrorCode.LOCAL_CONTENT_CHANGED,
                    "The update service is unavailable and managed files were changed locally",
                    networkFailure);
        }
        UpdatePlan localPlan = planner.create(paths, local.release(), local,
                effectiveOverrides, progress,
                request.cancellationToken());
        if (!localPlan.operations().isEmpty()) {
            throw new UpdateException(UpdateErrorCode.LOCAL_CONTENT_CHANGED,
                    "The offline installation has managed files that require repair", networkFailure);
        }
        persistBundledBaseline(paths, local);
        progress.onProgress(new ProgressEvent(UpdateStage.OFFLINE,
                "Using the last verified installation", null, 1, 1));
        return new UpdateResult(UpdateOutcome.OFFLINE_ALLOWED, local.release().manifest(),
                0, 0, 0, localPlan.unmanagedMods(), List.of(), null,
                List.of(), List.of(), List.of());
    }

    private void persistBundledBaseline(EnginePaths paths, LocalInstallation local) {
        if (!local.bundledBaseline()) return;
        try {
            localStore.save(paths, local.release());
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
