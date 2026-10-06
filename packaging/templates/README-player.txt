DreamingFish 玩家端 0.2.0 · 通用发行包

这个包不绑定任何 Minecraft 服务器，不含服务器新闻、账号或签名私钥。
project-binding.example.json 只是格式示例，不能直接拿来连接你的项目。

服主准备实例（推荐在网页操作）：
先在管理端“玩家端程序”上传本发行 ZIP，核对版本和平台后确认发布。
随后在“玩家实例”下载该项目的部署 ZIP，合并到版本隔离目录，再按官网教程接入 Agent。
部署包才有真实的项目地址、公钥与签名基线，不要把通用发行包直接发给玩家。

已解压包的终端接入方式：
1. 把本包全部内容解压到开启版本隔离的 Minecraft 实例根目录，不要漏掉 .dreamingfish-bootstrap。
2. 先在管理端发布与本包版本一致的玩家端程序。发布源应选择
   DreamingFishUpdater\app\<版本号>，该目录内应直接包含 DreamingFishUpdater.exe、app 和 runtime。
3. 运行：
   dfs-admin project binding <项目ID> --instance <实例目录> --platform windows-x64 --release <发布ID>
   该命令会写入真实绑定、准备首个签名程序、补齐所选发布文件并写入签名基线；不要直接使用 project-binding.example.json。
4. 在 PCL 的该版本 JVM 参数中加入：
   -javaagent:"{verpath}.dreamingfish-bootstrap/bootstrap-agent.jar"
   必须保留 {verpath}，不要改成服主电脑上的绝对路径。
5. 用 PCL 完整测试一次后，再分发整个实例。

玩家使用：
- 不要双击 DreamingFishUpdater.exe，正常从 PCL 启动这个 Minecraft 版本即可。
- 更新器固定使用实例内 project-binding.json 指定的相对目录，默认是 DreamingFishUpdater；不会在首次启动询问或擅自改位置。
- 普通目录中的玩家额外模组默认保留。强制目录、持续删除与问题版本要求仍会处理匹配副本，豁免、停用或装回不能绕过。
- 首次提供的文件之后交给玩家维护，删除后不会反复补回。
- 在“本地文件”的“管理范围”中，可以让普通同步不再管理某个文件或整个目录；“模组启停”可停用不兼容或不需要的模组。选择只保存在本机。
- 点击“恢复全部管理”或“恢复整合包默认”后，按提示关闭更新器，再从 MC 启动器重新启动游戏。
- 远程管理端对某个一级目录启用强制同步后，本地豁免不能绕过该目录，额外文件会移入 DreamingFishUpdater\backups\forced-sync，并在界面中明确提示和提供打开按钮。
- 默认日志位于 DreamingFishUpdater\logs\player-updater.log。

玩家端只更新整合包、显示服务器信息和日志，不接管 Minecraft 账号或游戏启动配置。

PCL/HMCL 接入与完整教程：
https://dreamingfish-studio.github.io/DreamingFish-Updater-Website/docs/quickstart.html
