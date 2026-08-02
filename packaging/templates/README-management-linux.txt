DreamingFish 管理端 Linux x64

本包自带 Java 21 运行时，不需要另外安装 Java。如果曾在 Windows 解压，或通过 VS Code 覆盖到 Linux，文件的执行权限可能会丢失。首次部署或遇到 Permission denied 时执行：
  chmod 755 dfs-admin runtime/bin/* runtime/lib/jspawnhelper
  ./dfs-admin

Linux 启动脚本已自动启用 Java IPv4 网络栈，确保公共 HTTP 服务实际监听 IPv4 的 0.0.0.0；不需要再手动设置 JAVA_TOOL_OPTIONS。

不带参数会进入中文交互终端。管理数据自动保存在本目录的 data 文件夹中，不需要配置；首次向导会先说明数据位置，再依次询问玩家下载服务与 Web 管理页面端口。
要管理的文件目录不需要是完整游戏目录。目录里放什么就管理什么，可以只放 mods，也可以只放 config。Web“管理文件”页顶部可上传、导入、归档并移除文件。

主菜单选择 [10] 可启动 Web 管理界面：
  http://127.0.0.1:18080/
首次打开 Web 页面需要注册管理员账户。默认只监听本机；请从自己的电脑建立 SSH 隧道完成注册：
  ssh -N -L 18080:127.0.0.1:18080 用户名@您的服务器地址
需要公网长期访问时，请使用同机 Caddy/Nginx 把 HTTPS 代理到 127.0.0.1:18080；不要把明文 HTTP 登录端口直接开放公网。
菜单 [9] 和 [10] 运行时按 Ctrl+C 会停止当前服务并返回主菜单。

第一次部署请先阅读本包中的 QUICKSTART.md；遇到远程部署、备份或故障时再查 DEPLOYMENT.md。

正式部署要点：
- 管理数据目录、标准源目录和玩家实例必须分开；不要把包含项目私钥的管理数据发给玩家。
- 项目 public-url 必须是玩家电脑可访问的公网 IP 或域名，不能把 127.0.0.1、localhost 或 0.0.0.0 发给玩家。
- 发布玩家端程序时只选择玩家端 ZIP 的解压根目录，版本号和 app-image 会自动识别。
- 先发布玩家端程序和首个整合包版本，再在 Web“玩家实例”中生成首次部署包。
- 管理服务器无需存放完整 Minecraft；将薄部署包下载并合并到本地实例即可。必须选择该下载包对应的历史发布基线。
- 强制同步可按一级目录或单文件配置；源文件移除时必须选择玩家删除或放弃管理并保留。
- 升级时保留 data、management-settings.json 和 management-web-auth.json（已注册账户时）；新版会自动修正设置中的旧 data 绝对路径。
- HTTP 服务示例：
  ./dfs-admin serve --host 0.0.0.0 --port 8080
- 健康检查：
  http://127.0.0.1:8080/healthz

运行 ./dfs-admin --help 可查看全部参数式命令。
