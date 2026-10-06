# DreamingFish 管理端与玩家端完整配置参考

本文从一台全新的服务器开始，完整说明如何配置管理端、发布整合包、制作玩家端并接入 PCL。

只想先把系统跑起来，请优先阅读 [最简使用教程](./QUICKSTART.md)。本文保留远程中转、参数式命令、备份和故障处理等完整细节，第一次部署不需要从头读完。

主流程以 Windows 管理服务器、Windows 玩家和 PCL 为例。Linux 管理端的差异会在对应步骤中单独说明。

## 脚本与 AI agent 调用

管理端同时提供参数式 CLI，不需要进入菜单或打开网页。Linux 使用 `./dfs-admin`，Windows 使用 `DreamingFishAdmin.exe`。日常项目和文件维护、三种维护方式、可选内容、问题文件处理、扫描发布、玩家程序发布、部署包生成及操作记录都可以用命令完成。

下面以 Linux 为例，`my-modpack` 换成自己的项目 ID：

```sh
./dfs-admin --json project files my-modpack
./dfs-admin project policy preset my-modpack config INITIAL --directory
./dfs-admin --json project scan my-modpack
./dfs-admin --json project publish my-modpack --version 1.0.1 --minimum-player-version 0.2.0 --changelog "更新模组和配置" --yes
```

`--json` 把结果交给程序读取，有确认步骤的操作显式传入 `--yes` 即可非交互执行。需要项目的命令缺少项目 ID、缺少必填参数或检查未通过时，会返回失败退出码；`--yes` 仍会检查签名、文件路径、预览是否过期和玩家端兼容性。

通常先读取清单、整理文件、扫描，再根据 JSON 预览决定是否发布。预览里有移除项时，明确使用 `--removed-files DELETE`（移出玩家副本）或 `--removed-files RELEASE`（停止维护、留给玩家）。已有兼容玩家端程序后再发布新规则。

查询、撤销和恢复设置也有直接入口：

```sh
./dfs-admin --json project operations my-modpack
./dfs-admin --json project operations my-modpack --undo 操作ID --yes
./dfs-admin --json project operations my-modpack --restore-published --yes
```

撤销与恢复的结果包含 `preview` 和 `result`。远程操作时，agent 可以通过 SSH 在管理服务器运行这些命令；Web 和 CLI 读取同一份管理数据。需要指定数据目录时加 `--data /绝对路径/data`。

## 一、先理解三个位置

整个系统只需要分清三个位置：

```text
管理端根目录
    保存管理程序
    自动生成 data 数据目录

标准整合包目录
    由服主维护
    可以只包含 mods 和 config

玩家实例目录
    玩家真正运行的 Minecraft 版本隔离实例
```

推荐使用下面的结构：

```text
C:\DreamingFishAdmin\
    DreamingFishAdmin.exe
    app\
    runtime\
    support\bootstrap-agent.jar  生成首次部署包时使用
    data\                       由程序自动生成，不需要手动配置
    management-settings.json   由程序自动生成

C:\DreamingFishSource\building_server\
    mods\                       服主放入要发布的模组
    config\                     服主放入要发布的配置
```

这三个位置的作用完全不同：

| 位置 | 谁来维护 | 放什么 |
| --- | --- | --- |
| 管理端根目录 | 管理端程序 | 程序、HTTP 设置、自动生成的 `data/` |
| 标准整合包目录 | 服主 | 希望下发给玩家的 `mods/`、`config/` 等文件 |
| 玩家实例目录 | PCL 和玩家端 | 完整 Minecraft 实例及更新器 |

不要手动把 `mods/` 或 `config/` 放进管理端的 `data/`。`data/` 保存数据库、项目私钥、签名、历史版本和文件对象，只能由管理端维护，也绝不能发给玩家。

## 二、配置管理端

### 1. 解压管理端

Windows 使用：

```text
dfs-admin-windows-x64-<版本号>.zip
```

把压缩包内容解压到一个长期使用的固定目录，推荐：

```text
C:\DreamingFishAdmin
```

不要每次升级都新建一个带版本号的管理端目录。管理数据位于根目录的 `data/`，升级时应保留 `data/` 和 `management-settings.json`。

管理端自带 Java 21，不需要安装 Java。

### 2. 第一次启动

在 PowerShell 中运行：

```powershell
cd C:\DreamingFishAdmin
.\DreamingFishAdmin.exe
```

也可以直接双击 `DreamingFishAdmin.exe`。EXE 会自动识别 Windows 控制台当前
代码页并选择匹配的中文输出编码；Web、文件和重定向输出仍统一使用 UTF-8。
旧系统终端的 GB18030 输入也会兼容处理，不需要手动执行 `chcp`。

第一次启动会显示：

```text
DreamingFish 整合包更新管理端
================================

首次运行配置
管理数据会自动保存在管理端根目录的 data 文件夹中。
管理数据目录：C:\DreamingFishAdmin\data
HTTP 监听地址 [0.0.0.0]：
HTTP 监听端口 [8080]：
```

这里真正需要填写的只有两项：

| 终端问题 | 推荐填写 | 说明 |
| --- | --- | --- |
| HTTP 监听地址 | 直接回车，使用 `0.0.0.0` | 允许其它电脑连接这台服务器 |
| HTTP 监听端口 | 直接回车，使用 `8080` | 端口冲突时才需要修改 |

`管理数据目录` 只是一条状态信息，不需要输入，也不能在交互界面修改。

随后程序会自动生成：

```text
C:\DreamingFishAdmin\data\
C:\DreamingFishAdmin\management-settings.json
```

### 2.1 启动本机 Web 管理界面

管理端 `0.1.15` 提供可选的桌面优先 Web 页面。它与完整 CLI 调用相同的
项目、扫描、发布和实例服务，不需要 Node、数据库服务或额外 Web 框架。

进入主菜单后选择：

```text
[10] 启动 Web 管理界面
```

默认地址是：

```text
http://127.0.0.1:18080/
```

页面可完成项目创建与设置、源文件上传/导入/移除、文件维护方式、清理多余文件、可选内容、
统一的问题文件处理、检查和发布、历史与回滚、玩家端程序发布、薄首次部署包生成、完整玩家实例制作，以及
公共 HTTP 服务启停。加密备份和恢复属于高风险运维操作，仍只在 CLI 中提供。

Web 管理端有以下安全边界：

- 默认监听 `127.0.0.1`；非回环监听需要先在本机或 SSH 隧道中注册管理账户；
- 管理端口默认 `18080`，与玩家下载端口 `8080` 完全独立；
- 写操作需要当前进程随机生成的临时会话令牌；
- 不提供 CORS，不允许被其它网页嵌入；
- 推荐使用 SSH 隧道；远程 Web 登录需要外部 HTTPS 反向代理，不能直接暴露未加密的管理登录。

管理端就在当前电脑时，直接用浏览器访问上述地址。管理端位于远程 Linux 或
Windows 服务器时，在自己的电脑建立 SSH 隧道：

```powershell
ssh -N -L 18080:127.0.0.1:18080 用户名@您的服务器地址
```

SSH 使用非默认端口时添加：

```powershell
ssh -N -p SSH端口 -L 18080:127.0.0.1:18080 用户名@您的服务器地址
```

保持 SSH 终端运行，并在自己电脑打开 `http://127.0.0.1:18080/`。隧道只把
自己的本地端口转到服务器回环地址，不会把管理页面公开给其他人。远程 Windows
未安装 OpenSSH Server 时，可以继续使用交互 CLI，或先按操作系统文档配置 SSH。

Web 页面没有定时轮询；不操作时不会持续查询数据库。固定请求线程也是守护
线程，因此页面空闲时的 CPU 占用接近零。

在管理端终端按 `Ctrl+C` 会停止 Web 管理服务并返回主菜单，不会退出整个
管理端。如果公共 HTTP 文件服务是从该 Web 页面启动的，它也会随 Web 服务一起
停止；准备长期只运行玩家下载服务时，可返回主菜单后单独选择 `[9]`。
Windows 版使用原生控制台控制处理器，不需要先执行 `chcp` 或修改快捷方式。

### 3. 创建第一个项目

首次启动会继续询问：

```text
目前还没有项目，是否现在创建第一个项目？ [Y/n]：
```

直接按回车或输入 `Y`。

下面以建筑服为例，逐项填写：

#### 项目 ID

```text
项目 ID（小写字母、数字、点、下划线或连字符）：building_server
```

这是内部固定标识。只能使用小写字母、数字、点、下划线和连字符，不要使用中文或空格。

#### 项目显示名称

```text
项目显示名称：梦鱼建筑服
```

这是玩家界面显示的名称，可以使用中文。旧项目名称曾因终端编码保存为乱码时，
可在 Web“项目设置”的“项目显示名称”中直接改正，不需要重新创建项目。

如果中文输入法还处于选字状态，先确认文字已经输入到终端，再按回车；否则程序可能收到空行并提示“此项不能为空”。

#### 标准整合包目录

```text
标准整合包目录：C:\DreamingFishSource\building_server
```

目录不存在时，程序会询问是否创建：

```text
目录不存在，是否创建 C:\DreamingFishSource\building_server？ [Y/n]：
```

输入 `Y`。这个目录不是管理数据目录，也不是完整 Minecraft。它只需要放你希望管理的内容，例如：

```text
C:\DreamingFishSource\building_server\
    mods\
    config\
```

#### 文件维护方式与可选内容

创建项目时不需要设置同步策略：所有文件默认“普通同步”，跟随发布更新，玩家可以停用模组
或把个别文件改为自行管理。以后需要时，在 Web“管理内容”页面按文件或文件夹选择维护方式：

| 维护方式 | 适合 | 玩家改过本地文件时 |
| --- | --- | --- |
| 强制同步 | 核心模组、联机必须一致的配置 | 换回服主版本，原文件移入玩家备份；玩家不能停用 |
| 普通同步（默认） | 大多数模组和配置 | 换回服主版本，原文件移入玩家备份；玩家可停用或自行管理 |
| 首次提供 | `options.txt` 等初始文件或配置模板 | 已有保留，缺失提供一次；之后不覆盖、不补回，玩家可主动恢复默认 |

把文件夹设为强制同步，也会把其中未发布的额外文件移入玩家备份。例如强制同步 `mods/`，
其中不能设置普通同步、首次提供或可选内容；普通同步的 `config/` 中玩家生成的文件仍会保留。
强制目录在扫描和发布时必须真实存在；确实需要清空玩家目录时，应显式保留一个空文件夹再发布。
不要把存档、截图等玩家自己的目录设为强制同步。

光影、美化等不是每台电脑都带得动的内容，可以放进“可选内容”：服主填写名称、说明和是否
默认安装，玩家在更新器里整组开关。模组按 modid 加入，以后改名更新仍然属于同一组。

不用 Web 时，可以在管理端根目录用参数式命令完成同样的设置：

```powershell
.\DreamingFishAdmin.exe project policy show building_server
.\DreamingFishAdmin.exe project policy preset building_server config INITIAL --directory
.\DreamingFishAdmin.exe project policy preset building_server mods/core.jar REQUIRED
.\DreamingFishAdmin.exe project policy group building_server --title 光影与美化 --default-off
.\DreamingFishAdmin.exe project policy group-members building_server <分组ID> --add mods/iris.jar
```

需要整个模组目录保持一致时，使用 `project policy preset building_server mods REQUIRED --directory`。
该目录不能同时提供玩家可选的模组。旧参数 `--force-sync-directories`、`--force-sync-files` 和
`project policy cleanup` 仍作为强制同步的兼容入口。所有修改都要发布新版本后才会到达玩家。

#### 服务器出问题时：处理问题文件

- **移出问题版本**：选择历史或当前发布中的坏版本，玩家那里匹配的副本会在下次更新时移入备份，
  即使玩家停用、自行管理或改了文件名。管理端仍在提供同一坏文件时，会先归档源副本，准备同一次发布。
- **换成正确文件**：先将正确文件放入标准目录，再选择用它替换问题版本。按内容指纹或模组 ID 与
  版本识别坏副本；被替换的文件先进入玩家备份。每位玩家各覆盖一次位于“更多替换选项”内。

两者在 Web“管理内容 → 问题处理 → 处理问题文件”中操作，或使用 `project policy history`、
`project policy withdraw` 与 `project policy correct` 命令；它们会随之后的每个发布持续生效，
直到服主撤销。

#### 玩家访问的公共 HTTP 地址

```text
玩家访问的公共 HTTP 地址：http://你的公网IP:8080
```

例如公网 IP 是 `203.0.113.20`：

```text
http://203.0.113.20:8080
```

有域名时也可以填写：

```text
http://update.example.com:8080
```

这个地址必须能从玩家电脑访问。

以下地址不能放进正式玩家包：

```text
http://127.0.0.1:8080   只代表玩家自己的电脑
http://localhost:8080   只代表玩家自己的电脑
http://0.0.0.0:8080     这是监听地址，不是访问地址
```

#### 界面信息

推荐填写：

```text
副标题：准备好后，一起进入游戏。
Minecraft 服务器地址：你的游戏服务器地址
主强调色 [#2ee8df]：直接回车
次强调色 [#b06cff]：直接回车
电脑端封面图片路径：封面在服务器上的绝对路径，暂时没有可以直接回车
```

项目创建完成后会生成独立签名身份。私钥位于管理端 `data/` 中，不会出现在玩家包里。

### 4. 准备标准整合包内容

把需要由更新器管理的文件上传到标准整合包目录：

```text
C:\DreamingFishSource\building_server\
    mods\
        mod-a.jar
        mod-b.jar
    config\
        mod-a.toml
        mod-b\settings.json
```

不需要上传完整 `.minecraft`，也不需要上传 `assets/`、`libraries/`、存档、日志或 PCL。

也可以在 Web“管理内容”页面的“整合包文件”区域完成这些操作：

- 从当前浏览器拖入或多选文件上传到指定相对目录；整批文件上传完只检查一次；
- 输入路径或用 `…` 打开管理服务器本机文件选择器，从 VPS 文件系统导入；
- 查看全部实际托管文件、模组名称与版本、大小，以及每个文件和文件夹的维护方式；
- 移除文件时，选择“移除玩家副本”或“停止维护，留给玩家”；可选内容在文件列表中另外设置。

覆盖和移除前，旧文件会先经过哈希校验并归档到：

```text
C:\DreamingFishAdmin\data\source-archive\<项目ID>\<时间批次>\<原相对路径>
```

这个归档只保护管理端源文件误操作，不代替完整的 `data/` 和标准源目录备份。

文件规则如下：

- 标准目录里存在的普通文件会进入发布清单。
- 每个文件按维护方式处理，未单独设置的文件为“普通同步”，玩家改动后会被恢复成发布版本，改过的原文件先移入玩家备份。
- 未开启“清理多余文件”的目录中，玩家额外添加且路径不在发布清单中的模组不会删除，只会在玩家端显示提醒。
- 开启“清理多余文件”的一级目录会递归收敛到发布清单，多出的所有文件类型都会移入玩家备份。
- 与发布中某个模组 modid 相同的另一个 JAR 会被移入备份，避免同一模组两份导致游戏崩溃。
- 已经发布过的文件从标准目录移除后，下一次检查会把它列为移除项，并要求服主选择处理方式。
- 更新器、Agent、日志、存档、截图和崩溃报告默认排除。

### 5. 发布玩家端更新器程序

制作首个玩家包之前，必须先发布一次玩家端更新器程序。

把下面的玩家端压缩包上传并解压到管理服务器的临时目录：

```text
dreamingfish-player-windows-x64-<版本号>.zip
```

解压后的关键结构为：

```text
dreamingfish-player-windows-x64\
    .dreamingfish-bootstrap\
    DreamingFishUpdater\
        app\
            0.1.14\
                DreamingFishUpdater.exe
                app\
                runtime\
        state\
```

重新运行 `DreamingFishAdmin.exe`，在主菜单选择：

```text
[6] 发布玩家端程序
```

当前玩家端 `0.1.14` 示例应填写：

| 终端问题 | 填写内容 |
| --- | --- |
| 平台 | `windows-x64` |
| 玩家端发行包解压根目录 | `...\dreamingfish-player-windows-x64` |
| 最低启动引导器版本 | `0.1.2` |

管理端会从 `DreamingFishUpdater/state/active-player.properties`、语义版本目录或
jpackage 元数据读取版本，并自动定位 app-image 与唯一启动 EXE。可以选择 ZIP
解压后的最外层目录，不再要求手填版本号和启动路径。

最后输入 `Y` 确认发布。

### 6. 发布第一个整合包版本

回到主菜单选择：

```text
[4] 扫描并发布整合包
```

管理端会显示新增、修改和删除文件。第一次发布时，应看到标准目录中的 `mods/` 和 `config/` 文件都属于新增。

后续检查出现移除文件时，可以逐项选择或批量设置：

- `DELETE` / 移除玩家副本（默认）：所有匹配副本先备份再移出，包括豁免、停用和首次提供副本；
  要求随之后的每个完整目标持续生效，玩家装回后仍会处理。
- `RELEASE` / 停止维护，留给玩家：停止下发和日常管理，保留玩家副本。

强制目录内只能移除副本；要留给玩家，请先将目录改为普通同步。参数式发布可用
`--removed-files DELETE` 或 `RELEASE` 统一设置本次所有移除项，覆盖预览里的默认决定。
重新提供同一资源会结束普通持续移除，历史问题版本仍需明确结束处理。停止维护的历史路径会
持续写入后续发布，旧整合包直接跨版本更新时也会保留。

仔细检查预览后填写：

| 终端问题 | 首次发布示例 |
| --- | --- |
| 本次显示版本 | `1.0.0` |
| 最低玩家端程序版本 | `0.2.0`（填写更低版本会被自动提高） |
| 更新记录 | `建筑服首次发布` |
| 确认不可变版本 | `Y` |

发布成功后，管理端 `data/` 会保存签名清单和文件对象。不要手动修改其中内容。
如果公共 HTTP 服务由当前 Web 管理进程启动，Web 发布和回滚成功后会自动重启
下载服务；重启失败不会回滚已经创建的不可变发布，页面会单独显示服务警告。

确认发布前，管理端会回显数据库实际收到的更新记录。如果中文已经变成
`P`、`0` 或乱码，应选择 `N` 取消。可以把内容保存为 UTF-8 文本文件，
在交互提示中输入 `@D:\更新记录.txt`；参数式命令则可使用
`--changelog-file "D:\更新记录.txt"`。两种方式都支持多行内容。

### 7. 启动 HTTP 文件服务

主菜单选择：

```text
[9] 启动 HTTP 文件服务
```

确认后终端会保持运行。这个窗口关闭后，玩家就无法在线检查和下载更新。
按 `Ctrl+C` 会停止 HTTP 文件服务并返回主菜单，不再退出整个管理端。

也可以在 Web 管理首页点击“启动下载服务”。Web 页面与公共下载服务使用独立
端口，浏览器关闭不会停止服务；管理端进程被停止时服务才会结束。

也可以在管理端根目录直接运行：

```powershell
.\DreamingFishAdmin.exe serve --host 0.0.0.0 --port 8080
```

先在管理服务器上访问：

```text
http://127.0.0.1:8080/healthz
```

正常结果是：

```json
{"status":"ok"}
```

然后必须从另一台电脑访问：

```text
http://你的公网IP:8080/healthz
```

如果服务器本机能打开、其它电脑打不开，需要检查：

- 云服务器安全组是否放行 TCP 8080；
- Windows 防火墙是否放行 TCP 8080；
- 使用家庭网络时是否完成路由器端口转发；
- 项目公共 HTTP 地址中的 IP、域名和端口是否正确。

### 8. Linux 管理端差异

Linux 包解压后先运行：

```bash
chmod +x dfs-admin runtime/bin/java
./dfs-admin
```

Linux 启动脚本会自动加入：

```text
-Djava.net.preferIPv4Stack=true
```

这会确保公共 HTTP 服务监听 IPv4 的 `0.0.0.0:8080`，无需手动设置
`JAVA_TOOL_OPTIONS`。它只影响 Linux 管理端发行包。

管理数据同样自动位于管理端根目录的：

```text
data/
```

HTTP 服务命令为：

```bash
./dfs-admin serve --host 0.0.0.0 --port 8080
```

其它项目创建、发布和玩家实例制作流程与 Windows 相同。

## 三、配置玩家端

### 1. 准备开启版本隔离的 Minecraft 实例

玩家端必须安装到具体整合包的版本隔离实例中。PCL 下通常类似：

```text
.minecraft\versions\Dreamingfish-Building\
```

打开该目录后，应该直接看到此整合包自己的 `mods/`、`config/` 等文件夹。

不要把更新器放到整个 `.minecraft` 根目录，也不要放到 PCL 程序目录，除非那个目录本身就是此实例根目录。

### 2. 把玩家端模板放进实例

将玩家端压缩包中的所有内容复制到实例根目录：

```text
dreamingfish-player-windows-x64-<版本号>.zip
```

复制完成后，实例中至少应存在：

```text
<实例>\.dreamingfish-bootstrap\bootstrap-agent.jar
<实例>\DreamingFishUpdater\state\active-player.properties
<实例>\DreamingFishUpdater\app\0.1.14\DreamingFishUpdater.exe
```

`.dreamingfish-bootstrap` 是隐藏目录，复制时不能漏掉。

此时还不能直接分发，因为包中只有示例绑定，必须让管理端生成真实项目绑定。

### 3. 让管理端制作玩家实例

#### 管理端能直接访问实例目录

在管理端主菜单选择：

```text
[7] 制作玩家实例
```

填写：

| 终端问题 | 填写内容 |
| --- | --- |
| Minecraft 版本隔离实例目录 | 玩家模板所在的实例根目录 |
| 平台 | `windows-x64` |
| 实例内玩家端目录 | 直接回车，保留 `DreamingFishUpdater` |
| 下载包对应的不可变发布版本 | 从列表中选择本次准备分发的版本，通常选最新版本 |
| 确认写入绑定 | `Y` |

管理端会自动：

1. 生成 `.dreamingfish-bootstrap/project-binding.json`；
2. 写入项目公共地址和公钥；
3. 验证首个玩家程序与已发布版本完全一致；
4. 验证实例中已有托管文件确实属于所选发布，选错版本时拒绝继续；
5. 从管理端对象库补齐实例中缺少的所选发布文件；
6. 写入 `.dreamingfish-bootstrap/bundled-release/` 签名分发基线；
7. 清理测试运行状态，同时保留玩家程序签名状态；
8. 项目有封面时复制封面。

不要手动使用 `project-binding.example.json`，也不要只生成一个绑定 JSON。

#### 管理端在远程服务器，完整整合包在本地

使用 Web“玩家实例”页上方的“生成首次部署包”，或主菜单：

```text
[11] 生成玩家端首次部署包
```

选择服务器上的输出父目录、`windows-x64`，以及本地完整整合包实际对应的历史
发布。管理端会从 `data/` 中的已发布对象重建：

1. 完整签名玩家端程序和活动版本状态；
2. `bootstrap-agent.jar`；
3. 真实项目绑定与项目封面；
4. 所选历史发布的签名分发基线；
5. PCL 所需 JVM 参数文本和中文说明。

输出是薄部署目录，不包含 `mods/`、`config/` 或 Minecraft 本体。下载整个生成
目录并合并到本地完整实例根目录，不能只取 `project-binding.json`。基线必须选择
本地整合包内容实际对应的版本；选最新版本不代表一定正确。

如果管理端在远程服务器，而你希望直接在当前电脑保存部署包，可点击同一页面的
“生成并下载到本机”。管理端会在自己的临时目录生成 ZIP 并通过浏览器传输，下载
完成或中断后都会清理临时文件；下载后解压并将其中全部内容合并到本地实例根目录。

远程 Linux 管理端同样可以生成 Windows 玩家部署包。它只校验和重建文件，不会
运行 `DreamingFishUpdater.exe`。

### 4. 配置 PCL

打开 PCL 中这个具体 Minecraft 版本的设置，找到该版本的 JVM 参数或 Java 虚拟机参数输入框，加入一条：

```text
-javaagent:"{verpath}.dreamingfish-bootstrap/bootstrap-agent.jar"
```

必须原样保留 `{verpath}`。它会在每位玩家电脑上自动解析为当前版本隔离实例，因此整合包移动磁盘或安装到不同目录后仍然可用。

注意：

- 不要改成服主电脑上的绝对路径；
- 不要添加两次；
- 不要放进 PCL 的“启动前执行命令”；
- 不要移动 `bootstrap-agent.jar`；
- 不需要设置额外的 PCL 启动脚本；
- 不要让玩家直接双击 `DreamingFishUpdater.exe`。

玩家平时仍然点击 PCL 的“启动游戏”。Agent 会在 Minecraft 真正启动前自动打开更新器。

### 5. 完整测试一次

在分发整合包前，用 PCL 启动测试实例。正常顺序是：

1. PCL 开始启动 Minecraft；
2. DreamingFish Updater 自动弹出；
3. 更新器检查自身程序版本；
4. 更新器检查并安装 `mods/config` 更新；
5. 验证完成后立刻允许 Minecraft 继续启动；
6. 更新器顶部提示 Minecraft 已开始启动；
7. 更新器在 15 秒后自动关闭；
8. 打开“更新记录”“运行记录”或“本地文件”后，窗口会保持打开。

玩家端不会询问安装位置。它直接使用实例内 `.dreamingfish-bootstrap/project-binding.json` 的 `playerHome`；推荐值是相对路径 `DreamingFishUpdater`，因此整合包移动或交给其它玩家后仍然有效。

### 6. 分发给玩家

测试通过后，使用 PCL 的整合包导出功能，或压缩完整版本隔离实例进行分发。

最终玩家实例必须包含：

```text
.dreamingfish-bootstrap\
    bootstrap-agent.jar
    project-binding.json
    project-cover              项目有封面时存在
    bundled-release\
        manifest.json
        manifest.sig

DreamingFishUpdater\
    app\
    state\
```

不要包含：

```text
管理端程序
管理端 data 目录
management-settings.json
项目私钥
管理端备份
标准整合包源目录
```

## 四、以后如何发布更新

### 更新模组或配置

1. 修改管理服务器上的 `C:\DreamingFishSource\building_server\mods` 或 `config`。
2. 启动管理端。
3. 选择 `[4] 扫描并发布整合包`。
4. 仔细检查新增、修改、删除列表以及维护规则变化。
5. 输入新的整合包显示版本和更新记录。
6. 确认发布。
7. 保持 HTTP 服务运行。

玩家下次从 PCL 启动时会自动更新，不需要重新下载整个整合包。

发布确认绑定扫描预览标识及摘要。修改项目配置、重新扫描或改变删除决定后，旧窗口中的确认会被拒绝；刷新后重新检查。升级后的管理端需要重新生成旧未发布预览，不修改历史签名发布。

每个正式下载包都应重新生成一次首次部署包，或直接执行菜单 `[7]` 制作实例，
并选择该下载包实际对应的发布版本。玩家无论拿到 1.1、1.2 还是其它旧正式包，
首次启动都会用签名基线识别旧托管文件，并直接与当前最新完整清单比较，不需要
逐版本更新。

### 更新玩家端程序

只有更新器界面或程序本身升级时才需要：

1. 准备新版本玩家端发行包。
2. 选择管理端菜单 `[6] 发布玩家端程序`。
3. 选择新 ZIP 解压后的根目录；版本和启动程序由管理端自动读取。
4. 确认最低 Bootstrap 版本。
5. 先发布玩家端程序，再发布任何要求该版本的整合包内容。

玩家端会在下一次启动时自动下载、验证并切换到更高版本。

本次简化规则包含在玩家程序 0.2.0 中，请先发布新版玩家端程序再发布整合包。必须以新版本发布，不能替换既有版本的签名内容，否则会触发重放保护。静态托管重新导出并完整上传新增的 `latest.signed`、`presentation.signed` 及各平台 `latest.signed`；它们包含原始载荷与签名，不能长期缓存。旧目录没有 `.signed` 时应返回真实 404，才会兼容读取 `.sig`；不能把缺失文件改写为 200 的 HTML 错误页面。

`bootstrap-agent.jar` 不会在线自更新。未来如果新玩家端必须使用新版 Agent，需要重新制作并分发玩家实例。

## 五、玩家文件和日志

普通同步目录中，玩家额外添加、且不匹配有效移除或问题要求的模组会保留并只显示提醒。强制目录中的额外文件、持续移除的资源和已知问题版本会先备份再移出；无法完成必要处理时不会授予启动许可。

玩家打开“本地文件”后有三个页面：

- **可选内容**：服主设置了可选内容时显示，玩家按电脑配置整组开关；没有做过选择时跟随服主的默认安装设置，选择写入 `state\local-option-preferences.json`。
- **文件管理范围**：普通同步允许把文件或目录改为自行管理；已经修改的不会覆盖，已经删除的不会重新下载。模组按 modid 记录，改名更新后仍然有效。首次提供或旧发布中的默认配置可以点“恢复默认”，下次检查时取得服主版本。明确的持续移除与问题处理仍会执行。选择写入 `state\local-file-preferences.json`。
- **模组启停**：搜索并停用某个整合包模组或玩家自选模组，选择写入 `state\local-mod-preferences.json`；停用的 JAR 会在下一次发放 Minecraft 启动许可前移到 `local-mods\disabled\`。

服主设为“强制同步”的文件和模组不能停用或自行管理，界面会说明原因；属于可选内容的模组由分组统一开关，单个模组的停用选择仍然优先于分组。停用必要依赖可能导致 Forge 启动失败或无法连接服务器，界面会在操作前提示。所有修改都只保存在本机，不会上传到管理端。

文件移动和偏好索引使用持久化本地事务；失败或崩溃后会恢复。已被服主停止维护的停用副本归玩家所有，重新启用时恢复或保留，不会作为旧官方缓存清理。若恢复位置已有另一个文件，会保留存储副本，不覆盖它。被服主撤回的版本即使在本地存储中，也不会再被放回游戏。

服主选择“停止维护，玩家保留”的文件不会再参与同步，玩家端会显示“服主已停止管理文件，本地副本已保留”，并在本次更新详情中列出路径。服主删除文件时，自行管理的副本会移入玩家备份，除非服主选择“删除，但保留自行管理玩家的副本”。

更新器不会直接删除玩家的文件：改过的文件被换回服主版本、清理出的多余文件、重复的模组、被撤回或修正替换的版本，以及玩家请求恢复默认的配置，都会先移入：

```text
<玩家端安装目录>\backups\archive\<时间_发布ID_事务ID>\
```

每次更新创建独立目录并写入 `archive-index.json`（原因、版本）和 `archived-files.txt`，更新器不会自动清理。玩家可以在更新器的“备份与恢复”中查看每个文件被移走的原因，把文件放回游戏（会先检查下次更新是否又会移走它）、打开或删除备份。0.2.0 之前的 `backups\forced-sync\` 旧备份也会一起显示。备份创建、移动或事务提交任一步失败时，更新会回滚并暂停 Minecraft 启动。

默认玩家日志位于：

```text
<实例>\DreamingFishUpdater\logs\player-updater.log
```

界面的“运行记录”直接读取这份 UTF-8 日志。“更新记录”通过管理端只读历史接口展示全部发布，并在本地缓存；旧管理端没有历史接口或当前断网时，至少仍会显示当前签名发布和已有缓存。

当前玩家端在联网之前读取签名安装记录或下载包基线；缺少基线会拒绝启动。更新服务器不可达且本地内容有效时，可以取得已验证离线许可；已有可信基线但托管内容变化时，可以由玩家明确选择保留变化继续启动，本次不记为验证通过。签名、清单、重放、危险路径和未完成事务错误仍然阻断。同一实例已有 Minecraft 运行时，当前桌面主流程要求先关闭游戏。

## 六、管理端备份

管理端根目录的 `data/` 包含项目私钥和全部发布数据，必须备份。不要只备份标准整合包目录。

Windows PowerShell：

```powershell
cd C:\DreamingFishAdmin
$env:DFS_BACKUP_PASSWORD = "使用长且唯一的备份密码"
.\DreamingFishAdmin.exe backup create --output "D:\Backups\dreamingfish-backup.dfsb"
Remove-Item Env:DFS_BACKUP_PASSWORD
```

标准整合包目录也要单独备份，因为加密管理端备份不包含原始标准源目录。

这个加密归档完整覆盖发布数据，包含数据库内的项目配置、项目私钥、整合包与玩家程序签名清单和内容对象。它不包含管理端目录旁的 `management-settings.json` 或 `management-web-auth.json`，迁移完整实例时需另行保存这些运行设置和 Web 账户。恢复替换和旧数据回退均失败时，工具保留原数据及已验证恢复目录，并在错误中显示位置；请保留它们处理恢复。

升级管理端时，不要删除以下内容：

```text
C:\DreamingFishAdmin\data\
C:\DreamingFishAdmin\management-settings.json
```

升级到 `0.1.15` 时：

1. 先停止 HTTP/Web 服务并退出旧管理端；
2. 备份上面的 `data/`、`management-settings.json` 和 `management-web-auth.json`；
3. 把新管理端 ZIP 解压到临时目录；
4. 用新包中的 `DreamingFishAdmin.exe`、`app/`、`runtime/`、`support/` 和文档覆盖旧程序文件，并删除不再使用的 `dfs-admin.cmd`；
5. 保留原有 `data/` 与 `management-settings.json`，重新启动。

旧 schema 1 设置会自动迁移为 schema 2，并使用默认 Web 管理端口 `18080`。
不需要重新创建项目、重新发布整合包或重新发布玩家端程序。

如果已经把旧 `data/` 和设置文件拖到新的管理端根目录，但设置仍保存旧版本目录
的绝对路径，只要新目录中的 `data/management.db` 有效，`0.1.14` 及更高版本会优先使用这个
本地 `data/` 并自动改写设置。只有空 `data/` 时不会抢占原配置，避免误用空库。

## 七、常见错误

### 玩家提示 Connection refused

最常见原因是项目公共地址写成了 `127.0.0.1`，或 HTTP 服务没有运行。先从玩家电脑访问：

```text
http://你的公网IP:8080/healthz
```

### PCL 提示无法加载 Java Agent

确认实例中存在：

```text
.dreamingfish-bootstrap\bootstrap-agent.jar
```

并确认 JVM 参数为：

```text
-javaagent:"{verpath}.dreamingfish-bootstrap/bootstrap-agent.jar"
```

### 制作玩家实例时提示玩家程序未发布或不一致

检查：

- 是否先执行了菜单 `[6] 发布玩家端程序`；
- 所选根目录是否包含 `DreamingFishUpdater\state\active-player.properties`，或可识别的语义版本 app-image；
- app-image 中是否存在 `DreamingFishUpdater.exe`、`app/` 和 `runtime/`；
- 玩家端模板是否被手动修改过。

必要时重新解压一份干净的玩家端模板。

### 发布预览出现大量删除

先取消发布。检查标准整合包目录是否选错、文件是否尚未上传完成，或之前发布过的文件是否被误删。只有确认删除列表完全正确后才能发布。

### 提示缺少签名分发基线

说明玩家实例没有经过新版菜单 `[7]` 正式制作，或打包时漏掉了 `.dreamingfish-bootstrap/bundled-release/`。不要手工伪造清单；重新解压干净玩家端模板，在管理端选择该下载包对应发布并重新制作实例。

### 开启清理的目录缺失，无法扫描或发布

该目录已设为强制同步，但标准整合包目录里没有对应文件夹。检查是否选错标准目录；若确实希望发布空目录，请创建同名空文件夹后重新扫描。

## 八、正式分发检查表

- 管理端自动生成了根目录下的 `data/`。
- 标准整合包目录与管理端 `data/` 分开，并包含正确的 `mods/`、`config/`。
- 项目公共地址使用玩家可访问的公网 IP 或域名。
- 从另一台电脑访问 `/healthz` 成功。
- 已发布与模板版本一致的玩家端程序。
- 已发布至少一个整合包版本。
- 已生成薄首次部署包并合并到本地实例，或通过菜单 `[7]` 制作真实玩家实例。
- 生成部署包或制作实例时选择的发布版本与这个下载包的实际内容一致。
- 玩家实例中同时存在 `.dreamingfish-bootstrap/` 和 `DreamingFishUpdater/`。
- 玩家实例中存在 `.dreamingfish-bootstrap/bundled-release/manifest.json` 和 `manifest.sig`。
- PCL 使用 `{verpath}` JVM 参数，而不是绝对路径。
- 已完整测试更新、Minecraft 放行和 15 秒自动关闭。
- 玩家包中没有管理端 `data/`、私钥或管理端备份。

## 0.2.0 简化规则升级

新管理界面使用普通同步、强制同步和首次提供，移除默认持续生效并先备份。旧签名发布保持原样；新发布需要至少 0.2.0 玩家端，不支持新能力的程序必须升级后才能接受目标。

升级管理端后先检查旧项目预览，再确认发布；迁移生成的持续移除仍属于待发布内容，可以结束处理。不要使用旧管理端程序修改采用新规则的数据。玩家本地启停事务与偏好也有新格式，程序回退遇到不支持的恢复格式会明确阻止启动，不会忽略未完成操作。

移除要求会处理活动与停用副本；备份索引与本地偏好一起提交或回滚。已知移除不能通过离线保留变更绕过；无法获取尚未接受的新版本时，只能依据最后已知规则。
