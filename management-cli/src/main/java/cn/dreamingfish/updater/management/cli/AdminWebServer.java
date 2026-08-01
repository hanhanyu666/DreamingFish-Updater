package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.PreviewChange;
import cn.dreamingfish.updater.management.ProjectRecord;
import cn.dreamingfish.updater.management.ProjectRules;
import cn.dreamingfish.updater.management.PublishPreview;
import cn.dreamingfish.updater.management.RemovalDecision;
import cn.dreamingfish.updater.management.RemovalAction;
import cn.dreamingfish.updater.management.SourceFileService;
import cn.dreamingfish.updater.management.StoredPlayerProgram;
import cn.dreamingfish.updater.management.StoredRelease;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.JsonCodec;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class AdminWebServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final String TOKEN_HEADER = "X-DFS-Token";
    private static final Map<String, StaticAsset> STATIC_ASSETS = loadAssets();

    private final ManagementCli root;
    private final JsonCodec json = new JsonCodec();
    private final HttpServer server;
    private final ExecutorService executor;
    private final PublicServiceController publicService;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String sessionToken;

    AdminWebServer(ManagementCli root) {
        this(root, new InetSocketAddress("127.0.0.1", root.settings().webPort()));
    }

    AdminWebServer(ManagementCli root, InetSocketAddress address) {
        this.root = Objects.requireNonNull(root, "root");
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            throw new ManagementException("Web 管理界面只允许监听回环地址");
        }
        try {
            server = HttpServer.create(address, 0);
        } catch (IOException e) {
            if (e instanceof BindException) {
                throw new ManagementException("端口 " + address.getPort()
                        + " 已被占用，无法启动 Web 管理界面", e);
            }
            String detail = e.getMessage();
            throw new ManagementException("无法启动 Web 管理界面"
                    + (detail == null || detail.isBlank() ? "" : "：" + detail), e);
        }
        executor = Executors.newFixedThreadPool(4,
                Thread.ofPlatform().daemon().name("dfs-admin-web-", 0).factory());
        publicService = new PublicServiceController(root);
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        sessionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    InetSocketAddress address() {
        return server.getAddress();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
            } else {
                handleStatic(exchange, path);
            }
        } catch (WebApiException e) {
            sendJson(exchange, e.status, Map.of(
                    "error", e.code,
                    "message", e.getMessage()
            ));
        } catch (ManagementException | IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of(
                    "error", "invalid_request",
                    "message", usefulMessage(e)
            ));
        } catch (Exception e) {
            if (Boolean.getBoolean("dfs.debug")) e.printStackTrace(root.err());
            sendJson(exchange, 500, Map.of(
                    "error", "internal_error",
                    "message", "管理端处理请求时发生错误，请查看终端日志"
            ));
        } finally {
            exchange.close();
        }
    }

    private void handleStatic(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")
                && !exchange.getRequestMethod().equals("HEAD")) {
            throw new WebApiException(405, "method_not_allowed", "此资源只允许读取");
        }
        String resourcePath = path.equals("/") ? "/index.html" : path;
        StaticAsset asset = STATIC_ASSETS.get(resourcePath);
        if (asset == null) throw new WebApiException(404, "not_found", "页面不存在");
        securityHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", asset.contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        sendBytes(exchange, 200, asset.bytes);
    }

    private void handleApi(HttpExchange exchange, String path) throws Exception {
        if (mutating(exchange.getRequestMethod())) requireToken(exchange);
        if (path.equals("/api/session") && exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 200, Map.of(
                    "token", sessionToken,
                    "version", ManagementCli.VERSION,
                    "bindAddress", webAddress()
            ));
            return;
        }
        if (path.equals("/api/state") && exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 200, stateView());
            return;
        }
        if (path.equals("/api/settings") && exchange.getRequestMethod().equals("PUT")) {
            SettingsRequest request = readJson(exchange, SettingsRequest.class);
            sendJson(exchange, 200, mutate(() -> updateSettings(request)));
            return;
        }
        if (path.equals("/api/system/browse-path")
                && exchange.getRequestMethod().equals("POST")) {
            PathBrowseRequest request = readJson(
                    exchange, PathBrowseRequest.class);
            sendJson(exchange, 200, browsePath(request));
            return;
        }
        if (path.equals("/api/public-service/start")
                && exchange.getRequestMethod().equals("POST")) {
            sendJson(exchange, 200, mutate(publicService::start));
            return;
        }
        if (path.equals("/api/public-service/stop")
                && exchange.getRequestMethod().equals("POST")) {
            sendJson(exchange, 200, mutate(publicService::stop));
            return;
        }

        List<String> segments = pathSegments(path);
        if (segments.size() == 2 && segments.get(1).equals("projects")
                && exchange.getRequestMethod().equals("POST")) {
            ProjectRequest request = readJson(exchange, ProjectRequest.class);
            sendJson(exchange, 201, mutate(() -> createProject(request)));
            return;
        }
        if (segments.size() < 3 || !segments.get(1).equals("projects")) {
            throw new WebApiException(404, "not_found", "API 不存在");
        }

        String projectId = segments.get(2);
        if (segments.size() == 3 && exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 200,
                    projectDetails(projectId, query(exchange.getRequestURI(), "platform")));
            return;
        }
        if (segments.size() == 3 && exchange.getRequestMethod().equals("PUT")) {
            ProjectRequest request = readJson(exchange, ProjectRequest.class);
            sendJson(exchange, 200, mutate(() -> configureProject(projectId, request)));
            return;
        }
        if (segments.size() == 4 && segments.get(3).equals("files")
                && exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 200, sourceFilesView(projectId));
            return;
        }
        if (segments.size() == 5 && segments.get(3).equals("files")) {
            switch (segments.get(4)) {
                case "upload" -> {
                    if (!exchange.getRequestMethod().equals("PUT")) {
                        throw new WebApiException(405, "method_not_allowed",
                                "文件上传只允许 PUT");
                    }
                    sendJson(exchange, 201, mutate(() ->
                            uploadSourceFile(exchange, projectId)));
                }
                case "import" -> {
                    if (!exchange.getRequestMethod().equals("POST")) {
                        throw new WebApiException(405, "method_not_allowed",
                                "服务器文件导入只允许 POST");
                    }
                    SourceImportRequest request = readJson(
                            exchange, SourceImportRequest.class);
                    sendJson(exchange, 201, mutate(() -> sourceMutationView(
                            root.services().sourceFiles().importFile(
                                    projectId,
                                    Path.of(required(request.sourcePath, "服务器文件路径")),
                                    defaultValue(request.targetDirectory, ""),
                                    Boolean.TRUE.equals(request.overwrite)))));
                }
                case "remove" -> {
                    if (!exchange.getRequestMethod().equals("POST")) {
                        throw new WebApiException(405, "method_not_allowed",
                                "源文件移除只允许 POST");
                    }
                    SourceRemoveRequest request = readJson(
                            exchange, SourceRemoveRequest.class);
                    sendJson(exchange, 200, mutate(() -> sourceMutationView(
                            root.services().sourceFiles().remove(
                                    projectId,
                                    required(request.path, "托管文件路径"),
                                    request.action))));
                }
                case "remove-batch" -> {
                    if (!exchange.getRequestMethod().equals("POST")) {
                        throw new WebApiException(405, "method_not_allowed",
                                "源文件批量移除只允许 POST");
                    }
                    SourceBatchRemoveRequest request = readJson(
                            exchange, SourceBatchRemoveRequest.class);
                    List<SourceFileService.SourceRemoval> removals =
                            request.paths == null ? List.of() : request.paths.stream()
                                    .map(sourcePath -> new SourceFileService.SourceRemoval(
                                            required(sourcePath, "托管文件路径"), request.action))
                                    .toList();
                    sendJson(exchange, 200, mutate(() -> sourceBatchMutationView(
                            root.services().sourceFiles().removeBatch(
                                    projectId, removals))));
                }
                default -> throw new WebApiException(
                        404, "not_found", "文件管理 API 不存在");
            }
            return;
        }
        if (segments.size() != 4 || !exchange.getRequestMethod().equals("POST")) {
            throw new WebApiException(404, "not_found", "API 不存在");
        }

        switch (segments.get(3)) {
            case "scan" -> sendJson(exchange, 200,
                    mutate(() -> previewView(
                            root.services().scanner().createPreview(projectId))));
            case "publish" -> {
                PublishRequest request = readJson(exchange, PublishRequest.class);
                sendJson(exchange, 201, mutate(() -> publishAndRefreshService(() ->
                        root.services().publisher().publish(
                                projectId,
                                required(request.displayVersion, "显示版本"),
                                defaultValue(request.minimumPlayerVersion, "0.1.0"),
                                defaultValue(request.changelog, "")
                        ))));
            }
            case "removals" -> {
                RemovalDecisionsRequest request = readJson(
                        exchange, RemovalDecisionsRequest.class);
                sendJson(exchange, 200, mutate(() -> previewView(
                        root.services().scanner().decideRemovals(
                                projectId,
                                request.decisions == null
                                        ? List.of() : request.decisions))));
            }
            case "forced-files" -> {
                ForcedFilesRequest request = readJson(
                        exchange, ForcedFilesRequest.class);
                sendJson(exchange, 200, mutate(() ->
                        updateForcedFiles(projectId, request)));
            }
            case "forced-directories" -> {
                ForcedDirectoriesRequest request = readJson(
                        exchange, ForcedDirectoriesRequest.class);
                sendJson(exchange, 200, mutate(() ->
                        updateForcedDirectories(projectId, request)));
            }
            case "rollback" -> {
                RollbackRequest request = readJson(exchange, RollbackRequest.class);
                sendJson(exchange, 201, mutate(() -> publishAndRefreshService(() ->
                        root.services().publisher().rollback(
                                projectId,
                                required(request.targetReleaseId, "目标发布"),
                                required(request.displayVersion, "显示版本"),
                                request.changelog
                        ))));
            }
            case "programs" -> {
                PlayerProgramRequest request = readJson(exchange, PlayerProgramRequest.class);
                sendJson(exchange, 201, mutate(() -> programView(
                        root.services().playerPrograms().publish(
                                projectId,
                                defaultValue(request.platform, "windows-x64"),
                                defaultValue(request.version, ""),
                                Path.of(required(request.sourceDirectory, "玩家端程序目录")),
                                defaultValue(request.launchPath, ""),
                                defaultValue(request.minimumBootstrapVersion, "0.1.2")
                        ))));
            }
            case "instance" -> {
                InstanceRequest request = readJson(exchange, InstanceRequest.class);
                sendJson(exchange, 200,
                        mutate(() -> prepareInstance(projectId, request)));
            }
            case "deployment" -> {
                DeploymentRequest request = readJson(
                        exchange, DeploymentRequest.class);
                sendJson(exchange, 201, mutate(() -> {
                    var prepared = root.services().deployments().create(
                            projectId,
                            defaultValue(request.platform, "windows-x64"),
                            required(request.releaseId, "整合包发布版本"),
                            Path.of(required(request.outputDirectory, "输出父目录")),
                            root.bootstrapAgentPath());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("outputDirectory",
                            prepared.outputDirectory().toString());
                    result.put("releaseId", prepared.releaseId());
                    result.put("releaseDisplayVersion",
                            prepared.releaseDisplayVersion());
                    result.put("playerVersion", prepared.playerVersion());
                    return result;
                }));
            }
            default -> throw new WebApiException(404, "not_found", "API 不存在");
        }
    }

    private Map<String, Object> stateView() {
        ManagementSettings settings = root.settings();
        ManagementCli.Services services = root.services();
        List<Map<String, Object>> projects = services.projects().list().stream()
                .map(project -> projectSummary(project, services))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", ManagementCli.VERSION);
        result.put("dataDirectory", settings.dataDirectory());
        result.put("settingsFile", root.settingsFile().toString());
        result.put("defaultProjectId", settings.defaultProjectId());
        result.put("settings", settingsView(settings));
        result.put("publicService", publicService.status());
        result.put("projects", projects);
        return result;
    }

    private Map<String, Object> projectDetails(String projectId, String requestedPlatform) {
        String platform = defaultValue(requestedPlatform, "windows-x64");
        ManagementCli.Services services = root.services();
        ProjectRecord project = services.database().requireProject(projectId);
        Map<String, Object> result = new LinkedHashMap<>(projectView(project));
        result.put("releases", services.database().listReleases(projectId).stream()
                .map(AdminWebServer::releaseView).toList());
        result.put("platform", platform);
        result.put("playerPrograms",
                reverse(services.playerPrograms().list(projectId, platform)).stream()
                        .map(AdminWebServer::programView).toList());
        try {
            result.put("preview", previewView(services.scanner().load(projectId)));
        } catch (ManagementException ignored) {
            result.put("preview", null);
        }
        return result;
    }

    private Map<String, Object> createProject(ProjectRequest request) {
        String id = required(request.id, "项目 ID");
        String displayName = required(request.displayName, "项目名称");
        Branding branding = branding(request, displayName, null);
        ProjectRules rules = ProjectRules.defaults().withForcedSyncDirectories(
                request.forcedSyncDirectories == null
                        ? List.of() : request.forcedSyncDirectories)
                .withForcedSyncFiles(request.forcedSyncFiles == null
                        ? List.of() : request.forcedSyncFiles);
        ProjectRecord project = root.services().projects().create(
                id,
                displayName,
                Path.of(required(request.sourceDirectory, "整合包目录")),
                required(request.publicBaseUrl, "玩家访问地址"),
                branding,
                rules
        );
        if (present(request.coverPath)) {
            project = root.services().projects().setCover(
                    id, Path.of(request.coverPath.trim()));
        }
        root.saveSettings(root.settings().withDefaultProject(id));
        return projectView(project);
    }

    private Map<String, Object> configureProject(String projectId, ProjectRequest request) {
        ManagementCli.Services services = root.services();
        ProjectRecord current = services.database().requireProject(projectId);
        Branding branding = branding(request, current.displayName(), current.branding());
        ProjectRules rules = request.forcedSyncDirectories == null
                ? current.rules()
                : current.rules().withForcedSyncDirectories(
                        request.forcedSyncDirectories);
        if (request.forcedSyncFiles != null) {
            rules = rules.withForcedSyncFiles(request.forcedSyncFiles);
        }
        Path source = present(request.sourceDirectory)
                ? Path.of(request.sourceDirectory.trim()) : current.sourceDirectory();
        String url = present(request.publicBaseUrl)
                ? request.publicBaseUrl.trim() : current.publicBaseUrl();
        String displayName = present(request.displayName)
                ? request.displayName.trim() : current.displayName();
        ProjectRecord project = services.projects().configure(
                projectId, displayName, source, url, branding, rules);
        if (present(request.coverPath)) {
            project = services.projects().setCover(
                    projectId, Path.of(request.coverPath.trim()));
        }
        return projectView(project);
    }

    private Map<String, Object> updateForcedFiles(
            String projectId, ForcedFilesRequest request) {
        ManagementCli.Services services = root.services();
        ProjectRecord current = services.database().requireProject(projectId);
        ProjectRules rules = current.rules().withForcedSyncFiles(
                request.files == null ? List.of() : request.files);
        ProjectRecord updated = services.projects().configure(
                projectId, current.sourceDirectory(), current.publicBaseUrl(),
                current.branding(), rules);
        PublishPreview preview = services.scanner().createPreview(projectId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("forcedSyncFiles", updated.rules().forcedSyncFiles());
        result.put("preview", previewView(preview));
        return result;
    }

    private Map<String, Object> updateForcedDirectories(
            String projectId, ForcedDirectoriesRequest request) {
        ManagementCli.Services services = root.services();
        ProjectRecord current = services.database().requireProject(projectId);
        ProjectRules rules = current.rules().withForcedSyncDirectories(
                request.directories == null ? List.of() : request.directories);
        ProjectRecord updated = services.projects().configure(
                projectId, current.sourceDirectory(), current.publicBaseUrl(),
                current.branding(), rules);
        PublishPreview preview = services.scanner().createPreview(projectId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("forcedSyncDirectories",
                updated.rules().forcedSyncDirectories());
        result.put("preview", previewView(preview));
        return result;
    }

    private Map<String, Object> updateSettings(SettingsRequest request) {
        if (publicService.running()) {
            throw new WebApiException(
                    409, "service_running", "请先停止 HTTP 文件服务再修改端口");
        }
        ManagementSettings current = root.settings();
        String host = defaultValue(request.httpHost, current.httpHost());
        int httpPort = request.httpPort == null
                ? current.httpPort() : request.httpPort;
        int webPort = request.webPort == null
                ? current.webPort() : request.webPort;
        root.saveSettings(new ManagementSettings(
                ManagementSettings.CURRENT_SCHEMA,
                current.dataDirectory(),
                current.defaultProjectId(),
                host,
                httpPort,
                webPort
        ));
        return settingsView(root.settings());
    }

    private Map<String, Object> browsePath(PathBrowseRequest request) {
        String kind = switch (request.kind == null ? "" : request.kind) {
            case "directory", "file", "image" -> request.kind;
            default -> throw new WebApiException(
                    400, "invalid_path_kind", "不支持的路径选择类型");
        };
        String rawPath = request.path == null ? "" : request.path.trim();
        Path requested;
        try {
            requested = rawPath.isEmpty()
                    ? Path.of(System.getProperty("user.home", "."))
                    : Path.of(rawPath);
            if (!requested.isAbsolute()) {
                requested = Path.of(System.getProperty("user.dir", "."))
                        .resolve(requested);
            }
            requested = requested.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new WebApiException(
                    400, "invalid_path", "路径格式无效");
        }

        Path selectedFile = null;
        Path directory = requested;
        if (Files.isRegularFile(requested)) {
            selectedFile = requested;
            directory = requested.getParent();
        }
        if (directory == null || !Files.exists(directory)) {
            throw new WebApiException(
                    400, "path_not_found", "路径不存在：" + requested);
        }
        if (!Files.isDirectory(directory)) {
            throw new WebApiException(
                    400, "not_a_directory", "该路径不是文件夹：" + directory);
        }

        List<Path> children;
        try (var stream = Files.list(directory)) {
            children = stream.limit(5001).toList();
        } catch (IOException | SecurityException e) {
            throw new ManagementException("无法读取文件夹：" + directory, e);
        }
        boolean truncated = children.size() > 5000;
        if (truncated) children = children.subList(0, 5000);
        children = new ArrayList<>(children);
        children.sort(Comparator
                .comparing((Path child) -> !Files.isDirectory(child))
                .thenComparing(child -> fileName(child)
                        .toLowerCase(Locale.ROOT)));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Path child : children) {
            boolean directoryEntry = Files.isDirectory(child);
            boolean regularFile = Files.isRegularFile(child);
            boolean selectable = directoryEntry
                    ? kind.equals("directory")
                    : regularFile && (!kind.equals("image")
                    || isImagePath(child));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", fileName(child));
            entry.put("path", child.toAbsolutePath().normalize().toString());
            entry.put("directory", directoryEntry);
            entry.put("regularFile", regularFile);
            entry.put("selectable", selectable);
            entry.put("size", regularFile ? safeFileSize(child) : 0L);
            entries.add(entry);
        }

        List<String> roots = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            roots.add(root.toAbsolutePath().normalize().toString());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("currentPath", directory.toString());
        result.put("parentPath", directory.getParent() == null
                ? null : directory.getParent().toString());
        result.put("selectedPath", selectedFile != null
                && (kind.equals("file") || isImagePath(selectedFile))
                ? selectedFile.toString() : null);
        result.put("roots", roots);
        result.put("entries", entries);
        result.put("truncated", truncated);
        return result;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static boolean isImagePath(Path path) {
        String name = fileName(path).toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".webp")
                || name.endsWith(".bmp");
    }

    private static long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException | SecurityException ignored) {
            return 0L;
        }
    }

    private Map<String, Object> sourceFilesView(String projectId) {
        List<Map<String, Object>> files = root.services().sourceFiles()
                .list(projectId).stream()
                .map(AdminWebServer::sourceFileView)
                .toList();
        long totalBytes = files.stream()
                .mapToLong(file -> ((Number) file.get("size")).longValue())
                .sum();
        return Map.of(
                "files", files,
                "count", files.size(),
                "totalBytes", totalBytes
        );
    }

    private Map<String, Object> uploadSourceFile(
            HttpExchange exchange, String projectId) {
        String contentType = defaultValue(
                exchange.getRequestHeaders().getFirst("Content-Type"), "");
        if (!contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith("application/octet-stream")) {
            throw new WebApiException(415, "unsupported_media_type",
                    "文件上传必须使用 application/octet-stream");
        }
        String targetPath = required(
                query(exchange.getRequestURI(), "path"), "目标托管路径");
        boolean overwrite = Boolean.parseBoolean(
                defaultValue(query(exchange.getRequestURI(), "overwrite"), "false"));
        boolean refreshPreview = Boolean.parseBoolean(
                defaultValue(query(exchange.getRequestURI(), "refreshPreview"), "true"));
        long expectedBytes = contentLength(exchange);
        if (expectedBytes > SourceFileService.MAX_UPLOAD_BYTES) {
            throw new WebApiException(413, "payload_too_large",
                    "单个上传文件不能超过 4 GiB");
        }
        return sourceMutationView(root.services().sourceFiles().upload(
                projectId, targetPath, exchange.getRequestBody(),
                expectedBytes, overwrite, refreshPreview));
    }

    private static long contentLength(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null || value.isBlank()) return -1;
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException e) {
            throw new WebApiException(400, "invalid_content_length",
                    "文件长度无效");
        }
    }

    private Map<String, Object> prepareInstance(
            String projectId, InstanceRequest request) {
        ProjectCommand parent = new ProjectCommand();
        parent.root = root;
        ProjectBindingCommand command = new ProjectBindingCommand();
        command.parent = parent;
        command.projectId = projectId;
        command.instance = Path.of(required(
                request.instanceDirectory, "Minecraft 实例目录"));
        command.playerHome = defaultValue(
                request.playerHome, "DreamingFishUpdater");
        command.platform = defaultValue(request.platform, "windows-x64");
        command.releaseId = required(request.releaseId, "整合包发布版本");
        command.bundledCover = present(request.bundledCover)
                ? request.bundledCover.trim() : null;
        command.run();
        return Map.of(
                "instanceDirectory",
                command.instance.toAbsolutePath().normalize().toString(),
                "releaseId", command.releaseId,
                "platform", command.platform,
                "playerHome", command.playerHome
        );
    }

    private Branding branding(
            ProjectRequest request, String fallbackName, Branding current) {
        String productName = defaultValue(
                request.productName,
                current == null ? fallbackName : current.productName());
        String subtitle = defaultValue(
                request.subtitle,
                current == null ? "Minecraft 整合包更新" : current.subtitle());
        String serverAddress = request.serverAddress == null
                ? current == null ? "" : current.serverAddress()
                : request.serverAddress.trim();
        String accent = defaultValue(
                request.accentColor,
                current == null ? "#2ee8df" : current.accentColor());
        String secondary = defaultValue(
                request.secondaryAccentColor,
                current == null ? "#b06cff" : current.secondaryAccentColor());
        String coverObject = Boolean.TRUE.equals(request.removeCover)
                ? null : current == null ? null : current.coverObject();
        return new Branding(
                productName, subtitle, serverAddress,
                coverObject, accent, secondary);
    }

    private Map<String, Object> projectSummary(
            ProjectRecord project, ManagementCli.Services services) {
        Map<String, Object> summary = new LinkedHashMap<>(projectView(project));
        StoredRelease latest = services.database()
                .latestRelease(project.id()).orElse(null);
        summary.put("latestRelease",
                latest == null ? null : releaseView(latest));
        summary.put("releaseCount",
                services.database().listReleases(project.id()).size());
        return summary;
    }

    private static Map<String, Object> projectView(ProjectRecord project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.id());
        result.put("displayName", project.displayName());
        result.put("sourceDirectory", project.sourceDirectory().toString());
        result.put("publicBaseUrl", project.publicBaseUrl());
        result.put("branding", project.branding());
        result.put("forcedSyncDirectories",
                project.rules().forcedSyncDirectories());
        result.put("forcedSyncFiles", project.rules().forcedSyncFiles());
        result.put("nextSequence", project.nextSequence());
        result.put("createdAt", project.createdAt());
        return result;
    }

    private static Map<String, Object> releaseView(StoredRelease release) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("releaseId", release.releaseId());
        result.put("sequence", release.sequence());
        result.put("displayVersion", release.displayVersion());
        result.put("createdAt", release.createdAt());
        result.put("changelog", release.changelog());
        result.put("manifestSha256", release.manifestSha256());
        return result;
    }

    private Map<String, Object> publishAndRefreshService(
            Callable<StoredRelease> operation) throws Exception {
        StoredRelease release = operation.call();
        boolean restarted = false;
        String warning = null;
        if (publicService.running()) {
            try {
                publicService.restartIfRunning();
                restarted = true;
            } catch (RuntimeException e) {
                warning = "版本已经发布，但 HTTP 文件服务重启失败：" + usefulMessage(e);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(releaseView(release));
        result.put("publicServiceRestarted", restarted);
        result.put("publicService", publicService.status());
        result.put("serviceWarning", warning);
        return result;
    }

    private static Map<String, Object> programView(StoredPlayerProgram program) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", program.platform());
        result.put("version", program.version());
        result.put("createdAt", program.createdAt());
        result.put("manifestSha256", program.manifestSha256());
        return result;
    }

    private static Map<String, Object> sourceFileView(
            SourceFileService.SourceFileEntry file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.path());
        result.put("size", file.size());
        result.put("lastModifiedMillis", file.lastModifiedMillis());
        result.put("policy", file.policy().name());
        result.put("forcedByDirectory", file.forcedByDirectory());
        result.put("forcedByFile", file.forcedByFile());
        result.put("published", file.published());
        return result;
    }

    private static Map<String, Object> sourceMutationView(
            SourceFileService.SourceMutation mutation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", mutation.path());
        result.put("archivedPreviousFile", mutation.archivedPreviousFile() == null
                ? null : mutation.archivedPreviousFile().toString());
        result.put("preview", mutation.preview() == null
                ? null : previewView(mutation.preview()));
        return result;
    }

    private static Map<String, Object> sourceBatchMutationView(
            SourceFileService.SourceBatchMutation mutation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", mutation.removed().size());
        result.put("removed", mutation.removed().stream().map(file -> Map.of(
                "path", file.path(),
                "archivedPreviousFile", file.archivedPreviousFile().toString()
        )).toList());
        result.put("preview", previewView(mutation.preview()));
        return result;
    }

    private static Map<String, Object> previewView(PublishPreview preview) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previewId", preview.previewId());
        result.put("baseReleaseId", preview.baseReleaseId());
        result.put("createdAt", preview.createdAt());
        result.put("managedFiles", preview.files().size());
        result.put("totalManagedBytes", preview.totalManagedBytes());
        result.put("estimatedDownloadBytes", preview.estimatedDownloadBytes());
        result.put("changes", preview.changes().stream()
                .map(AdminWebServer::changeView).toList());
        result.put("files", preview.files().stream().map(file -> Map.of(
                "path", file.path(),
                "size", file.size(),
                "policy", file.policy().name()
        )).toList());
        return result;
    }

    private static Map<String, Object> changeView(PreviewChange change) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", change.kind().name());
        result.put("path", change.path());
        result.put("downloadSize", change.downloadSize());
        result.put("removalAction", change.removalAction() == null
                ? null : change.removalAction().name());
        return result;
    }

    private static Map<String, Object> settingsView(
            ManagementSettings settings) {
        return Map.of(
                "httpHost", settings.httpHost(),
                "httpPort", settings.httpPort(),
                "webHost", "127.0.0.1",
                "webPort", settings.webPort()
        );
    }

    private Object mutate(Callable<Object> operation) throws Exception {
        if (!mutationLock.tryLock()) {
            throw new WebApiException(
                    409, "operation_in_progress", "另一个管理操作正在执行");
        }
        try {
            return operation.call();
        } finally {
            mutationLock.unlock();
        }
    }

    private <T> T readJson(
            HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType.toLowerCase().startsWith("application/json")) {
            throw new WebApiException(
                    415, "unsupported_media_type",
                    "请求必须使用 application/json");
        }
        byte[] bytes = readLimited(exchange.getRequestBody());
        if (bytes.length == 0) {
            throw new WebApiException(
                    400, "empty_body", "请求内容不能为空");
        }
        return json.read(bytes, type);
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_REQUEST_BYTES) {
                throw new WebApiException(
                        413, "request_too_large", "请求内容超过 1 MiB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void requireToken(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                sessionToken.getBytes(StandardCharsets.UTF_8))) {
            throw new WebApiException(
                    403, "invalid_session", "管理会话已失效，请刷新页面");
        }
    }

    private void sendJson(
            HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.write(value);
        securityHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        sendBytes(exchange, status, bytes);
    }

    private static void sendBytes(
            HttpExchange exchange, int status, byte[] bytes) throws IOException {
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.getResponseHeaders().set(
                "Content-Length", Integer.toString(bytes.length));
        exchange.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) exchange.getResponseBody().write(bytes);
    }

    private static void securityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "img-src 'self' data:; connect-src 'self'; "
                        + "frame-ancestors 'none'; base-uri 'none'");
    }

    private String webAddress() {
        return "http://127.0.0.1:" + address().getPort() + "/";
    }

    private static List<String> pathSegments(String path) {
        List<String> result = new ArrayList<>();
        for (String value : path.split("/")) {
            if (!value.isEmpty()) {
                result.add(URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static String query(URI uri, String name) {
        if (uri.getRawQuery() == null) return null;
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String key = URLDecoder.decode(
                    separator < 0 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(
                        separator < 0 ? "" : pair.substring(separator + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static boolean mutating(String method) {
        return method.equals("POST")
                || method.equals("PUT")
                || method.equals("DELETE");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ManagementException(label + "不能为空");
        }
        return value.trim();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String usefulMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static <T> List<T> reverse(List<T> source) {
        List<T> result = new ArrayList<>(source);
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    private static Map<String, StaticAsset> loadAssets() {
        return Map.of(
                "/index.html", loadAsset(
                        "web/index.html", "text/html; charset=utf-8"),
                "/app.css", loadAsset(
                        "web/app.css", "text/css; charset=utf-8"),
                "/app.js", loadAsset(
                        "web/app.js", "text/javascript; charset=utf-8")
        );
    }

    private static StaticAsset loadAsset(
            String path, String contentType) {
        try (InputStream input = AdminWebServer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new ExceptionInInitializerError(
                        "Missing admin web asset: " + path);
            }
            return new StaticAsset(input.readAllBytes(), contentType);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        publicService.close();
        server.stop(0);
        executor.shutdownNow();
    }

    private record StaticAsset(byte[] bytes, String contentType) {
    }

    private record ProjectRequest(
            String id,
            String displayName,
            String sourceDirectory,
            String publicBaseUrl,
            List<String> forcedSyncDirectories,
            List<String> forcedSyncFiles,
            String productName,
            String subtitle,
            String serverAddress,
            String accentColor,
            String secondaryAccentColor,
            String coverPath,
            Boolean removeCover
    ) {
    }

    private record RemovalDecisionsRequest(List<RemovalDecision> decisions) {
    }

    private record ForcedFilesRequest(List<String> files) {
    }

    private record ForcedDirectoriesRequest(List<String> directories) {
    }

    private record PathBrowseRequest(String kind, String path) {
    }

    private record SourceImportRequest(
            String sourcePath,
            String targetDirectory,
            Boolean overwrite
    ) {
    }

    private record SourceRemoveRequest(
            String path,
            RemovalAction action
    ) {
    }

    private record SourceBatchRemoveRequest(
            List<String> paths,
            RemovalAction action
    ) {
    }

    private record PublishRequest(
            String displayVersion,
            String minimumPlayerVersion,
            String changelog
    ) {
    }

    private record RollbackRequest(
            String targetReleaseId,
            String displayVersion,
            String changelog
    ) {
    }

    private record PlayerProgramRequest(
            String platform,
            String version,
            String sourceDirectory,
            String launchPath,
            String minimumBootstrapVersion
    ) {
    }

    private record InstanceRequest(
            String instanceDirectory,
            String platform,
            String playerHome,
            String releaseId,
            String bundledCover
    ) {
    }

    private record DeploymentRequest(
            String outputDirectory,
            String platform,
            String releaseId
    ) {
    }

    private record SettingsRequest(
            String httpHost,
            Integer httpPort,
            Integer webPort
    ) {
    }

    private static final class WebApiException extends RuntimeException {
        private final int status;
        private final String code;

        private WebApiException(
                int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
