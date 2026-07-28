package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ChangeKind;
import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.ProjectRecord;
import cn.dreamingfish.updater.management.ProjectRules;
import cn.dreamingfish.updater.management.PublicFileServer;
import cn.dreamingfish.updater.management.PublishPreview;
import cn.dreamingfish.updater.management.StoredRelease;
import cn.dreamingfish.updater.protocol.Branding;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class InteractiveConsole {
    private final ManagementCli root;

    InteractiveConsole(ManagementCli root) {
        this.root = root;
    }

    void run() {
        root.out().println();
        root.out().println("DreamingFish 整合包更新管理端");
        root.out().println("================================");
        try {
            boolean firstRun = !root.hasSavedSettings();
            if (firstRun) {
                configureFirstRun();
            }
            root.services();
            if (firstRun && root.services().projects().list().isEmpty()
                    && confirm("目前还没有项目，是否现在创建第一个项目？", true)) {
                createProject();
            }
            mainMenu();
        } catch (InputEnded ignored) {
            root.out().println();
            root.out().println("输入已结束，管理端退出。");
        }
    }

    private void configureFirstRun() {
        root.out().println();
        root.out().println("首次运行配置");
        root.out().println("管理数据会自动保存在管理端根目录的 data 文件夹中。");

        ManagementSettings current = root.settings();
        Path data = root.dataDirectory.toAbsolutePath().normalize();
        root.out().println("管理数据目录：" + data);
        String host = prompt("HTTP 监听地址", current.httpHost());
        int port = promptPort("HTTP 监听端口", current.httpPort());
        root.saveSettings(new ManagementSettings(
                ManagementSettings.CURRENT_SCHEMA,
                data.toString(),
                current.defaultProjectId(),
                host,
                port,
                current.webPort()
        ));
        root.services();
        root.out().println("配置已保存到：" + root.settingsFile());
        root.out().println("管理数据目录已初始化：" + root.dataDirectory);
        root.out().println("Web 管理界面：http://127.0.0.1:"
                + current.webPort() + "/");
    }

    private void mainMenu() {
        while (true) {
            root.out().println();
            root.out().println("主菜单");
            root.out().println("[1] 查看项目状态");
            root.out().println("[2] 创建项目");
            root.out().println("[3] 修改项目设置");
            root.out().println("[4] 扫描并发布整合包");
            root.out().println("[5] 查看发布历史");
            root.out().println("[6] 发布玩家端程序");
            root.out().println("[7] 制作玩家实例");
            root.out().println("[8] 修改服务设置");
            root.out().println("[9] 启动 HTTP 文件服务");
            root.out().println("[10] 启动 Web 管理界面");
            root.out().println("[0] 退出");
            String choice = readLine("请选择：").trim();
            if (choice.equals("0")) {
                root.out().println("管理端已退出。");
                return;
            }
            try {
                switch (choice) {
                    case "1" -> showProject();
                    case "2" -> createProject();
                    case "3" -> configureProject();
                    case "4" -> scanAndPublish();
                    case "5" -> showReleases();
                    case "6" -> publishPlayerProgram();
                    case "7" -> preparePlayerInstance();
                    case "8" -> configureManagement();
                    case "9" -> serve();
                    case "10" -> serveWeb();
                    default -> root.out().println("请输入 0 到 10 之间的菜单编号。");
                }
            } catch (ManagementException | IllegalArgumentException e) {
                root.err().println("操作失败：" + usefulMessage(e));
            }
        }
    }

    private void showProject() {
        ProjectRecord project = selectProject();
        if (project == null) return;
        var services = root.services();
        root.out().println();
        root.out().println("项目：" + project.displayName() + " (" + project.id() + ")");
        root.out().println("标准整合包目录：" + project.sourceDirectory());
        root.out().println("公共地址：" + project.publicBaseUrl());
        root.out().println("强制同步目录：" + displayForcedDirectories(project.rules()));
        root.out().println("服务器地址：" + displayOrNone(project.branding().serverAddress()));
        root.out().println("下一个发布序号：" + project.nextSequence());
        services.database().latestRelease(project.id()).ifPresentOrElse(
                release -> root.out().println("当前发布：" + release.displayVersion()
                        + " / " + release.releaseId()),
                () -> root.out().println("当前发布：尚未发布")
        );
        List<String> entries = topLevelEntries(project.sourceDirectory());
        if (entries.isEmpty()) {
            root.out().println("标准目录当前为空。扫描发布空目录可能产生删除项，请仔细核对预览。");
        } else {
            root.out().println("标准目录当前包含：" + String.join("、", entries));
        }
        root.out().println("只需在此目录中保留希望发布的内容，不需要复制完整游戏目录。");
    }

    private void createProject() {
        root.out().println();
        root.out().println("创建整合包项目");
        root.out().println("标准整合包目录可以只包含 mods 和 config，也可以放入其它需要发布的目录。");
        String id = promptRequired("项目 ID（小写字母、数字、点、下划线或连字符）");
        String name = promptRequired("项目显示名称");
        Path source = promptDirectory("标准整合包目录", null, true);
        String forcedInput = readLine(
                "强制同步一级目录（逗号分隔，留空不启用，例如 mods）：").trim();
        String publicUrl = prompt("玩家访问的公共 HTTP 地址", defaultPublicUrl());
        String subtitle = prompt("副标题", "Minecraft 整合包更新");
        String serverAddress = prompt("Minecraft 服务器地址（可留空）", "");
        String accent = prompt("主强调色", "#2ee8df");
        String secondaryAccent = prompt("次强调色", "#b06cff");
        String coverInput = readLine("电脑端封面图片路径（可留空）：").trim();
        Path cover = coverInput.isEmpty() ? null : requireRegularFile(coverInput, "封面图片");

        Branding branding = new Branding(name, subtitle, serverAddress, null, accent, secondaryAccent);
        var services = root.services();
        ProjectRules rules = ProjectRules.defaults().withForcedSyncDirectories(
                ProjectCreateCommand.parseDirectories(forcedInput));
        ProjectRecord project = services.projects().create(
                id, name, source, publicUrl, branding, rules);
        if (cover != null) {
            project = services.projects().setCover(id, cover);
        }
        root.saveSettings(root.settings().withDefaultProject(id));
        root.out().println("项目已创建：" + project.displayName() + " (" + project.id() + ")");
        root.out().println("项目签名私钥已保存在管理数据目录，请通过加密备份保护它。");
    }

    private void configureProject() {
        ProjectRecord current = selectProject();
        if (current == null) return;
        root.out().println("直接按回车保留当前值。");
        Path source = promptDirectory("标准整合包目录", current.sourceDirectory(), false);
        String currentForced = String.join(",", current.rules().forcedSyncDirectories());
        String forcedInput = prompt("强制同步一级目录（逗号分隔，输入 - 清空）", currentForced);
        List<String> forcedDirectories = ProjectCreateCommand.parseDirectories(forcedInput);
        String publicUrl = prompt("玩家访问的公共 HTTP 地址", current.publicBaseUrl());
        Branding old = current.branding();
        String productName = prompt("界面产品名称", old.productName());
        String subtitle = prompt("副标题", old.subtitle());
        String serverAddress = prompt("Minecraft 服务器地址", old.serverAddress());
        String accent = prompt("主强调色", old.accentColor());
        String secondaryAccent = prompt("次强调色", old.secondaryAccentColor());
        String coverInput = readLine("新封面图片路径（回车保留，输入 - 移除）：").trim();
        Path cover = null;
        String coverObject = old.coverObject();
        if (coverInput.equals("-")) {
            coverObject = null;
        } else if (!coverInput.isEmpty()) {
            cover = requireRegularFile(coverInput, "封面图片");
        }

        Branding branding = new Branding(productName, subtitle, serverAddress,
                coverObject, accent, secondaryAccent);
        var services = root.services();
        ProjectRules rules = current.rules().withForcedSyncDirectories(forcedDirectories);
        ProjectRecord updated = services.projects().configure(
                current.id(), source, publicUrl, branding, rules);
        if (cover != null) {
            updated = services.projects().setCover(current.id(), cover);
        }
        root.out().println("项目设置已更新：" + updated.id());
    }

    private void scanAndPublish() {
        ProjectRecord project = selectProject();
        if (project == null) return;
        PublishPreview preview = root.services().scanner().createPreview(project.id());
        printPreview(preview);
        String version = promptRequired("本次显示版本（例如 1.0.1）");
        String minimumPlayer = prompt("最低玩家端程序版本", "0.1.0");
        String changelog = ChangelogInput.interactive(
                readLine("更新记录（可留空；输入 @文件路径读取 UTF-8 文本）："),
                root.settingsFile().getParent());
        root.out().println();
        root.out().println("实际将保存的更新记录：");
        root.out().println(changelog.isBlank() ? "（未填写）" : changelog);
        root.out().println();
        if (!confirm("确认以上版本和更新记录无误并发布？", false)) {
            root.out().println("已取消发布；扫描预览仍保留，可使用参数式命令继续处理。");
            return;
        }
        var release = root.services().publisher().publish(
                project.id(), version, minimumPlayer, changelog);
        root.out().println("发布完成：" + release.displayVersion() + " / " + release.releaseId());
    }

    private void printPreview(PublishPreview preview) {
        root.out().println();
        root.out().println("发布预览 " + preview.previewId());
        root.out().println("托管文件：" + preview.files().size()
                + "，总大小：" + HumanSize.format(preview.totalManagedBytes()));
        root.out().println("变更数量：" + preview.changes().size()
                + "，预计下载：" + HumanSize.format(preview.estimatedDownloadBytes()));
        Set<String> roots = new LinkedHashSet<>();
        preview.files().forEach(file -> roots.add(topLevelName(file.path())));
        root.out().println("本次内容范围：" + (roots.isEmpty() ? "（空）" : String.join("、", roots)));
        ProjectRecord project = root.services().database().requireProject(preview.projectId());
        root.out().println("强制同步目录：" + displayForcedDirectories(project.rules()));
        if (preview.changes().isEmpty()) {
            root.out().println("没有文件变更。");
            return;
        }
        root.out().println("变更明细：");
        preview.changes().forEach(change -> {
            root.out().printf("  %-6s %s", changeName(change.kind()), change.path());
            if (change.downloadSize() > 0) {
                root.out().print("  (" + HumanSize.format(change.downloadSize()) + ")");
            }
            root.out().println();
        });
    }

    private void showReleases() {
        ProjectRecord project = selectProject();
        if (project == null) return;
        var releases = root.services().database().listReleases(project.id());
        if (releases.isEmpty()) {
            root.out().println("该项目尚未发布任何版本。");
            return;
        }
        root.out().println("发布历史（新版本在前）：");
        releases.forEach(release -> {
            root.out().printf("  #%d  %s  %s  %s%n",
                    release.sequence(), release.displayVersion(), release.releaseId(), release.createdAt());
            if (!release.changelog().isBlank()) {
                root.out().println("       " + release.changelog());
            }
        });
    }

    private void publishPlayerProgram() {
        ProjectRecord project = selectProject();
        if (project == null) return;
        root.out().println("此处发布的是玩家端更新器程序，不是 mods/config 整合包内容。");
        String platform = prompt("平台", "windows-x64");
        String version = promptRequired("玩家端程序版本（语义版本，例如 0.1.1）");
        Path source = promptDirectory("玩家端完整 app-image 目录", null, false);
        String launcher = prompt("该目录内的启动程序路径", "DreamingFishUpdater.exe");
        String minimumBootstrap = prompt("最低启动引导器版本", "0.1.2");
        if (!confirm("确认发布玩家端程序 " + version + "？", false)) {
            root.out().println("已取消发布。");
            return;
        }
        var stored = root.services().playerPrograms().publish(
                project.id(), platform, version, source, launcher, minimumBootstrap);
        root.out().println("玩家端程序已发布：" + stored.version() + " / " + stored.platform());
    }

    private void preparePlayerInstance() {
        ProjectRecord project = selectProject();
        if (project == null) return;
        root.out().println("实例中必须已经解压玩家端发行包，并包含 .dreamingfish-bootstrap/bootstrap-agent.jar。");
        Path instance = promptDirectory("Minecraft 版本隔离实例目录", null, false);
        String platform = prompt("平台", "windows-x64");
        String playerHome = prompt("实例内玩家端目录", "DreamingFishUpdater");
        StoredRelease release = selectReleaseForBundle(project);
        if (!confirm("确认写入项目绑定并核对首个玩家端程序？", false)) {
            root.out().println("已取消制作实例。");
            return;
        }
        ProjectCommand projectCommand = new ProjectCommand();
        projectCommand.root = root;
        ProjectBindingCommand binding = new ProjectBindingCommand();
        binding.parent = projectCommand;
        binding.projectId = project.id();
        binding.instance = instance;
        binding.playerHome = playerHome;
        binding.platform = platform;
        binding.releaseId = release.releaseId();
        binding.run();
    }

    private StoredRelease selectReleaseForBundle(ProjectRecord project) {
        List<StoredRelease> releases = root.services().database().listReleases(project.id());
        if (releases.isEmpty()) {
            throw new ManagementException("该项目尚未发布整合包版本，无法制作玩家实例");
        }
        root.out().println("请选择这个下载包所对应的不可变发布版本（新版本在前）：");
        for (int index = 0; index < releases.size(); index++) {
            StoredRelease release = releases.get(index);
            root.out().printf("[%d] %s  %s%s%n", index + 1, release.displayVersion(),
                    release.releaseId(), index == 0 ? "  *" : "");
        }
        while (true) {
            String input = readLine("输入编号或发布 ID，回车使用 * 版本：").trim();
            if (input.isEmpty()) return releases.getFirst();
            try {
                int index = Integer.parseInt(input) - 1;
                if (index >= 0 && index < releases.size()) return releases.get(index);
            } catch (NumberFormatException ignored) {
                StoredRelease selected = releases.stream()
                        .filter(release -> release.releaseId().equals(input))
                        .findFirst().orElse(null);
                if (selected != null) return selected;
            }
            root.out().println("发布版本不存在，请重新输入。");
        }
    }

    private void configureManagement() {
        ManagementSettings current = root.settings();
        root.out().println("设置文件：" + root.settingsFile());
        root.out().println("管理数据目录：" + Path.of(current.dataDirectory()));
        String host = prompt("HTTP 监听地址", current.httpHost());
        int port = promptPort("HTTP 监听端口", current.httpPort());
        int webPort = promptPort("Web 管理端口（仅监听 127.0.0.1）", current.webPort());
        if (webPort == port) {
            throw new ManagementException("Web 管理端口不能与 HTTP 文件服务端口相同");
        }
        root.saveSettings(new ManagementSettings(
                ManagementSettings.CURRENT_SCHEMA,
                current.dataDirectory(),
                current.defaultProjectId(),
                host,
                port,
                webPort
        ));
        root.services();
        root.out().println("服务设置已保存。");
    }

    private void serve() {
        ManagementSettings settings = root.settings();
        String address = "http://" + settings.httpHost() + ":" + settings.httpPort() + "/";
        if (!confirm("启动只读 HTTP 文件服务 " + address + "？", true)) {
            return;
        }
        var services = root.services();
        PublicFileServer server = new PublicFileServer(
                services.database(), services.objects(),
                new InetSocketAddress(settings.httpHost(), settings.httpPort()));
        Thread shutdown = new Thread(server::close, "dfs-http-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
            server.start();
            root.out().println("HTTP 文件服务已启动：" + address);
            root.out().println("健康检查：" + address + "healthz");
            waitForServiceStop();
        } finally {
            server.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down.
            }
            root.out().println("HTTP 文件服务已停止，正在返回主菜单。");
        }
    }

    private void serveWeb() {
        ManagementSettings settings = root.settings();
        String address = "http://127.0.0.1:" + settings.webPort() + "/";
        if (!confirm("启动 Web 管理界面 " + address + "？", true)) return;
        try (AdminWebServer server = new AdminWebServer(root)) {
            Thread shutdown = new Thread(server::close, "dfs-admin-web-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(shutdown);
                server.start();
                root.out().println("Web 管理界面已启动：" + address);
                root.out().println("远程服务器可使用 SSH 隧道：ssh -L "
                        + settings.webPort() + ":127.0.0.1:" + settings.webPort()
                        + " <用户>@<服务器>");
                waitForServiceStop();
            } finally {
                server.close();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // The JVM is already shutting down.
                }
                root.out().println("Web 管理界面已停止，正在返回主菜单。");
            }
        }
    }

    private void waitForServiceStop() {
        try (ConsoleInterrupt interrupt = ConsoleInterrupt.install()) {
            if (!interrupt.supported()) {
                readLine("当前终端无法捕获 Ctrl+C，按回车停止服务并返回主菜单：");
                return;
            }
            root.out().println("按 Ctrl+C 停止服务并返回主菜单。");
            interrupt.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ProjectRecord selectProject() {
        List<ProjectRecord> projects = root.services().projects().list();
        if (projects.isEmpty()) {
            root.out().println("还没有项目，请先从主菜单创建项目。");
            return null;
        }
        String preferred = root.settings().defaultProjectId();
        ProjectRecord defaultProject = projects.stream()
                .filter(project -> project.id().equals(preferred))
                .findFirst()
                .orElse(projects.getFirst());
        if (projects.size() == 1) {
            rememberDefaultProject(defaultProject.id());
            return defaultProject;
        }
        root.out().println("请选择项目：");
        for (int index = 0; index < projects.size(); index++) {
            ProjectRecord project = projects.get(index);
            String marker = project.id().equals(defaultProject.id()) ? " *" : "";
            root.out().printf("[%d] %s (%s)%s%n",
                    index + 1, project.displayName(), project.id(), marker);
        }
        while (true) {
            String input = readLine("输入编号或项目 ID，回车使用 * 项目：").trim();
            ProjectRecord selected = null;
            if (input.isEmpty()) {
                selected = defaultProject;
            } else {
                try {
                    int index = Integer.parseInt(input) - 1;
                    if (index >= 0 && index < projects.size()) selected = projects.get(index);
                } catch (NumberFormatException ignored) {
                    selected = projects.stream()
                            .filter(project -> project.id().equals(input))
                            .findFirst()
                            .orElse(null);
                }
            }
            if (selected != null) {
                rememberDefaultProject(selected.id());
                return selected;
            }
            root.out().println("项目不存在，请重新输入。");
        }
    }

    private void rememberDefaultProject(String projectId) {
        if (!projectId.equals(root.settings().defaultProjectId())) {
            root.saveSettings(root.settings().withDefaultProject(projectId));
        }
    }

    private List<String> topLevelEntries(Path source) {
        try (var stream = Files.list(source)) {
            return stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString()
                            + (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            throw new ManagementException("无法读取标准整合包目录：" + source, e);
        }
    }

    private Path promptDirectory(String label, Path defaultValue, boolean allowCreate) {
        while (true) {
            String value = defaultValue == null
                    ? readLine(label + "：").trim()
                    : prompt(label, defaultValue.toString());
            if (value.isEmpty()) {
                root.out().println("目录不能为空。");
                continue;
            }
            final Path directory;
            try {
                directory = Path.of(value).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                root.out().println("目录格式无效，请重新输入。");
                continue;
            }
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(directory)) {
                return directory;
            }
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                root.out().println("该路径不是可用的普通目录：" + directory);
                continue;
            }
            if (!allowCreate || !confirm("目录不存在，是否创建 " + directory + "？", true)) {
                root.out().println("请输入一个已经存在的目录。");
                continue;
            }
            try {
                Files.createDirectories(directory);
                return directory;
            } catch (IOException e) {
                root.out().println("无法创建目录：" + e.getMessage());
            }
        }
    }

    private Path requireRegularFile(String input, String label) {
        final Path file;
        try {
            file = Path.of(input).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ManagementException(label + "路径格式无效", e);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new ManagementException(label + "不存在或不是普通文件：" + file);
        }
        return file;
    }

    private String promptRequired(String label) {
        while (true) {
            String value = readLine(label + "：").trim();
            if (!value.isEmpty()) return value;
            root.out().println("此项不能为空。");
        }
    }

    private String prompt(String label, String defaultValue) {
        String shown = defaultValue == null || defaultValue.isEmpty()
                ? label + "（可留空）："
                : label + " [" + defaultValue + "]：";
        String value = readLine(shown).trim();
        return value.isEmpty() ? (defaultValue == null ? "" : defaultValue) : value;
    }

    private int promptPort(String label, int defaultValue) {
        while (true) {
            String value = prompt(label, Integer.toString(defaultValue));
            try {
                int port = Integer.parseInt(value);
                if (port >= 1 && port <= 65535) return port;
            } catch (NumberFormatException ignored) {
                // The message below covers both invalid formats and invalid ranges.
            }
            root.out().println("端口必须是 1 到 65535 之间的整数。");
        }
    }

    private boolean confirm(String question, boolean defaultYes) {
        String suffix = defaultYes ? " [Y/n]：" : " [y/N]：";
        while (true) {
            String answer = readLine(question + suffix).trim().toLowerCase(Locale.ROOT);
            if (answer.isEmpty()) return defaultYes;
            if (answer.equals("y") || answer.equals("yes") || answer.equals("是")) return true;
            if (answer.equals("n") || answer.equals("no") || answer.equals("否")) return false;
            root.out().println("请输入 y 或 n。");
        }
    }

    private String readLine(String prompt) {
        java.io.Console console = System.console();
        if (console != null) {
            String value = console.readLine("%s", prompt);
            if (value == null) throw new InputEnded();
            return value;
        }
        root.out().print(prompt);
        root.out().flush();
        try {
            String value = root.input().readLine();
            if (value == null) throw new InputEnded();
            return value;
        } catch (IOException e) {
            throw new ManagementException("无法读取终端输入", e);
        }
    }

    private String defaultPublicUrl() {
        return "http://127.0.0.1:" + root.settings().httpPort();
    }

    private static String topLevelName(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "根目录文件" : path.substring(0, slash) + "/";
    }

    private static String changeName(ChangeKind kind) {
        return switch (kind) {
            case ADDED -> "新增";
            case MODIFIED -> "修改";
            case REMOVED -> "删除";
            case POLICY_CHANGED -> "策略";
            case METADATA_CHANGED -> "模组信息";
        };
    }

    private static String displayOrNone(String value) {
        return value == null || value.isBlank() ? "（未设置）" : value;
    }

    private static String displayForcedDirectories(ProjectRules rules) {
        return rules.forcedSyncDirectories().isEmpty()
                ? "（未启用）"
                : rules.forcedSyncDirectories().stream()
                .map(value -> value + "/")
                .reduce((left, right) -> left + "、" + right)
                .orElse("（未启用）");
    }

    private static String usefulMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static final class InputEnded extends RuntimeException {
    }
}
