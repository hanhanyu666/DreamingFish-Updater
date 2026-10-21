# DreamingFish Admin Desktop

Tauri 2 管理端桌面版，版本 0.2.8，Windows x64。

连接页和工作区使用 Vue 3 / TypeScript / Vite，作为独立桌面前端随程序打包。本机、HTTPS 和 SSH 连接都加载同一套桌面资源，再调用当前管理端的业务接口。Rust 负责本机核心、连接代理、SSH 隧道、窗口与托盘；文件维护、认证、签名、发布与历史恢复继续由 Java 管理核心处理。

同一工作区源码也构建为管理端新版网页。`npm --prefix admin-desktop run build:web` 输出 `web-dist/`，Maven 将它打入管理端 JAR 的 `/workbench/` 资源目录；旧版保留在 `/legacy/`，页面内可切换。两者均直接调用同源 API，网页不使用桌面令牌和原生命令。

使用说明见 [管理端桌面版](../docs/ADMIN-DESKTOP.md)，实施与验收记录见 [实施计划](../docs/ADMIN-DESKTOP-PLAN.md)。

## 构建

需要 Node.js 22.18 或更新版本、Rust、Windows C++ 构建工具和完整 Java 21 JDK。在仓库根目录运行：

```powershell
.\packaging\build-admin-desktop.ps1 -JdkHome 'C:\Program Files\Java\jdk-21'
```

脚本构建玩家端预览、Java 管理核心与桌面界面，运行 Java 和 Rust 测试，再生成带精简 Java 的便携 ZIP 与 NSIS 安装程序。安装程序在缺少系统 WebView2 时下载官方运行环境，不附带离线 WebView2。

输出位于仓库 `dist/`：

- `dfs-admin-desktop-windows-x64/DreamingFishAdminDesktop.exe`
- `dfs-admin-desktop-windows-x64-0.2.8.zip`
- `dfs-admin-desktop-windows-x64-0.2.8-setup.exe`
- `dfs-admin-desktop-windows-x64-0.2.8-setup.exe.sig`：正式签名构建的公开更新签名
- `latest.json`：安装版自动更新读取的版本信息

安装程序会用更新私钥签名，默认读取 `%USERPROFILE%\.tauri\dreamingfish-admin-desktop.key`，可用 `-SigningKey` 指定。私钥不放进仓库，务必另外备份：已安装的桌面端只接受这把私钥签名的更新，丢失后只能让用户手动重装。没有私钥时加 `-NoUpdaterSignature` 只能做本地测试包。生成新私钥用 `npx tauri signer generate --ci -w <路径>`，并把公钥写进 `tauri.conf.json` 的 `plugins.updater.pubkey`。

桌面端从 `releases/latest/download/latest.json` 读取更新，所以每个正式 Release 都要带上 `latest.json`。推送提交和标签后，用发布脚本创建 Release，它会验证新安装包、签名和 `latest.json` 的版本 / 标签 / URL 一致，再生成 `SHA256SUMS.txt`。没有新安装包时才可沿用既有有效更新信息；不能用旧元数据配新安装包。发布后再检查自动更新地址：

```powershell
.\packaging\publish-release.ps1 -Tag v0.2.8 -NotesFile <发布说明.md>
```

加 `-DryRun` 只生成只读计划，不调用 gh / git、不下载、不修改 dist / 校验和或临时文件；相对发布说明在调用位置解析。`latest.json` 中的下载地址默认指向 `v<桌面版版本>` 标签，标签不同时打包时用 `-ReleaseTag` 指定。便携版压缩包里带一层“梦鱼更新管理”文件夹。

只生成便携包可加 `-PortableOnly`。已构建核心可加 `-SkipBuildCore`；`-SkipTests` 仅用于已经验证过的构建。公开包只包含程序、Java 和 Bootstrap Agent，不包含管理账户、项目或密钥。

## 开发

先执行一次打包脚本，准备 `src-tauri/resources/core/`。然后：

```powershell
npm --prefix admin-desktop ci
npm --prefix admin-desktop run tauri -- dev
```

Vite 使用 `127.0.0.1:1421`。仅内置 `main` 窗口拥有连接和生命周期命令权限；外部 `workspace` 仅能通过 `desktop_window_action` 操作当前窗框。所有命令同时检查调用窗口和页面来源，工作区窗框还检查当前连接的 origin。

标题栏样式位于 `src/desktop-chrome.css`，初始化脚本为 `src/desktop-chrome.js`。`src/Launcher.vue` 和 `src/launcher.css` 实现连接库，`src/workbench/` 实现工作区的界面、状态与接口调用。文件与目录资源和真实玩家预览在 Vite 构建结束时复制到输出目录。标题栏初始化等待 DOM 就绪，播放器预览 iframe 不加载桌面窗框。无边框窗口的圆角由 `src-tauri/src/frame.rs` 的 `round_corners` 向 Windows 11 DWM 申请。

`src-tauri/src/workbench.rs` 为每次连接创建只监听 `127.0.0.1` 的工作区服务。静态资源来自 Tauri 内置资产；只有 `/api/` 转发到选定管理端，校验 Host、Origin 和每次连接的随机令牌。管理会话 cookie 留在连接客户端的内存中，不下发到浏览器；保留管理端自己的认证、CSRF 和恢复预览校验。上传和接口响应以流传输，断开时释放工作区监听端口与连接客户端。

本机 Java 入口为 `dfs-admin desktop`，通过标准输出发出带 PID 的就绪 JSON，并通过标准输入接收 `{"command":"shutdown"}`。管理端只监听自动分配的 loopback 端口，数据目录加锁，桌面外壳关闭所属服务时保留已有网页配置。Windows Job Object 在外壳异常退出时清理所属子进程。

桌面端的连接记录、日志、界面缓存和默认管理目录放在程序旁的 `data`；程序目录不能写入时退回 `%LOCALAPPDATA%\cn.dreamingfish.admin.desktop`。旧数据迁移采用持久记录、复制核对和显式提交，实际根由结果返回；连接 v2 保存根内相对路径，外部目录保持绝对身份，歧义必须重新定位。迁移清理可能留下可恢复材料。两个窗口的浏览器 / 渲染退出与无响应共享各自一分钟三次恢复预算；退避 200 / 400 / 800 ms，重建后的旧回调丢弃，达到上限后允许托盘手动恢复。记录写入实际 data 根的 `logs/desktop.log`。程序目录有 `uninstall.exe` 时视为安装版，可以自动更新；便携版只提示新版本。安装程序钩子与中文文字在 `src-tauri/installer/`，侧边图和顶部图由 `render-art.py` 从图标绘制。

测试可用 `--desktop-home <绝对路径>` 隔离连接记录和默认管理目录。此参数不会更改发行包默认的数据位置。

`--background-check` 配合独立的 `--desktop-home` 可验收生产 WebView、实际桌面前端加载、窗框 IPC 和退出清理，整个过程不显示窗口，结果写入该目录的 `background-check.json`。默认验证本机；`--background-profile <连接ID>` 验证已保存的连接。构建或替换资源前退出开发实例，避免 Windows 锁住运行中的 Java 文件。


### 本地修复中的数据定位约定（2026-10-18）

连接配置 v2 将实际桌面 data 根目录内的 home 保存为相对路径，加载时相对于本次实际根解析；用户选择的外部 home 保持绝对路径。搬家后旧绝对路径有多个可能副本或原目录缺失时，连接页要求用选择目录按钮重新定位，不自动创建空库。标准源目录独立配置，不随 home 擅自改写。

迁移先保存 migration.json，再复制并逐字节核对，管理设置与 connections.json 均验证后才切换。失败提示中的“实际使用”路径由迁移结果决定；中途退出后按记录继续。源数据使用中的迁移被拒绝。损坏主配置可以从有效 previous 恢复，损坏原件和有效备份保留；无法恢复时明确报错。旧日志、未知文件或恢复证据未能清理时，会报告待清理，不能视作无残留。迁移额外需要一份管理目录的可用空间。

新保存的 Java 默认 dataDirectory 使用相对于 home 的 data；外部数据目录保持原身份。历史绝对 dataDirectory 缺失而本机又有数据库时，需在设置文件明确确认位置，避免静默改用另一副本。

### 统一验证与资源身份

在仓库根执行 `packaging/validate.ps1 -JdkHome <Java21路径> -IsolatedRoot <新的绝对目录> -WithPackaging -WithDesktopSmoke`。隔离目录必须不存在，不清理真实数据。可用 `-MavenRepository` 复用依赖缓存；输出为本地未签名测试包，不可作为自动更新。两套前端都必须在 Maven / Cargo 之前构建；缺失、源码不匹配或资产被改动的 build-info 会拒绝打包。JAR、前端、原生外壳和就绪消息共享源码构建 ID，`check-artifact-identity.py` 再检查嵌入资源。

后台检查从进入 main 即有独立绝对期限，默认 45 秒；`--background-timeout-ms` 可设 1–120 秒。`--background-fault both/limit` 测真实隐藏窗口的模拟故障与手动恢复，`--simulate-init-hang` 测初始化阻塞。它们只与新建的 `--desktop-home` 配合，不使用真实 AppData。

代理默认 128 个连接、16 个活动请求，短 API 总 90 秒 / 无进展 30 秒；大正文和长兼容 API 总 3600 秒、正文无进展 60 秒，长 API 响应头 1200 秒，下游写停滞 30 秒。`DFS_PROXY_TRANSFER_SECONDS` 可设 60–86400；扩展总期限不取消无进展上限。任务式 API 可跨连接查询，超时不会自动重放写入。

管理 JVM 默认 `-Xms64m -Xmx512m`，`DFS_JAVA_HEAP_MB` 只接受 128–16384 的整数。构建机与运行时版本、测试矩阵和实测指标见[本地修复结果](../docs/reviews/2026-10-18-remediation-results.md)。
