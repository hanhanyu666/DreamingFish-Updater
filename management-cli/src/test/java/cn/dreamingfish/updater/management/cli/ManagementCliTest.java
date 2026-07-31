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
        assertTrue(version.out().contains("0.1.16"));

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
                "",
                "interactive-pack",
                "交互测试整合包",
                source.toString(),
                "",
                "",
                "http://127.0.0.1:18081",
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
        assertTrue(invocation.out().contains("首次运行配置"));
        assertTrue(invocation.out().contains("不是玩家访问的公网地址"));
        assertTrue(invocation.out().contains("Web 管理界面并创建第一个项目"));
        assertTrue(invocation.out().contains("玩家访问公共 HTTP 地址（必填）"));
        assertTrue(invocation.out().contains(
                "Web 管理端口不能与 HTTP 文件服务端口相同，请重新输入"));
        assertTrue(invocation.out().contains("管理数据目录：" + data));
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
        assertTrue(!secondRun.out().contains("首次运行配置"));
        assertTrue(secondRun.out().contains("[8] 修改服务设置"));
        assertTrue(secondRun.out().contains("服务设置已保存。"));
        assertTrue(!secondRun.out().contains("修改管理数据目录"));

        ManagementSettings updated = new ManagementSettingsStore(settingsFile).load();
        assertEquals(data.toString(), updated.dataDirectory());
        assertEquals("127.0.0.1", updated.httpHost());
        assertEquals(18082, updated.httpPort());
        assertEquals(18080, updated.webPort());
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
