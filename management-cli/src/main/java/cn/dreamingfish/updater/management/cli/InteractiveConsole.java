package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ChangeKind;
import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.ProjectRecord;
import cn.dreamingfish.updater.management.ProjectRules;
import cn.dreamingfish.updater.management.PublicFileServer;
import cn.dreamingfish.updater.management.PublishPreview;
import cn.dreamingfish.updater.management.RemovalAction;
import cn.dreamingfish.updater.management.RemovalDecision;
import cn.dreamingfish.updater.management.StoredRelease;
import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.PlayerCustomPage;
import cn.dreamingfish.updater.protocol.PlayerContentPage;
import cn.dreamingfish.updater.protocol.PlayerNewsArticle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
            if (firstRun && root.services().projects().list().isEmpty()) {
                root.out().println();
                root.out().println("还没有整合包项目。推荐在 Web 管理界面中完成首次创建，"
                        + "每个必填项都带有用途和示例。");
                root.out().println("  按 Y 进入 Web 管理界面创建，按 N 使用命令行创建。");
                boolean useWeb = confirm(
                        "是否现在启动 Web 管理界面并创建第一个项目？", true);
                if (!useWeb) {
                    createProject();
                } else if (!serveWeb(false)
                        && root.services().projects().list().isEmpty()) {
                    root.out().println("Web 管理界面未能启动，将改用命令行创建第一个项目。");
                    createProject();
                }
            }
            mainMenu();
        } catch (InputEnded ignored) {
            root.out().println();
            root.out().println("输入已结束，管理端退出。");
        }
    }

    private void configureFirstRun() {
        root.out().println();
        root.out().println("首次使用引导");
        root.out().println("================");
        root.out().println("欢迎使用 DreamingFish Updater！");
        root.out().println("请跟随引导完成设置。如果不确定怎么填，直接按回车使用默认值即可。");

        ManagementSettings current = root.settings();
        Path data = root.dataDirectory.toAbsolutePath().normalize();

        root.out().println();
        root.out().println("[1/3] 管理端数据保存位置");
        root.out().println("管理端会把项目设置、发布记录和签名密钥自动保存在下面这个文件夹：");
        root.out().println("  " + data);
        root.out().println("这个 data 文件夹位于管理端根目录中，非常重要，请勿删除！");
        root.out().println();
        root.out().println("升级管理端的方法：");
        root.out().println("  1. 把旧管理端根目录中的 data 文件夹、management-settings.json");
        root.out().println("     和 management-web-auth.json（如果已经注册 Web 账户）");
        root.out().println("     移动或复制到新版管理端根目录。");
        root.out().println("  2. 启动新版管理端，程序会自动读取原有数据。");

        root.out().println();
        root.out().println("[2/3] 配置玩家下载服务");
        root.out().println("玩家更新器会通过这项服务下载模组和配置文件。");
        root.out().println("请设置服务监听地址和端口。");
        root.out().println();
        root.out().println("监听地址决定哪些电脑可以连接：");
        root.out().println("  0.0.0.0   = 允许其他电脑连接，部署在服务器或 VPS 时使用（推荐）。");
        root.out().println("  127.0.0.1 = 只有当前电脑能连接，仅在内部测试时使用。");
        root.out().println("这里填写的不是公网 IP。玩家访问的公网地址会在创建项目时填写。");
        String host = prompt("下载服务监听地址（一般直接按回车）", current.httpHost());
        root.out().println();
        root.out().println("监听端口通常使用默认值 8080。只有端口冲突或服务商有要求时才修改。");
        root.out().println("如果使用端口映射，这里填写服务器内部端口。");
        int port = promptPort("下载服务端口（一般直接按回车）", current.httpPort());

        root.out().println();
        root.out().println("[3/3] Web 管理页面");
        root.out().println("这个页面只给管理员使用，不要提供给玩家。");
        root.out().println("  默认端口是 18080，通常直接按回车。");
        root.out().println("  默认只监听 127.0.0.1；需要公网访问时，先通过本机或 SSH 隧道注册账户，");
        root.out().println("  再在服务设置或 Web 页面显式改为 0.0.0.0，并务必配置 HTTPS 反向代理。");
        int webPort = promptWebPort(
                "Web 管理端口（一般直接按回车）", current.webPort(), port);
        root.saveSettings(new ManagementSettings(
                ManagementSettings.CURRENT_SCHEMA,
                data.toString(),
                current.defaultProjectId(),
                host,
                port,
                webPort,
                "127.0.0.1"
        ));
        root.services();
        root.out().println();
        root.out().println("首次设置已完成");
        root.out().println("================");
        root.out().println("  管理端数据：" + root.dataDirectory);
        root.out().println("  下载服务：" + host + ":" + port);
        root.out().println("  Web 管理页面：http://127.0.0.1:"
                + webPort + "/");
        root.out().println("  设置文件：" + root.settingsFile());
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
            root.out().println("[11] 生成玩家端首次部署包");
            root.out().println("[12] 修改玩家端个性化设置");
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
                    case "11" -> createPlayerDeployment();
                    case "12" -> configurePlayerPersonalization();
                    default -> root.out().println("请输入 0 到 12 之间的菜单编号。");
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
        root.out().println("整合包文件目录：" + project.sourceDirectory());
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
        root.out().println("================================");
        root.out().println("[1/3] 创建必备设置");
        root.out().println("以下四项用于创建项目、定位管理目录并让玩家端连接更新服务。");
        root.out().println();
        root.out().println("【必填】项目 ID：用于目录、接口和玩家端绑定；创建后不应随意更改。");
        root.out().println("  格式：小写字母或数字开头，可包含点、下划线和连字符，例如 build-server。");
        String id = promptRequired("项目 ID（必填）");
        root.out().println("【必填】项目显示名称：显示在管理端项目列表中，例如服务器名称或整合包名称。");
        root.out().println("  创建时也会作为玩家端主标题的默认值。");
        String name = promptRequired("项目显示名称（必填）");
        root.out().println("【必填】要管理的文件目录：只放准备交给更新器管理的内容，例如 mods、config。");
        root.out().println("  不需要复制完整 .minecraft；该目录必须与管理端 data 目录分开。");
        Path source = promptDirectory("要管理的文件目录（必填）", null, true);
        root.out().println("【必填】玩家端更新器访问地址：写入玩家部署包，必须能从玩家电脑访问。");
        root.out().println("  示例：http://example.com:39988；不要填写 0.0.0.0、127.0.0.1 或 localhost。");
        root.out().println("  请确认公网端口已经映射或转发到管理端下载服务端口。");
        String publicUrl = promptRequired("玩家端更新器访问地址（必填）");

        root.out().println();
        root.out().println("[2/3] 同步策略（可选）");
        root.out().println("强制同步一级目录会与服务器保持一致，玩家不能豁免目录内文件，额外文件会被归档移出。");
        root.out().println("不确定时请直接按回车留空，创建后仍可在“管理文件”中设置。");
        String forcedInput = readLine(
                "强制同步一级目录（逗号分隔，留空不启用，例如 mods, config）：").trim();

        root.out().println();
        root.out().println("[3/3] 玩家端个性化");
        root.out().println("以下内容只影响玩家端显示，直接按回车可使用默认值。");
        root.out().println("创建后可从主菜单 [12]“修改玩家端个性化设置”继续调整，Web 页面还提供实时预览。");
        String productName = prompt("玩家端主标题（显示在首页左侧的大号标题区域）", name);
        String subtitle = prompt("玩家端副标题（显示在主标题下方）", "Minecraft 整合包更新");
        String brandName = prompt("左上角中文品牌名（显示在玩家端窗口左上角）",
                Branding.DEFAULT_BRAND_NAME);
        String brandEnglishName = prompt("左上角英文品牌名（紧跟在中文品牌名右侧）",
                Branding.DEFAULT_BRAND_ENGLISH_NAME);
        String serverAddress = prompt("Minecraft 服务器地址（显示在服务器信息区域，可留空）", "");

        Branding branding = new Branding(productName, subtitle, serverAddress,
                null, "#2ee8df", "#b06cff", brandName, brandEnglishName,
                List.of(), null, List.of());
        var services = root.services();
        ProjectRules rules = ProjectRules.defaults().withForcedSyncDirectories(
                ProjectCreateCommand.parsePaths(forcedInput));
        ProjectRecord project = services.projects().create(
                id, name, source, publicUrl, branding, rules);
        root.saveSettings(root.settings().withDefaultProject(id));
        root.out().println("项目已创建：" + project.displayName() + " (" + project.id() + ")");
        root.out().println("项目签名私钥已保存在管理数据目录，请通过加密备份保护它。");
        root.out().println("建议下一步在 Web 管理界面添加整合包文件、扫描差异并创建首次发布。");
        if (confirm("是否现在启动 Web 管理界面继续配置？", true)) {
            serveWeb(false);
        }
    }

    private void configureProject() {
        ProjectRecord current = selectProject();
        if (current == null) return;
        root.out().println("直接按回车保留当前值。");
        root.out().println("玩家访问公共 HTTP 地址必须是玩家电脑可访问的公网域名或 IP，"
                + "不是服务器监听地址。");
        root.out().println("强制同步目录会清理玩家本地额外文件，修改前请确认影响范围。");
        String displayName = prompt("项目显示名称（必填）", current.displayName());
        Path source = promptDirectory("整合包文件目录（必填）", current.sourceDirectory(), false);
        String currentForced = String.join(",", current.rules().forcedSyncDirectories());
        String forcedInput = prompt("强制同步一级目录（逗号分隔，输入 - 清空）", currentForced);
        List<String> forcedDirectories = ProjectCreateCommand.parsePaths(forcedInput);
        String currentForcedFiles = String.join(
                ",", current.rules().forcedSyncFiles());
        String forcedFilesInput = prompt(
                "强制同步文件（逗号分隔，输入 - 清空）", currentForcedFiles);
        List<String> forcedFiles = ProjectCreateCommand.parsePaths(
                forcedFilesInput);
        String publicUrl = prompt("玩家访问公共 HTTP 地址（必填）", current.publicBaseUrl());
        var services = root.services();
        ProjectRules rules = current.rules()
                .withForcedSyncDirectories(forcedDirectories)
                .withForcedSyncFiles(forcedFiles);
        ProjectRecord updated = services.projects().configure(
                current.id(), displayName, source, publicUrl,
                current.branding(), rules);
        root.out().println("项目设置已更新：" + updated.id());
    }

    private void configurePlayerPersonalization() {
        ProjectRecord current = selectProject();
        if (current == null) return;
        Branding old = current.branding();
        root.out().println();
        root.out().println("玩家端个性化设置");
        root.out().println("================================");
        root.out().println("这里的内容只影响玩家端显示，不会修改项目 ID、管理目录或玩家文件。");
        root.out().println("直接按回车保留当前值；Web 管理页可以一边填写一边查看玩家端预览。");
        root.out().println();
        root.out().println("【窗口品牌】");
        String brandName = prompt(
                "左上角中文品牌名（显示在玩家端窗口左上角）",
                old.brandName());
        String brandEnglishName = prompt(
                "左上角英文品牌名（紧跟在中文品牌名右侧）",
                old.brandEnglishName());
        String productName = prompt(
                "玩家端主标题（显示在首页左侧的大号标题区域）",
                old.productName());
        String subtitle = prompt(
                "玩家端副标题（显示在主标题下方）",
                old.subtitle());
        String serverAddress = prompt(
                "Minecraft 服务器地址（显示在服务器信息区域）",
                old.serverAddress());
        root.out().println();
        root.out().println("【外观与背景】");
        String accent = promptColor("主强调色", old.accentColor());
        String secondaryAccent = promptColor(
                "次强调色", old.secondaryAccentColor());
        String coverInput = readLine(
                "服务器上的新背景图片路径（回车保留，输入 - 移除）：").trim();
        Path cover = null;
        String coverObject = old.coverObject();
        if (coverInput.equals("-")) {
            coverObject = null;
        } else if (!coverInput.isEmpty()) {
            cover = requireRegularFile(coverInput, "背景图片");
        }

        List<PlayerContentPage> contentPages = old.contentPages() == null
                ? migrateLegacyPages(old) : old.contentPages();
        root.out().println();
        root.out().println("【玩家端页面】");
        root.out().println("主页和“关于更新器”固定保留；普通页面适合玩法/规则，公告页可以发布多条新闻。");
        root.out().println("当前已配置 " + contentPages.size() + " 个页面。Web 管理页还提供快捷排版、JSON 导入导出和 AI 提示词。");
        if (confirm("是否管理玩家端页面？", false)) {
            contentPages = editPlayerPages(contentPages);
        }

        Branding branding = new Branding(
                productName, subtitle, serverAddress,
                coverObject, accent, secondaryAccent,
                brandName, brandEnglishName, List.of(), null, contentPages);
        var services = root.services();
        ProjectRecord updated = services.projects().configure(
                current.id(), current.displayName(), current.sourceDirectory(),
                current.publicBaseUrl(), branding, current.rules());
        if (cover != null) {
            updated = services.projects().setCover(current.id(), cover);
        }
        root.out().println();
        root.out().println("玩家端个性化设置已更新：" + updated.id());
        root.out().println("  左上角品牌：" + updated.branding().brandName()
                + " " + updated.branding().brandEnglishName());
        root.out().println("  首页标题：" + updated.branding().productName());
        root.out().println("  首页副标题：" + displayOrNone(updated.branding().subtitle()));
        root.out().println("  服务器地址：" + displayOrNone(
                updated.branding().serverAddress()));
        root.out().println("  自定义背景："
                + (updated.branding().coverObject() == null ? "未设置" : "已设置"));
        root.out().println("  玩家端页面：" + updated.branding().contentPages().size() + " 个");
    }

    private List<PlayerContentPage> migrateLegacyPages(Branding branding) {
        List<PlayerContentPage> pages = new ArrayList<>();
        pages.add(new PlayerContentPage("news", "新闻", true,
                branding.brandEnglishName() + " NEWS", branding.brandName() + "新闻",
                "这里记录服务器动态、版本消息和想与玩家分享的内容。", "",
                branding.newsArticles() == null ? List.of() : branding.newsArticles()));
        PlayerCustomPage custom = branding.customPage();
        if (custom != null && custom.enabled()) {
            pages.add(new PlayerContentPage("custom", custom.navigationLabel(), false,
                    custom.eyebrow(), custom.title(), custom.lead(), custom.markdown(), List.of()));
        }
        return List.copyOf(pages);
    }

    private List<PlayerContentPage> editPlayerPages(List<PlayerContentPage> existing) {
        List<PlayerContentPage> pages = new ArrayList<>(existing);
        while (true) {
            root.out().println();
            root.out().println("玩家端页面（顺序就是顶部导航顺序）：");
            if (pages.isEmpty()) root.out().println("  （未添加页面，只显示主页和关于更新器）");
            for (int i = 0; i < pages.size(); i++) {
                PlayerContentPage page = pages.get(i);
                root.out().println("  [" + (i + 1) + "] " + page.navigationLabel()
                        + (page.announcementPage() ? " · 公告页 · " + page.articles().size() + " 条" : " · 普通页面"));
            }
            root.out().println("  [A] 添加  [E] 编辑  [D] 删除  [U] 上移  [N] 下移  [Q] 完成");
            String choice = readLine("请选择：").trim().toLowerCase(Locale.ROOT);
            switch (choice) {
                case "a" -> {
                    if (pages.size() >= 12) root.out().println("最多只能添加 12 个页面。");
                    else pages.add(promptContentPage(null, pages));
                }
                case "e" -> {
                    int index = promptListIndex("要编辑的页面编号", pages.size());
                    if (index >= 0) pages.set(index, promptContentPage(pages.get(index), pages));
                }
                case "d" -> {
                    int index = promptListIndex("要删除的页面编号", pages.size());
                    if (index >= 0 && confirm("确认删除“" + pages.get(index).navigationLabel() + "”？", false)) pages.remove(index);
                }
                case "u", "n" -> {
                    int index = promptListIndex("要移动的页面编号", pages.size());
                    int target = choice.equals("u") ? index - 1 : index + 1;
                    if (index >= 0 && target >= 0 && target < pages.size()) java.util.Collections.swap(pages, index, target);
                }
                case "q", "" -> { return List.copyOf(pages); }
                default -> root.out().println("请输入 A、E、D、U、N 或 Q。");
            }
        }
    }

    private PlayerContentPage promptContentPage(PlayerContentPage current, List<PlayerContentPage> pages) {
        String id = prompt("页面 ID（英文字母、数字、点、下划线或短横线）", current == null ? "page-" + (pages.size() + 1) : current.id());
        while (true) {
            String candidate = id;
            boolean duplicate = pages.stream().anyMatch(
                    page -> page != current && page.id().equals(candidate));
            if (id.matches("[A-Za-z0-9._-]{1,64}") && !duplicate) break;
            root.out().println("页面 ID 格式无效或已经存在。");
            id = promptRequired("页面 ID");
        }
        String label = prompt("顶部导航名称（最多 12 个字符）", current == null ? "新页面" : current.navigationLabel());
        boolean announcement = confirm("是否设为公告页？公告页可以添加多条新闻", current != null && current.announcementPage());
        String eyebrow = prompt("页面顶部小标题", current == null ? "" : current.eyebrow());
        String title = prompt("页面主标题", current == null ? label : current.title());
        String lead = prompt("页面引导语", current == null ? "" : current.lead());
        if (announcement) {
            List<PlayerNewsArticle> articles = editPlayerNews(current == null || current.articles() == null ? List.of() : current.articles());
            return new PlayerContentPage(id, label, true, eyebrow, title, lead, "", articles);
        }
        String oldMarkdown = current == null ? "" : current.markdown();
        String markdownInput = readLine("页面正文 Markdown（输入 @文件路径读取；回车保留）：");
        String markdown = markdownInput.isBlank() ? oldMarkdown
                : ChangelogInput.interactive(markdownInput, root.settingsFile().getParent());
        return new PlayerContentPage(id, label, false, eyebrow, title, lead, markdown, List.of());
    }

    private List<PlayerNewsArticle> editPlayerNews(List<PlayerNewsArticle> existing) {
        List<PlayerNewsArticle> articles = new ArrayList<>(existing);
        while (true) {
            root.out().println();
            root.out().println("玩家端新闻（按当前顺序展示）：");
            if (articles.isEmpty()) root.out().println("  （还没有新闻）");
            for (int i = 0; i < articles.size(); i++) {
                PlayerNewsArticle article = articles.get(i);
                root.out().println("  [" + (i + 1) + "] " + article.publishedOn()
                        + " · " + article.title() + " (" + article.id() + ")");
            }
            root.out().println("  [A] 添加新闻  [E] 编辑新闻  [D] 删除新闻  [Q] 完成");
            String choice = readLine("请选择：").trim().toLowerCase(Locale.ROOT);
            switch (choice) {
                case "a" -> articles.add(promptNewsArticle(null, articles));
                case "e" -> {
                    int index = promptListIndex("要编辑的新闻编号", articles.size());
                    if (index >= 0) articles.set(index,
                            promptNewsArticle(articles.get(index), articles));
                }
                case "d" -> {
                    int index = promptListIndex("要删除的新闻编号", articles.size());
                    if (index >= 0 && confirm("确认删除“"
                            + articles.get(index).title() + "”？", false)) {
                        articles.remove(index);
                    }
                }
                case "q", "" -> { return List.copyOf(articles); }
                default -> root.out().println("请输入 A、E、D 或 Q。");
            }
        }
    }

    private PlayerNewsArticle promptNewsArticle(
            PlayerNewsArticle current, List<PlayerNewsArticle> articles) {
        String defaultId = current == null ? "" : current.id();
        String id;
        while (true) {
            id = prompt("文章 ID（小写字母、数字和连字符，创建后尽量不要修改）",
                    defaultId);
            String candidate = id;
            boolean duplicate = articles.stream().anyMatch(article -> article != current
                    && article.id().equals(candidate));
            if (id.matches("[a-z0-9][a-z0-9-]{0,63}") && !duplicate) break;
            root.out().println("文章 ID 格式无效或已经存在。");
        }
        String title = prompt("标题", current == null ? "" : current.title());
        while (title.isBlank()) title = promptRequired("标题");
        String summary = prompt("摘要", current == null ? "" : current.summary());
        String date = promptNewsDate(current == null
                ? LocalDate.now().toString() : current.publishedOn());
        String coverUrl = prompt("封面图片网址（http/https，可留空）",
                current == null ? "" : current.coverUrl());
        String existingMarkdown = current == null ? "" : current.markdown();
        String markdownInput = readLine(
                "正文 Markdown（可直接输入一行；输入 @文件路径读取 UTF-8 文本；回车保留当前正文）：");
        String markdown = markdownInput.isBlank()
                ? existingMarkdown
                : ChangelogInput.interactive(markdownInput,
                        root.settingsFile().getParent());
        return new PlayerNewsArticle(id, title, summary, date, coverUrl, markdown);
    }

    private String promptNewsDate(String defaultValue) {
        while (true) {
            String value = prompt("发布日期（YYYY-MM-DD）", defaultValue);
            try {
                LocalDate.parse(value);
                return value;
            } catch (DateTimeParseException ignored) {
                root.out().println("日期必须使用 YYYY-MM-DD 格式，例如 2026-08-04。");
            }
        }
    }

    private PlayerCustomPage editCustomPage(PlayerCustomPage current) {
        PlayerCustomPage defaults = current == null
                ? PlayerCustomPage.disabled() : current;
        boolean enabled = confirm("是否在玩家端顶部导航中启用自定义页面？",
                defaults.enabled());
        if (!enabled) {
            return new PlayerCustomPage(false, defaults.navigationLabel(),
                    defaults.eyebrow(), defaults.title(), defaults.lead(),
                    defaults.markdown());
        }
        String label = prompt("顶部导航名称（最多 12 个字符）",
                defaults.navigationLabel());
        String eyebrow = prompt("页面顶部小标题", defaults.eyebrow());
        String title = prompt("页面主标题", defaults.title());
        String lead = prompt("页面引导语", defaults.lead());
        String markdownInput = readLine(
                "页面正文 Markdown（可直接输入一行；输入 @文件路径读取 UTF-8 文本；回车保留当前正文）：");
        String markdown = markdownInput.isBlank()
                ? defaults.markdown()
                : ChangelogInput.interactive(markdownInput,
                        root.settingsFile().getParent());
        return new PlayerCustomPage(true, label, eyebrow, title, lead, markdown);
    }

    private int promptListIndex(String label, int size) {
        if (size == 0) {
            root.out().println("当前没有可操作的项目。");
            return -1;
        }
        while (true) {
            String value = readLine(label + "（1-" + size + "，回车取消）：").trim();
            if (value.isEmpty()) return -1;
            try {
                int index = Integer.parseInt(value) - 1;
                if (index >= 0 && index < size) return index;
            } catch (NumberFormatException ignored) {
            }
            root.out().println("请输入列表中的有效编号。");
        }
    }

    private void scanAndPublish() {
        ProjectRecord project = selectProject();
        if (project == null) return;
        PublishPreview preview = root.services().scanner().createPreview(project.id());
        printPreview(preview);
        preview = promptRemovalDecisions(project, preview);
        String version = promptRequired("本次显示版本（例如 1.0.1）");
        String minimumPlayer = prompt("最低玩家端程序版本", "0.1.14");
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
        root.out().println("强制同步文件：" + displayForcedFiles(project.rules()));
        if (preview.changes().isEmpty()) {
            root.out().println("本次没有修改。");
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

    private PublishPreview promptRemovalDecisions(
            ProjectRecord project, PublishPreview preview) {
        List<RemovalDecision> decisions = new java.util.ArrayList<>();
        for (var change : preview.changes()) {
            if (change.kind() != ChangeKind.REMOVED) continue;
            boolean forcedDirectory = project.rules().forcedSyncDirectories().stream()
                    .anyMatch(directory -> change.path().toLowerCase(Locale.ROOT)
                            .startsWith(directory.toLowerCase(Locale.ROOT) + "/"));
            if (forcedDirectory) {
                root.out().println("强制同步目录内的文件只能从玩家端移除："
                        + change.path());
                decisions.add(new RemovalDecision(
                        change.path(), RemovalAction.DELETE));
                continue;
            }
            root.out().println();
            root.out().println("源目录中已移除：" + change.path());
            root.out().println("[1] 从玩家端删除");
            root.out().println("[2] 只放弃管理，保留玩家本地文件");
            while (true) {
                String choice = readLine("请选择 1 或 2：").trim();
                if (choice.equals("1")) {
                    decisions.add(new RemovalDecision(
                            change.path(), RemovalAction.DELETE));
                    break;
                }
                if (choice.equals("2")) {
                    decisions.add(new RemovalDecision(
                            change.path(), RemovalAction.RELEASE));
                    break;
                }
                root.out().println("请输入 1 或 2。");
            }
        }
        return decisions.isEmpty() ? preview
                : root.services().scanner().decideRemovals(
                preview.projectId(), decisions);
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
        Path source = promptDirectory("玩家端发行包解压根目录", null, false);
        String minimumBootstrap = prompt("最低启动引导器版本", "0.1.2");
        if (!confirm("自动读取版本并发布这个玩家端程序？", false)) {
            root.out().println("已取消发布。");
            return;
        }
        var stored = root.services().playerPrograms().publish(
                project.id(), platform, "", source, "", minimumBootstrap);
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

    private void createPlayerDeployment() {
        ProjectRecord project = selectProject();
        if (project == null) return;
        root.out().println("此功能不需要完整 Minecraft 实例，输出内容用于合并到本地实例根目录。");
        Path output = promptDirectory("首次部署包输出父目录", null, false);
        String platform = prompt("平台", "windows-x64");
        StoredRelease release = selectReleaseForBundle(project);
        if (!confirm("按整合包基线 " + release.displayVersion()
                + " 生成首次部署包？", false)) {
            root.out().println("已取消生成。");
            return;
        }
        var prepared = root.services().deployments().create(
                project.id(), platform, release.releaseId(), output,
                root.bootstrapAgentPath());
        root.out().println("首次部署包已生成：" + prepared.outputDirectory());
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
        root.out().println("下载服务监听地址决定哪些电脑可以连接：");
        root.out().println("  0.0.0.0 允许其他电脑连接；127.0.0.1 只允许当前电脑连接。");
        root.out().println("这里不填写玩家公网地址；公网地址在各项目设置中单独维护。");
        String host = prompt("下载服务监听地址（必填）", current.httpHost());
        int port = promptPort("下载服务端口（必填）", current.httpPort());
        int webPort = promptWebPort(
                "Web 管理端口（必填）",
                current.webPort(), port);
        root.out().println("Web 监听地址：127.0.0.1 仅本机；0.0.0.0 可公网连接且必须已有账户和 HTTPS 反代。");
        String webHost = prompt("Web 管理监听地址", current.webHost());
        if (!webHost.equals("127.0.0.1") && !webHost.equals("0.0.0.0")) {
            root.out().println("不支持该地址，将保持 127.0.0.1。"); webHost = "127.0.0.1";
        }
        if (webHost.equals("0.0.0.0") && !new WebAuthStore(root.settingsFile().getParent()
                .resolve("management-web-auth.json")).registered()) {
            root.out().println("尚未注册管理账户，不能启用公网监听；请先用 127.0.0.1 启动并注册账户。");
            webHost = "127.0.0.1";
        }
        root.saveSettings(new ManagementSettings(
                ManagementSettings.CURRENT_SCHEMA,
                current.dataDirectory(),
                current.defaultProjectId(),
                host,
                port,
                webPort,
                webHost
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
        serveWeb(true);
    }

    private boolean serveWeb(boolean askBeforeStart) {
        ManagementSettings settings = root.settings();
        String listenAddress = settings.webHost() + ":" + settings.webPort();
        String localAddress = "http://127.0.0.1:" + settings.webPort() + "/";
        if (askBeforeStart && !confirm("启动 Web 管理界面并监听 "
                + listenAddress + "？", true)) {
            return false;
        }
        try (AdminWebServer server = new AdminWebServer(root)) {
            Thread shutdown = new Thread(server::close, "dfs-admin-web-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(shutdown);
                server.start();
                root.out().println("Web 管理界面已启动，监听：" + listenAddress);
                root.out().println();
                root.out().println("如果管理端运行在当前电脑：");
                root.out().println("  请直接在浏览器打开：" + localAddress);
                root.out().println();
                root.out().println("如果管理端运行在远程服务器且保持安全的 127.0.0.1 监听：");
                root.out().println("  1. 在您自己的电脑上打开终端，运行以下 SSH 隧道命令：");
                root.out().println("     ssh -N -L "
                        + settings.webPort() + ":127.0.0.1:" + settings.webPort()
                        + " 用户名@您的服务器地址");
                root.out().println("  2. 保持 SSH 窗口运行，再在自己电脑的浏览器打开：");
                root.out().println("     " + localAddress);
                if (settings.webHost().equals("0.0.0.0")) {
                    root.out().println("公网监听已启用：远程登录必须经 HTTPS 反向代理访问，禁止直接使用明文 HTTP。");
                } else {
                    root.out().println("同机 Caddy/Nginx 可代理 127.0.0.1 到公网 443，这是推荐部署方式。");
                }
                root.out().println();
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
            return true;
        } catch (ManagementException e) {
            root.err().println("Web 管理界面启动失败：" + usefulMessage(e));
            root.out().println("请关闭占用 Web 管理端口的程序，或在主菜单 [8] 修改 Web 管理端口。");
            return false;
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
            throw new ManagementException("无法读取整合包文件目录：" + source, e);
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

    private String promptColor(String label, String defaultValue) {
        while (true) {
            String value = prompt(label + "（#RRGGBB）", defaultValue);
            if (value.matches("#[0-9a-fA-F]{6}")) {
                return value.toLowerCase(Locale.ROOT);
            }
            root.out().println("颜色必须使用 #RRGGBB 格式，例如 #2ee8df。");
        }
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

    private int promptWebPort(
            String label, int defaultValue, int httpPort) {
        while (true) {
            int webPort = promptPort(label, defaultValue);
            if (webPort != httpPort) return webPort;
            root.out().println("Web 管理端口不能与 HTTP 文件服务端口相同，请重新输入。");
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

    private static String displayForcedFiles(ProjectRules rules) {
        return rules.forcedSyncFiles().isEmpty()
                ? "（未启用）"
                : String.join("、", rules.forcedSyncFiles());
    }

    private static String usefulMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static final class InputEnded extends RuntimeException {
    }
}
