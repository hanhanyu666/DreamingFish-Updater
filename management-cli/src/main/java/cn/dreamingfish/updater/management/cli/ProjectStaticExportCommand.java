package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
        name = "export-static",
        description = "Export an upload-ready static directory for HTTP, OSS or CDN hosting"
)
final class ProjectStaticExportCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;

    @CommandLine.Parameters(index = "0", description = "Project ID")
    String projectId;

    @CommandLine.Option(names = "--output", required = true,
            description = "Empty directory or an earlier static export of this project")
    Path output;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        var result = root.services().staticDistribution()
                .exportProject(projectId, output);
        if (root.jsonOutput) {
            root.printJson(result);
            return;
        }
        root.out().println("外部托管目录已导出：" + result.outputDirectory());
        root.out().println("  整合包版本：" + result.releaseCount());
        root.out().println("  玩家端程序版本：" + result.playerProgramCount());
        root.out().println("  内容对象：" + result.objectCount()
                + "（本次复制 " + result.copiedObjectCount()
                + "，复用 " + result.reusedObjectCount() + "）");
        root.out().println("  本次新增数据："
                + HumanSize.format(result.copiedObjectBytes()));
        root.out().println("请把目录内的全部文件原样上传到公开 HTTP、对象存储或 CDN 根目录。");
    }
}
