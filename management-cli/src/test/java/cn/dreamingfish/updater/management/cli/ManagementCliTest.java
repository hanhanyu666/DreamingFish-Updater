package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementDatabase;
import cn.dreamingfish.updater.management.ManagementPaths;
import cn.dreamingfish.updater.protocol.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCliTest {
    @TempDir
    Path temporary;

    @Test
    void exposesHelpAndCompletesThePublishWorkflow() throws Exception {
        Invocation version = invoke("--version");
        assertEquals(0, version.exitCode());
        assertTrue(version.out().contains("0.1.18"));

        Invocation help = invoke("--help");
        assertEquals(0, help.exitCode());
        assertTrue(help.out().contains("project"));
        assertTrue(help.out().contains("player"));
        assertTrue(help.out().contains("backup"));

        Path data = temporary.resolve("data");
        Path source = Files.createDirectories(temporary.resolve("source"));
        Files.createDirectories(source.resolve("mods"));
        Files.writeString(source.resolve("mods/example.jar"), "cli-content");
        Path cover = temporary.resolve("cover.png");
        Files.writeString(cover, "desktop-cover");

        assertEquals(0, invoke("--data", data.toString(), "init").exitCode());
        Invocation create = invoke(
                "--data", data.toString(), "project", "create", "demo",
                "--name", "Demo Pack",
                "--source", source.toString(),
                "--public-url", "http://127.0.0.1:18080",
                "--force-sync-directories", "mods",
                "--cover", cover.toString()
        );
        assertEquals(0, create.exitCode(), create.err());
        assertTrue(create.out().contains("Demo Pack"));

        Invocation scan = invoke("--data", data.toString(), "project", "scan", "demo");
        assertEquals(0, scan.exitCode(), scan.err());
        assertTrue(scan.out().contains("1 changes"));

        Path changelogFile = temporary.resolve("changelog.txt");
        Files.writeString(changelogFile, "删除信雅互联残留文件\n保留玩家本地设置");
        Invocation publish = invoke(
                "--data", data.toString(), "project", "publish", "demo",
                "--version", "1.0.0",
                "--changelog-file", changelogFile.toString(),
                "--yes"
        );
        assertEquals(0, publish.exitCode(), publish.err());
        assertTrue(publish.out().contains("Published 1.0.0"));

        ManagementPaths managementPaths = ManagementPaths.at(data);
        ManagementDatabase managementDatabase = new ManagementDatabase(
                managementPaths, new JsonCodec());
        managementDatabase.initialize();
        var latestRelease = managementDatabase.latestRelease("demo").orElseThrow();
        String releaseId = latestRelease.releaseId();
        assertEquals("删除信雅互联残留文件\n保留玩家本地设置",
                latestRelease.changelog());
        assertEquals(List.of("mods"), managementDatabase.readManifest(
                latestRelease).forcedSyncDirectories());

        Invocation releases = invoke("--data", data.toString(), "--json", "project", "releases", "demo");
        assertEquals(0, releases.exitCode(), releases.err());
        assertTrue(releases.out().contains("\"displayVersion\":\"1.0.0\""));

        Path playerSource = Files.createDirectories(temporary.resolve("player-program"));
        Files.writeString(playerSource.resolve("player.cmd"), "player-cli-content");
        Invocation playerPublish = invoke(
                "--data", data.toString(), "player", "publish", "demo",
                "--platform", "windows-x64",
                "--version", "0.2.0",
                "--source", playerSource.toString(),
                "--launcher", "player.cmd",
                "--yes"
        );
        assertEquals(0, playerPublish.exitCode(), playerPublish.err());
        assertTrue(playerPublish.out().contains("Published player program 0.2.0"));

        Invocation playerList = invoke(
                "--data", data.toString(), "--json", "player", "list", "demo",
                "--platform", "windows-x64"
        );
        assertEquals(0, playerList.exitCode(), playerList.err());
        assertTrue(playerList.out().contains("\"version\":\"0.2.0\""));

        Path instance = Files.createDirectories(temporary.resolve("instance"));
        Path bootstrap = Files.createDirectories(instance.resolve(".dreamingfish-bootstrap"));
        Files.writeString(bootstrap.resolve("bootstrap-agent.jar"), "agent");
        Path playerHome = Files.createDirectories(instance.resolve("DreamingFishUpdater"));
        Path installedProgram = Files.createDirectories(playerHome.resolve("app/0.2.0"));
        Files.copy(playerSource.resolve("player.cmd"), installedProgram.resolve("player.cmd"));
        Path state = Files.createDirectories(playerHome.resolve("state"));
        Files.writeString(state.resolve("active-player.properties"), """
                schema=1
                version=0.2.0
                launcher=app/0.2.0/player.cmd
                programRoot=app/0.2.0
                manifestSha256=
                timeoutSeconds=3600
                """);
        Invocation binding = invoke(
                "--data", data.toString(), "project", "binding", "demo",
                "--instance", instance.toString(), "--platform", "windows-x64",
                "--release", releaseId
        );
        assertEquals(0, binding.exitCode(), binding.err());
        assertTrue(Files.isRegularFile(
                instance.resolve(".dreamingfish-bootstrap/project-binding.json")));
        assertEquals("desktop-cover", Files.readString(
                instance.resolve(".dreamingfish-bootstrap/project-cover")));
        assertTrue(Files.readString(instance.resolve(
                ".dreamingfish-bootstrap/project-binding.json"))
                .contains("\"bundledCoverPath\":\".dreamingfish-bootstrap/project-cover\""));
        assertTrue(Files.isRegularFile(instance.resolve(
                ".dreamingfish-bootstrap/bundled-release/manifest.json")));
        assertTrue(Files.isRegularFile(instance.resolve("mods/example.jar")));
        String active = Files.readString(state.resolve("active-player.properties"));
        assertTrue(active.matches("(?s).*manifestSha256=[0-9a-f]{64}.*"));
    }

    @Test
    void firstRunWizardPersistsSettingsAndPublishesOnlyTheSourceContents() throws Exception {
        Path adminHome = Files.createDirectories(temporary.resolve("interactive-admin"));
        Path settingsFile = adminHome.resolve("management-settings.json");
        Path data = adminHome.resolve("data").toAbsolutePath().normalize();
        Path source = Files.createDirectories(temporary.resolve("partial-source"));
        Files.createDirectories(source.resolve("mods"));
        Files.createDirectories(source.resolve("config"));
        Files.writeString(source.resolve("mods/example.jar"), "mod-content");
        Files.writeString(source.resolve("config/example.toml"), "config-content");

        String input = String.join(System.lineSeparator(),
                "",
                "18081",
                "18081",
                "",
                "n",
                "interactive-pack",
                "交互测试整合包",
                source.toString(),
                "http://127.0.0.1:18081",
                "",
                "",
                "",
                "",
                "",
                "",
                "n",
                "4",
                "1.0.0",
                "",
                "首次发布",
                "y",
                "0",
                ""
        );

        Invocation invocation = invokeWithInput(settingsFile, input);
        assertEquals(0, invocation.exitCode(), invocation.err());
        assertTrue(invocation.out().contains("首次使用引导"));
        assertTrue(invocation.out().contains("欢迎使用 DreamingFish Updater"));
        assertTrue(invocation.out().contains("[1/3] 管理端数据保存位置"));
        assertTrue(invocation.out().contains(
                "0.0.0.0   = 允许其他电脑连接"));
        assertTrue(invocation.out().contains(
                "127.0.0.1 = 只有当前电脑能连接"));
        assertTrue(invocation.out().contains("这里填写的不是公网 IP"));
        assertTrue(invocation.out().contains(
                "按 Y 进入 Web 管理界面创建，按 N 使用命令行创建"));
        assertTrue(invocation.out().contains("是否现在启动 Web 管理界面并创建第一个项目"));
        assertTrue(invocation.out().contains("[1/3] 创建必备设置"));
        assertTrue(invocation.out().contains("[2/3] 同步策略（可选）"));
        assertTrue(invocation.out().contains("[3/3] 玩家端个性化"));
        assertTrue(invocation.out().contains(
                "主菜单 [12]“修改玩家端个性化设置”继续调整"));
        assertTrue(invocation.out().contains("玩家端更新器访问地址（必填）"));
        assertTrue(invocation.out().contains("显示在首页左侧的大号标题区域"));
        assertTrue(invocation.out().contains("显示在主标题下方"));
        assertTrue(invocation.out().contains(
                "Web 管理端口不能与 HTTP 文件服务端口相同，请重新输入"));
        assertTrue(invocation.out().contains("  " + data));
        assertTrue(invocation.out().contains("非常重要，请勿删除"));
        assertTrue(invocation.out().contains("移动或复制到新版管理端根目录"));
        assertTrue(!invocation.out().contains("管理数据目录 ["));
        assertTrue(invocation.out().contains("本次内容范围：config/、mods/"));
        assertTrue(invocation.out().contains("实际将保存的更新记录："));
        assertTrue(invocation.out().contains("首次发布"));
        assertTrue(invocation.out().contains("发布完成：1.0.0"));
        assertTrue(Files.isRegularFile(settingsFile));

        ManagementSettings settings = new ManagementSettingsStore(settingsFile).load();
        assertEquals(data.toAbsolutePath().normalize().toString(), settings.dataDirectory());
        assertEquals("interactive-pack", settings.defaultProjectId());
        assertEquals(18081, settings.httpPort());
        assertEquals(18080, settings.webPort());

        ManagementPaths paths = ManagementPaths.at(data);
        ManagementDatabase database = new ManagementDatabase(paths, new JsonCodec());
        database.initialize();
        var createdProject = database.requireProject("interactive-pack");
        assertEquals("交互测试整合包", createdProject.branding().productName());
        assertEquals("Minecraft 整合包更新", createdProject.branding().subtitle());
        assertEquals("梦鱼服", createdProject.branding().brandName());
        assertEquals("DreamingFish", createdProject.branding().brandEnglishName());
        var release = database.latestRelease("interactive-pack").orElseThrow();
        assertEquals("首次发布", release.changelog());
        var managedPaths = database.readManifest(release).files().stream()
                .map(file -> file.path())
                .toList();
        assertEquals(List.of("config/example.toml", "mods/example.jar"), managedPaths);

        String secondInput = String.join(System.lineSeparator(),
                "8",
                "127.0.0.1",
                "18082",
                "",
                "0",
                ""
        );
        Invocation secondRun = invokeWithInput(settingsFile, secondInput);
        assertEquals(0, secondRun.exitCode(), secondRun.err());
        assertTrue(!secondRun.out().contains("首次使用引导"));
        assertTrue(secondRun.out().contains("[8] 修改服务设置"));
        assertTrue(secondRun.out().contains("服务设置已保存。"));
        assertTrue(!secondRun.out().contains("修改管理数据目录"));

        ManagementSettings updated = new ManagementSettingsStore(settingsFile).load();
        assertEquals(data.toString(), updated.dataDirectory());
        assertEquals("127.0.0.1", updated.httpHost());
        assertEquals(18082, updated.httpPort());
        assertEquals(18080, updated.webPort());

        String personalizationInput = String.join(System.lineSeparator(),
                "12",
                "星河服",
                "StarRiver",
                "星河主页",
                "和朋友一起探索新的世界",
                "play.example.com:25565",
                "not-a-color",
                "#112233",
                "#445566",
                "-",
                "",
                "",
                "0",
                ""
        );
        Invocation personalizationRun = invokeWithInput(
                settingsFile, personalizationInput);
        assertEquals(0, personalizationRun.exitCode(), personalizationRun.err());
        assertTrue(personalizationRun.out().contains(
                "[12] 修改玩家端个性化设置"));
        assertTrue(personalizationRun.out().contains(
                "颜色必须使用 #RRGGBB 格式"));
        assertTrue(personalizationRun.out().contains(
                "玩家端个性化设置已更新：interactive-pack"));

        var personalizedProject = database.requireProject("interactive-pack");
        assertEquals("星河服", personalizedProject.branding().brandName());
        assertEquals("StarRiver",
                personalizedProject.branding().brandEnglishName());
        assertEquals("星河主页",
                personalizedProject.branding().productName());
        assertEquals("和朋友一起探索新的世界",
                personalizedProject.branding().subtitle());
        assertEquals("play.example.com:25565",
                personalizedProject.branding().serverAddress());
        assertEquals("#112233",
                personalizedProject.branding().accentColor());
        assertEquals("#445566",
                personalizedProject.branding().secondaryAccentColor());
    }

    @Test
    void migratesSchemaOneSettingsToTheLoopbackWebDefault() throws Exception {
        Path adminHome = Files.createDirectories(temporary.resolve("legacy-admin"));
        Path settingsFile = adminHome.resolve("management-settings.json");
        Files.writeString(settingsFile, """
                {
                  "schemaVersion": 1,
                  "dataDirectory": "legacy-data",
                  "defaultProjectId": "",
                  "httpHost": "0.0.0.0",
                  "httpPort": 8080
                }
                """);

        ManagementSettings settings = new ManagementSettingsStore(settingsFile).load();

        assertEquals(ManagementSettings.CURRENT_SCHEMA, settings.schemaVersion());
        assertEquals(adminHome.resolve("legacy-data").toAbsolutePath().normalize().toString(),
                settings.dataDirectory());
        assertEquals("0.0.0.0", settings.httpHost());
        assertEquals(8080, settings.httpPort());
        assertEquals(ManagementSettings.DEFAULT_WEB_PORT, settings.webPort());
        assertEquals("127.0.0.1", settings.webHost());
    }

    @Test
    void migratesSchemaTwoSettingsToTheLoopbackWebDefault() throws Exception {
        Path adminHome = Files.createDirectories(temporary.resolve("schema-two-admin"));
        Path settingsFile = adminHome.resolve("management-settings.json");
        Files.writeString(settingsFile, """
                {
                  "schemaVersion": 2,
                  "dataDirectory": "legacy-data",
                  "defaultProjectId": "",
                  "httpHost": "0.0.0.0",
                  "httpPort": 8080,
                  "webPort": 18081
                }
                """);

        ManagementSettings settings = new ManagementSettingsStore(settingsFile).load();

        assertEquals(ManagementSettings.CURRENT_SCHEMA, settings.schemaVersion());
        assertEquals(18081, settings.webPort());
        assertEquals("127.0.0.1", settings.webHost());
    }

    @Test
    void relocatedAdminPrefersAndPersistsItsLocalExistingDataDirectory() throws Exception {
        Path newHome = Files.createDirectories(temporary.resolve("new-admin"));
        Path settingsFile = newHome.resolve("management-settings.json");
        Path oldData = temporary.resolve("old-version/data").toAbsolutePath().normalize();
        Files.writeString(settingsFile, """
                {
                  "schemaVersion": 2,
                  "dataDirectory": "%s",
                  "defaultProjectId": "",
                  "httpHost": "0.0.0.0",
                  "httpPort": 8080,
                  "webPort": 18080
                }
                """.formatted(oldData.toString().replace("\\", "\\\\")));
        Path localData = Files.createDirectories(newHome.resolve("data"));
        Files.writeString(localData.resolve("management.db"), "moved-database");

        ManagementSettings settings = new ManagementSettingsStore(settingsFile).load();

        assertEquals(localData.toAbsolutePath().normalize().toString(),
                settings.dataDirectory());
        assertTrue(Files.readString(settingsFile)
                .contains(localData.toAbsolutePath().normalize().toString()
                        .replace("\\", "\\\\")));
    }

    private Invocation invoke(String... args) {
        StringWriter standardOutput = new StringWriter();
        StringWriter standardError = new StringWriter();
        int exit = ManagementCli.execute(
                args,
                new PrintWriter(standardOutput, true),
                new PrintWriter(standardError, true)
        );
        return new Invocation(exit, standardOutput.toString(), standardError.toString());
    }

    private Invocation invokeWithInput(Path settingsFile, String input, String... args) {
        StringWriter standardOutput = new StringWriter();
        StringWriter standardError = new StringWriter();
        int exit = ManagementCli.execute(
                args,
                new StringReader(input),
                settingsFile,
                new PrintWriter(standardOutput, true),
                new PrintWriter(standardError, true)
        );
        return new Invocation(exit, standardOutput.toString(), standardError.toString());
    }

    private record Invocation(int exitCode, String out, String err) {
    }
}
