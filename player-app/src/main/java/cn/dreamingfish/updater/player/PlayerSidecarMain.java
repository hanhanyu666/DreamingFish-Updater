package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.engine.ProgressEvent;
import cn.dreamingfish.updater.engine.UpdateResult;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ReleaseHistory;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Headless JSON-lines sidecar used by the Tauri player window. */
public final class PlayerSidecarMain {
    private static final JsonCodec JSON = new JsonCodec();
    private static final ObjectMapper TREES = new ObjectMapper();

    private PlayerSidecarMain() {
    }

    public static void main(String[] args) throws Exception {
        PlayerArguments arguments = PlayerArguments.parse(List.of(args));
        JsonViewPort viewport = new JsonViewPort();
        PlayerController controller = new PlayerController(
                arguments, viewport, viewport::exitProcess);
        Thread.ofPlatform().daemon().name("sidecar-stdin")
                .start(() -> commandLoop(viewport, controller));
        if (arguments.preview()) {
            viewport.startPreview();
        } else {
            controller.start();
        }
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void commandLoop(JsonViewPort viewport, PlayerController controller) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8))) {
            runCommandLoop(reader, viewport::completeConfirm,
                    root -> handleControllerCommand(root, controller),
                    viewport::showCommandFailure, controller::exitApplication);
        } catch (IOException ignored) {
            // runCommandLoop owns EOF/error cleanup once the reader is created.
        }
    }

    /**
     * Keeps stdin responsive while a controller command is waiting for a UI confirmation.
     * Confirm replies are completed on the reader thread; all business commands run in a
     * single ordered executor because {@link PlayerController} is intentionally stateful.
     */
    static void runCommandLoop(BufferedReader reader,
                               BiConsumer<Integer, Boolean> confirmHandler,
                               Consumer<JsonNode> controllerHandler,
                               Consumer<RuntimeException> commandErrorHandler,
                               Runnable eofAction) throws IOException {
        ExecutorService commands = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sidecar-commands");
            thread.setDaemon(true);
            return thread;
        });
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode root = TREES.readTree(line);
                    if ("confirm".equals(root.path("command").asText(""))) {
                        confirmHandler.accept(root.path("id").asInt(-1),
                                root.path("accepted").asBoolean(false));
                    } else {
                        commands.execute(() -> {
                            try {
                                controllerHandler.accept(root);
                            } catch (RuntimeException error) {
                                // Report the failed action but keep later ordered commands alive.
                                try {
                                    commandErrorHandler.accept(error);
                                } catch (RuntimeException ignored) {
                                    // A broken error reporter must not terminate command dispatch.
                                }
                            }
                        });
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A malformed JSON line must not kill the command reader.
                }
            }
        } finally {
            commands.shutdown();
            try {
                if (!commands.awaitTermination(2, TimeUnit.SECONDS)) {
                    commands.shutdownNow();
                }
            } catch (InterruptedException e) {
                commands.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                eofAction.run();
            }
        }
    }

    private static void handleControllerCommand(JsonNode root,
                                                PlayerController controller) {
        String command = root.path("command").asText("");
        switch (command) {
            case "retry" -> controller.retry();
            case "continue-launch" -> controller.continueLaunch();
            case "toggle-mod" -> {
                LocalModEntry entry = decode(root.path("entry"), LocalModEntry.class);
                controller.changeLocalModPreference(entry, root.path("disabled").asBoolean(false));
            }
            case "restore-mods" -> controller.restoreLocalModDefaults();
            case "toggle-file" -> {
                LocalFileEntry entry = decode(root.path("entry"), LocalFileEntry.class);
                controller.changeLocalFilePreference(entry, root.path("managed").asBoolean(false));
            }
            case "restore-files" -> controller.restoreLocalFileDefaults();
            case "toggle-group" -> {
                JsonNode enabled = root.path("enabled");
                controller.changeOptionalGroup(root.path("groupId").asText(""),
                        enabled.isBoolean() ? enabled.asBoolean() : null);
            }
            case "reset-default" -> {
                LocalFileEntry entry = decode(root.path("entry"), LocalFileEntry.class);
                controller.requestDefaultReset(entry);
            }
            case "archives" -> controller.refreshArchives();
            case "restore-archive" -> controller.restoreArchivedFile(
                    root.path("archiveId").asText(""), root.path("path").asText(""));
            case "delete-archive" -> controller.deleteArchive(root.path("archiveId").asText(""));
            case "open-directory" -> controller.openPlayerDirectory();
            case "open-archive" -> {
                String archiveId = root.path("archiveId").asText("");
                if (archiveId.isBlank()) controller.openArchiveDirectory();
                else controller.openArchive(archiveId);
            }
            case "keep-open" -> controller.keepWindowOpen();
            case "close" -> controller.requestClose();
            case "quit" -> controller.exitApplication();
            default -> {
                // Unknown commands are ignored for forward compatibility.
            }
        }
    }

    private static <T> T decode(JsonNode node, Class<T> type) {
        return JSON.read(node.toString().getBytes(StandardCharsets.UTF_8), type);
    }

    private record ProgressDto(String stage, String message, String currentPath,
                               long completedBytes, long totalBytes, double fraction) {
    }

    private record ProgressMessage(String type, ProgressDto event) {
    }

    private record ResultDto(String releaseId, long sequence, String projectId, String createdAt,
                             String outcome, String displayVersion, String changelog,
                             long downloadedBytes, List<String> installedPaths,
                             List<String> deletedPaths, List<String> archivedFiles,
                             List<String> releasedPaths, String archiveDirectory,
                             List<String> unmanagedMods, List<String> forcedSyncDirectories,
                             List<ArchivedDto> archived, List<String> keptModifiedPaths,
                             List<String> skippedSelfManagedPaths, List<String> resetPaths) {
    }

    private record ArchivedDto(String path, String reason, String reasonText, String detail,
                               long size, String componentId, String version, String restoredAt) {
        static ArchivedDto of(cn.dreamingfish.updater.engine.ArchivedFile file) {
            return new ArchivedDto(file.originalPath(), file.reason().name(),
                    file.reason().description(), file.detail(), file.size(), file.componentId(),
                    file.version(), file.restoredAt() == null ? null : file.restoredAt().toString());
        }
    }

    private record ArchiveDto(String id, boolean legacy, String createdAt, String releaseId,
                              String displayVersion, long totalBytes, List<ArchivedDto> files) {
    }

    private record ArchivesMessage(String type, List<ArchiveDto> archives) {
    }

    private record GroupsMessage(String type, List<OptionalGroupView> groups) {
    }

    private record ResultMessage(String type, ResultDto result) {
    }

    private record BrandingMessage(String type, Branding branding) {
    }

    private record IdentityMessage(String type, String name) {
    }

    private record BackgroundMessage(String type, String path) {
    }

    private record LogsMessage(String type, List<String> lines) {
    }

    private record LogMessage(String type, String line) {
    }

    private record HistoryMessage(String type, ReleaseHistory history) {
    }

    private record ModsMessage(String type, List<LocalModEntry> entries) {
    }

    private record FilesMessage(String type, List<LocalFileEntry> entries) {
    }

    private record ErrorMessage(String type, String title, String detail, boolean allowContinue) {
    }

    private record CountdownMessage(String type, int seconds) {
    }

    private record LaunchKeptOpenMessage(String type) {
    }

    private record ConfirmRequestDto(int id, String tone, String title, String heading,
                                     String message, String actionText, String cancelText) {
    }

    private record ConfirmRequestMessage(String type, ConfirmRequestDto request) {
    }

    private record OpenRequestMessage(String type, String kind, String value) {
    }

    private record ReadyMessage(String type) {
    }

    private record ExitMessage(String type) {
    }

    private static final class JsonViewPort implements PlayerViewPort {
        private final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                System.out, StandardCharsets.UTF_8));
        private final Map<Integer, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger confirmIds = new AtomicInteger(1);
        private final AtomicBoolean exitEmitted = new AtomicBoolean();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "sidecar-preview");
                    thread.setDaemon(true);
                    return thread;
                });

        private synchronized void emit(Object message) {
            try {
                writer.write(JSON.writeString(message));
                writer.newLine();
                writer.flush();
            } catch (IOException ignored) {
                // The host window is gone; nothing useful can be delivered.
            }
        }

        void startPreview() {
            emit(new IdentityMessage("identity", "Player"));
            emit(new BrandingMessage("branding", Branding.empty()));
            emit(new BackgroundMessage("background", null));
            emit(new LogsMessage("logs", List.of(
                    "2026-08-13 12:08:40.210 | START | 启动 | 玩家端 "
                            + PlayerRuntime.VERSION + " · 项目 preview",
                    "2026-08-13 12:08:41.035 | INFO  | 检查更新 | 已连接到整合包更新服务",
                    "2026-08-13 12:08:42.184 | INFO  | 下载文件 | 正在下载 mods/dreamingfish-core.jar",
                    "2026-08-13 12:08:43.420 | WARN  | 网络 | 当前下载速度较慢，正在继续尝试")));
            emit(new ReadyMessage("ready"));
            scheduler.schedule(() -> emit(new ProgressMessage("progress",
                    new ProgressDto("CHECKING", "正在连接更新服务", null, 0, 0, -1))),
                    400, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> emit(new ProgressMessage("progress",
                    new ProgressDto("DOWNLOADING", "正在下载更新",
                            "mods/dreamingfish-core.jar",
                            184L * 1024 * 1024, 271L * 1024 * 1024, 184.0 / 271.0))),
                    900, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> emit(new ProgressMessage("progress",
                    new ProgressDto("DOWNLOADING", "正在下载更新",
                            "config/dreamingfish/client.toml",
                            271L * 1024 * 1024, 271L * 1024 * 1024, 1.0))),
                    1500, TimeUnit.MILLISECONDS);
            scheduler.schedule(this::emitPreviewResult, 1700, TimeUnit.MILLISECONDS);
        }

        private void emitPreviewResult() {
            emit(new ModsMessage("mods", List.of(
                    new LocalModEntry("component:renderer", "旧版渲染优化",
                            "mods/legacy-renderer.jar", "renderer", true, true, false, false,
                            "0.9.2", "SYNC", null, null, null, null),
                    new LocalModEntry("component:dreamingfish", "DreamingFish Core",
                            "mods/dreamingfish-core.jar", "dreamingfish", true, false, true, true,
                            "2.4.0", "REQUIRED", null, null, "服主设为必需同步，不能在本机停用", null),
                    new LocalModEntry("component:iris", "Iris Shaders",
                            "mods/iris.jar", "iris", true, false, true, true,
                            "1.7.2", "SYNC", "visuals", "光影与美化", "由可选内容“光影与美化”统一开关", null),
                    new LocalModEntry("component:embeddium-options-api", "Embeddium Options API",
                            "mods/embeddium-options-api.jar", "embeddium-options-api",
                            false, false, true, false, "1.0.3", null, null, null, null, null),
                    new LocalModEntry("component:xaerominimap", "Xaero's Minimap",
                            "mods/xaeros-minimap.jar", "xaerominimap",
                            false, false, true, false, "24.2.0", null, null, null, null,
                            "这个版本会导致进入主城时崩溃，请换用 24.3.0"))));
            emit(new FilesMessage("files", previewFiles()));
            emit(new ResultMessage("result", new ResultDto(
                    "r000012", 12, "preview",
                    Instant.now().minusSeconds(86_400).toString(),
                    "UPDATED", "1.20.1-r12", "更新模组与资源包",
                    271L * 1024 * 1024,
                    List.of("mods/dreamingfish-core.jar", "mods/dreamingfish-world.jar",
                            "config/dreamingfish/client.toml"),
                    List.of("mods/legacy-renderer.jar"),
                    List.of("config/dreamingfish/client.toml"), List.of(),
                    "DreamingFishUpdater/backups/archive/preview",
                    List.of("mods/embeddium-options-api.jar", "mods/xaeros-minimap.jar"),
                    List.of(), List.of(new ArchivedDto("config/dreamingfish/client.toml",
                            "REPLACED_MODIFIED",
                            cn.dreamingfish.updater.engine.ArchiveReason.REPLACED_MODIFIED.description(),
                            "", 2048, null, null, null)),
                    List.of("config/xaerominimap.txt"), List.of("mods/xaeros-minimap.jar"),
                    List.of())));
            emit(new GroupsMessage("groups", List.of(
                    new OptionalGroupView("visuals", "光影与美化", "光影、粒子和动画效果，显卡较弱或内存小于 8G 建议关闭",
                            true, true, false, List.of("Iris Shaders", "Particle Rain", "Not Enough Animations")),
                    new OptionalGroupView("minimap", "小地图", "右上角小地图与路径点", false, false, false,
                            List.of("Xaero's Minimap")))));
            emit(new ArchivesMessage("archives", List.of(new ArchiveDto("preview", false,
                    Instant.now().minusSeconds(3_600).toString(), "r000012", "1.20.1-r12", 2048,
                    List.of(new ArchivedDto("config/dreamingfish/client.toml", "REPLACED_MODIFIED",
                            cn.dreamingfish.updater.engine.ArchiveReason.REPLACED_MODIFIED.description(),
                            "", 2048, null, null, null))))));
            emit(new HistoryMessage("history", new ReleaseHistory(1, "preview", List.of(
                    new cn.dreamingfish.updater.protocol.ReleaseHistoryEntry(
                            "r000012", 12, "1.20.1-r12",
                            Instant.now().minusSeconds(86_400), "更新模组与资源包"),
                    new cn.dreamingfish.updater.protocol.ReleaseHistoryEntry(
                            "r000011", 11, "1.20.1-r11",
                            Instant.now().minusSeconds(172_800), "修复部分任务无法完成的问题")))));
            // Preview mode stays open so the window can be inspected; real launches
            // still auto-close after the launch countdown.
            emit(new LaunchKeptOpenMessage("launch-kept-open"));
        }

        private static List<LocalFileEntry> previewFiles() {
            return List.of(
                    new LocalFileEntry("config", "config", true, false, null, false, true, false, null, 2),
                    new LocalFileEntry("config/dreamingfish", "dreamingfish", true, true, null,
                            false, true, false, null, 1),
                    new LocalFileEntry("config/dreamingfish/client.toml", "client.toml", false,
                            false, "config/dreamingfish", false, true, false,
                            cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, 0, null, "SYNC",
                            null, null, null, false),
                    new LocalFileEntry("config/voice.toml", "voice.toml", false, false, null,
                            false, true, false, cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, 0,
                            null, "DEFAULT_CONFIG", null, null, true, false),
                    new LocalFileEntry("mods", "mods", true, false, null, false, true, false, null, 2),
                    new LocalFileEntry("mods/dreamingfish-core.jar", "DreamingFish Core", false,
                            false, null, false, true, true,
                            cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, 0, "dreamingfish",
                            "REQUIRED", null, "服主设为必需同步，不能取消管理", null, false),
                    new LocalFileEntry("mods/dreamingfish-world.jar", "DreamingFish World", false,
                            false, null, false, true, false,
                            cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, 0, "dreamingfish-world",
                            "SYNC", null, null, null, false),
                    new LocalFileEntry("defaultconfigs", "defaultconfigs", true, false, null,
                            false, true, true, null, 1),
                    new LocalFileEntry("defaultconfigs/server.toml", "server.toml", false,
                            false, null, false, true, true,
                            cn.dreamingfish.updater.protocol.FilePolicy.ENFORCED, 0, null, "REQUIRED",
                            null, "服主设为必需同步，不能取消管理", null, false));
        }

        @Override
        public void setPlayerIdentity(String name) {
            emit(new IdentityMessage("identity", name));
        }

        @Override
        public void setBranding(Branding branding) {
            emit(new BrandingMessage("branding", branding));
        }

        @Override
        public void setBackground(Path localCover) {
            emit(new BackgroundMessage("background", localCover == null ? null : localCover.toString()));
        }

        @Override
        public void setLogs(List<String> lines) {
            emit(new LogsMessage("logs", lines == null ? List.of() : List.copyOf(lines)));
        }

        @Override
        public void setReleaseHistory(ReleaseHistory history) {
            emit(new HistoryMessage("history", history));
        }

        @Override
        public void appendLog(String line) {
            emit(new LogMessage("log", line));
        }

        @Override
        public void showProgress(ProgressEvent event) {
            double fraction = event.totalBytes() > 0
                    ? Math.min(1.0, Math.max(0.0, (double) event.completedBytes() / event.totalBytes()))
                    : -1.0;
            emit(new ProgressMessage("progress", new ProgressDto(
                    event.stage().name(), event.message(), event.currentPath(),
                    event.completedBytes(), event.totalBytes(), fraction)));
        }

        @Override
        public void showResult(UpdateResult result) {
            ReleaseManifest release = result.release();
            emit(new ResultMessage("result", new ResultDto(
                    release.releaseId(), release.sequence(), release.projectId(),
                    release.createdAt() == null ? null : release.createdAt().toString(),
                    result.outcome().name(), release.displayVersion(), release.changelog(),
                    result.downloadedBytes(),
                    paths(result.installedPaths()), paths(result.deletedPaths()),
                    paths(result.archivedFiles()), paths(result.releasedPaths()),
                    result.archiveDirectory() == null ? null : result.archiveDirectory().toString(),
                    paths(result.unmanagedMods()),
                    cn.dreamingfish.updater.protocol.MaintenanceModel.of(release).cleanupDirectories(),
                    result.archived().stream().map(ArchivedDto::of).toList(),
                    paths(result.keptModifiedPaths()), paths(result.skippedSelfManagedPaths()),
                    paths(result.resetPaths()))));
        }

        @Override
        public void setOptionalGroups(List<OptionalGroupView> groups) {
            emit(new GroupsMessage("groups", groups == null ? List.of() : List.copyOf(groups)));
        }

        @Override
        public void setArchives(List<cn.dreamingfish.updater.engine.ArchiveCatalog.Archive> archives) {
            List<ArchiveDto> values = archives == null ? List.of() : archives.stream()
                    .map(archive -> new ArchiveDto(archive.id(), archive.legacy(),
                            archive.createdAt() == null ? null : archive.createdAt().toString(),
                            archive.releaseId(), archive.displayVersion(), archive.totalBytes(),
                            archive.files().stream().map(ArchivedDto::of).toList()))
                    .toList();
            emit(new ArchivesMessage("archives", values));
        }

        private static List<String> paths(List<Path> values) {
            if (values == null) return List.of();
            return values.stream()
                    .map(Path::normalize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .toList();
        }

        @Override
        public void showUnverifiedOfflineLaunch() {
            emit(Map.of("type", "unverified-offline"));
        }

        @Override
        public void showLocalContentOverrideLaunch() {
            emit(Map.of("type", "local-content-override"));
        }

        @Override
        public void showError(String title, String detail, boolean allowContinue) {
            emit(new ErrorMessage("error", title, detail, allowContinue));
        }

        @Override
        public void setLocalMods(List<LocalModEntry> mods) {
            emit(new ModsMessage("mods", mods == null ? List.of() : List.copyOf(mods)));
        }

        @Override
        public void setLocalFiles(List<LocalFileEntry> files) {
            emit(new FilesMessage("files", files == null ? List.of() : List.copyOf(files)));
        }

        @Override
        public void showLaunchCountdown(int seconds) {
            emit(new CountdownMessage("countdown", seconds));
        }

        @Override
        public void showLaunchKeptOpen() {
            emit(new LaunchKeptOpenMessage("launch-kept-open"));
        }

        @Override
        public boolean confirmDialog(DialogTone tone, String title, String heading, String message,
                                     String actionText, String cancelText) {
            int id = confirmIds.getAndIncrement();
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pending.put(id, future);
            emit(new ConfirmRequestMessage("confirm-request", new ConfirmRequestDto(
                    id, tone.name(), title, heading, message, actionText, cancelText)));
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                return false;
            }
        }

        void completeConfirm(int id, boolean accepted) {
            CompletableFuture<Boolean> future = pending.remove(id);
            if (future != null) future.complete(accepted);
        }

        void showCommandFailure(RuntimeException error) {
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
            System.err.println("Player sidecar command failed: " + detail);
            emit(new ErrorMessage("error", "操作未能完成", detail, false));
        }

        @Override
        public void openPlayerDirectory(Path playerHome) {
            emit(new OpenRequestMessage("open-request", "directory", playerHome.toString()));
        }

        @Override
        public void openArchiveDirectory(Path archiveDirectory) {
            emit(new OpenRequestMessage("open-request", "archive", archiveDirectory.toString()));
        }

        @Override
        public void openExternalLink(URI uri) {
            emit(new OpenRequestMessage("open-request", "external", uri.toString()));
        }

        @Override
        public void fadeOut(long durationMillis, Runnable finished) {
            scheduler.schedule(() -> {
                emitExit();
                finished.run();
            }, durationMillis, TimeUnit.MILLISECONDS);
        }

        void exitProcess() {
            emitExit();
            System.exit(0);
        }

        private void emitExit() {
            if (exitEmitted.compareAndSet(false, true)) {
                emit(new ExitMessage("exit"));
            }
        }

        @Override
        public void ready() {
            emit(new ReadyMessage("ready"));
        }
    }
}
