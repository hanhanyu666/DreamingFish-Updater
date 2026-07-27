DreamingFish 玩家端实例模板

服主准备实例：
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
- 普通目录中的玩家额外模组会保留并只显示提醒。
- 在“本地文件”的“管理范围”中，可以让普通同步不再管理某个文件或整个目录；“模组启停”可停用不兼容或不需要的模组。选择只保存在本机。
- 远程管理端对某个一级目录启用强制同步后，本地豁免不能绕过该目录，额外文件会移入 DreamingFishUpdater\backups\forced-sync，并在界面中明确提示和提供打开按钮。
- 默认日志位于 DreamingFishUpdater\logs\player-updater.log。

玩家端只更新整合包、显示服务器信息和日志，不接管 Minecraft 账号或游戏启动配置。
