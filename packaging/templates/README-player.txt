DreamingFish 玩家端 0.3.4 · 通用发行包

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
4. 备份该版本的 JSON，在 arguments.jvm 数组中追加字符串：
   "-javaagent:.dreamingfish-bootstrap/bootstrap-agent.jar"
   保留其他参数；同一 Agent 只添加一次。相对路径要求启动时工作目录能定位实例内的 Agent，实际游戏目录也应指向实例。
   若启动器覆盖版本 JSON 或工作目录不同，使用启动器支持的版本级 JVM 参数；PCL 可回退为：
   -javaagent:"{verpath}.dreamingfish-bootstrap/bootstrap-agent.jar"
   {verpath} 只适用于 PCL，不适用于 HMCL、美西螈或普通命令行。
5. 在实际使用的启动器中完整测试一次，再分发整合包。

玩家使用：
- 不要双击 DreamingFishUpdater.exe，正常从配置好的 MC 启动器启动这个版本即可。
- 更新器固定使用实例内 project-binding.json 指定的相对目录，默认是 DreamingFishUpdater；不会在首次启动询问或擅自改位置。
- 普通目录中的玩家额外模组默认保留。强制目录、持续删除与问题版本要求仍会处理匹配副本，豁免、停用或装回不能绕过。
- 首次提供的文件之后交给玩家维护，删除后不会反复补回。
- 在“本地文件”的“管理范围”中，可以让普通同步不再管理某个文件或整个目录；“模组启停”可停用不兼容或不需要的模组。选择只保存在本机。
- 点击“恢复全部管理”或“恢复整合包默认”后，按提示关闭更新器，再从 MC 启动器重新启动游戏。
- 远程管理端对某个一级目录启用强制同步后，本地豁免不能绕过该目录，额外文件会移入 DreamingFishUpdater\backups\archive，并在界面中明确提示和提供打开按钮。
- 默认日志位于 DreamingFishUpdater\logs\player-updater.log。

玩家端不接管 Minecraft 账户。服主明确配置时，可更新指定的整合包外层文件和合并启动器配置字段；其他设置保留。
玩家端程序由服主批准指定版本后自动升级；个性化展示和整合包内容仍由服主独立发布。

PCL/HMCL 接入与完整教程：
https://dreamingfish-studio.github.io/DreamingFish-Updater-Website/docs/quickstart.html
