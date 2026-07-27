# DreamingFish 管理端与玩家端完整配置参考

本文从一台全新的服务器开始，完整说明如何配置管理端、发布整合包、制作玩家端并接入 PCL。

只想先把系统跑起来，请优先阅读 [最简使用教程](./QUICKSTART.md)。本文保留远程中转、参数式命令、备份和故障处理等完整细节，第一次部署不需要从头读完。

主流程以 Windows 管理服务器、Windows 玩家和 PCL 为例。Linux 管理端的差异会在对应步骤中单独说明。

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
    dfs-admin.cmd
    app\
    runtime\
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
.\dfs-admin.cmd
```

也可以直接双击 `dfs-admin.cmd`。

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

这是玩家界面显示的名称，可以使用中文。

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

#### 强制同步一级目录

创建项目时会询问：

```text
强制同步一级目录（逗号分隔，留空不启用，例如 mods）：
```

普通项目直接回车即可。需要让玩家 `mods/` 与管理端完全一致时填写：

```text
mods
```

也可以填写多个一级目录：

```text
mods,resourcepacks
```

强制同步按目录独立生效并递归处理所有文件类型。上例不会强制同步 `config/`，因此玩家在 `config/` 中额外生成的文件仍会保留。强制目录中的额外文件不会直接删除，而会移入玩家端长期备份。

配置的目录在扫描和发布时必须真实存在。若配置了 `mods`，但标准整合包目录中没有 `mods/`，管理端会拒绝扫描或发布。确实需要清空玩家 `mods/` 时，应显式保留一个空的 `mods/` 文件夹再发布。

不用交互菜单时，可以在管理端根目录执行：

```powershell
.\dfs-admin.cmd project configure building_server --force-sync-directories mods,resourcepacks
.\dfs-admin.cmd project configure building_server --clear-force-sync
```

第一条会完整替换强制同步目录列表，不是在现有列表后追加；第二条会关闭这个项目的全部目录级强制同步。修改设置后仍需扫描并发布一个新版本，玩家端才会收到变化。

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
副标题：灾变之后，仍有人在这里守望。
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

文件规则如下：

- 标准目录里存在的普通文件会进入发布清单。
- `mods/` 和 `config/` 默认属于强制托管，玩家改动后会被恢复成发布版本。
- 未启用强制同步的目录中，玩家额外添加且路径不在发布清单中的模组不会删除，只会在玩家端显示提醒。
- 启用强制同步的一级目录会递归收敛到发布清单，多出的所有文件类型都会移入玩家备份。
- 已经发布过的强制托管文件从标准目录移除后，下一次发布会把它列为删除项。
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
            0.1.11\
                DreamingFishUpdater.exe
                app\
                runtime\
        state\
```

重新运行 `dfs-admin.cmd`，在主菜单选择：

```text
[6] 发布玩家端程序
```

当前玩家端 `0.1.11` 示例应填写：

| 终端问题 | 填写内容 |
| --- | --- |
| 平台 | `windows-x64` |
| 玩家端程序版本 | `0.1.11` |
| 玩家端完整 app-image 目录 | `...\DreamingFishUpdater\app\0.1.11` |
| 该目录内的启动程序路径 | `DreamingFishUpdater.exe` |
| 最低启动引导器版本 | `0.1.2` |

必须选择直接包含 `DreamingFishUpdater.exe`、`app/` 和 `runtime/` 的 `0.1.11` 目录，不要选择外层的 `DreamingFishUpdater`。

最后输入 `Y` 确认发布。

### 6. 发布第一个整合包版本

回到主菜单选择：

```text
[4] 扫描并发布整合包
```

管理端会显示新增、修改和删除文件。第一次发布时，应看到标准目录中的 `mods/` 和 `config/` 文件都属于新增。

仔细检查预览后填写：

| 终端问题 | 首次发布示例 |
| --- | --- |
| 本次显示版本 | `1.0.0` |
| 最低玩家端程序版本 | `0.1.11` |
| 更新记录 | `建筑服首次发布` |
| 确认不可变版本 | `Y` |

发布成功后，管理端 `data/` 会保存签名清单和文件对象。不要手动修改其中内容。

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

也可以在管理端根目录直接运行：

```powershell
.\dfs-admin.cmd serve --host 0.0.0.0 --port 8080
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
<实例>\DreamingFishUpdater\app\0.1.11\DreamingFishUpdater.exe
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

远程管理服务器不需要存放完整 Minecraft。按下面操作：

1. 在远程服务器创建临时目录，例如 `C:\DreamingFishPlayerStaging`。
2. 只把玩家端压缩包完整解压到该目录；不需要预先上传完整 Minecraft。
3. 在管理端菜单 `[7]` 中把 `C:\DreamingFishPlayerStaging` 作为实例目录。
4. 管理端会从对象库把所选发布的 `mods/`、`config/` 等托管文件补入临时目录。
5. 制作成功后，下载整个临时目录，并合并到本地完整整合包实例。

不能只下载 `project-binding.json`。至少必须包含完整的 `.dreamingfish-bootstrap/`、`DreamingFishUpdater/`，以及管理端补入的全部托管目录；最不容易遗漏的做法是下载整个临时目录。

远程 Linux 管理端同样可以准备 Windows 玩家包。它只校验文件，不会运行 `DreamingFishUpdater.exe`。

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
4. 仔细检查新增、修改和删除列表。
5. 输入新的整合包显示版本和更新记录。
6. 确认发布。
7. 保持 HTTP 服务运行。

玩家下次从 PCL 启动时会自动更新，不需要重新下载整个整合包。

每个正式下载包都应重新执行一次菜单 `[7]`，并选择该下载包实际对应的发布版本。玩家无论拿到 1.1、1.2 还是其它旧正式包，首次启动都会用签名基线识别旧托管文件，并直接与当前最新完整清单比较，不需要逐版本更新。

### 更新玩家端程序

只有更新器界面或程序本身升级时才需要：

1. 准备新版本玩家端发行包。
2. 选择管理端菜单 `[6] 发布玩家端程序`。
3. 发布新包中的 `DreamingFishUpdater\app\<新版本>`。
4. 填写正确的语义版本和最低 Bootstrap 版本。
5. 先发布玩家端程序，再发布任何要求该版本的整合包内容。

玩家端会在下一次启动时自动下载、验证并切换到更高版本。

`bootstrap-agent.jar` 不会在线自更新。未来如果新玩家端必须使用新版 Agent，需要重新制作并分发玩家实例。

## 五、玩家文件和日志

没有启用强制同步的目录中，玩家额外添加、且没有出现在发布清单中的模组会保留并只显示提醒。当前版本没有模组黑名单，也不会因为这些额外模组而阻止启动。

玩家可以打开“本地文件”，在“管理范围”中让普通同步不再管理某个远程文件或整个目录。被豁免文件保持玩家本机状态：已经修改的不会覆盖，已经删除的不会重新下载。选择只写入本机：

```text
<实例>\DreamingFishUpdater\state\local-file-preferences.json
```

同一页面的“模组启停”可搜索并停用某个整合包模组或玩家自选模组，选择写入：

```text
<实例>\DreamingFishUpdater\state\local-mod-preferences.json
```

停用的 JAR 会在下一次发放 Minecraft 启动许可前移到：

```text
<实例>\DreamingFishUpdater\local-mods\disabled\
```

单文件、目录和模组豁免只对普通同步生效；管理端不需要为每个文件配置“可选”属性。新版管理端会自动从 Forge、NeoForge 或 Fabric JAR 提取稳定组件 ID，所以模组升级后即使文件名变化，玩家选择仍能延续。旧发布没有元数据时退回到精确路径匹配。

停用必要依赖可能导致 Forge 启动失败或无法连接服务器，界面会在操作前提示。“恢复管理默认”会清除文件与目录豁免；“恢复整合包默认”会重新启用模组。所有修改都不会上传到管理端。

管理端可在菜单 `[3] 修改项目设置` 中按一级目录配置强制同步，例如只填写 `mods`。设置变化必须发布一个新整合包版本后才会到达玩家端。强制同步优先级最高：目录及其子文件的玩家开关会禁用，已有本地豁免也会被忽略，缺失或修改的托管文件会恢复为远程版本。玩家界面会明确显示“远程管理端已对 mods/ 启用强制同步”、移出文件数量和备份位置，并提供“打开备份目录”按钮。

长期备份位于：

```text
<玩家端安装目录>\backups\forced-sync\<时间戳_发布ID>\
```

每次同步都会创建独立目录，不覆盖旧备份，更新器也不会自动清理。完整文件列表同时写入备份中的 `archived-files.txt` 和玩家运行日志。备份创建、移动或事务提交任一步失败时，更新会回滚并暂停 Minecraft 启动。

如果玩家文件与管理端强制托管文件使用完全相同的相对路径，该文件会被校验并恢复为管理端发布版本。

默认玩家日志位于：

```text
<实例>\DreamingFishUpdater\logs\player-updater.log
```

界面的“运行记录”直接读取这份 UTF-8 日志。“更新记录”通过管理端只读历史接口展示全部发布，并在本地缓存；旧管理端没有历史接口或当前断网时，至少仍会显示当前签名发布和已有缓存。

## 六、管理端备份

管理端根目录的 `data/` 包含项目私钥和全部发布数据，必须备份。不要只备份标准整合包目录。

Windows PowerShell：

```powershell
cd C:\DreamingFishAdmin
$env:DFS_BACKUP_PASSWORD = "使用长且唯一的备份密码"
.\dfs-admin.cmd backup create --output "D:\Backups\dreamingfish-backup.dfsb"
Remove-Item Env:DFS_BACKUP_PASSWORD
```

标准整合包目录也要单独备份，因为加密管理端备份不包含原始标准源目录。

升级管理端时，不要删除以下内容：

```text
C:\DreamingFishAdmin\data\
C:\DreamingFishAdmin\management-settings.json
```

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
- 发布的版本是否与 `DreamingFishUpdater\state\active-player.properties` 一致；
- 发布源是否选择了直接包含 EXE、`app/` 和 `runtime/` 的版本目录；
- 玩家端模板是否被手动修改过。

必要时重新解压一份干净的玩家端模板。

### 发布预览出现大量删除

先取消发布。检查标准整合包目录是否选错、文件是否尚未上传完成，或之前发布过的文件是否被误删。只有确认删除列表完全正确后才能发布。

### 提示缺少签名分发基线

说明玩家实例没有经过新版菜单 `[7]` 正式制作，或打包时漏掉了 `.dreamingfish-bootstrap/bundled-release/`。不要手工伪造清单；重新解压干净玩家端模板，在管理端选择该下载包对应发布并重新制作实例。

### 强制同步目录缺失，无法扫描或发布

项目设置中已启用该目录，但标准整合包目录里没有对应文件夹。检查是否选错标准目录；若确实希望发布空目录，请创建同名空文件夹后重新扫描。

## 八、正式分发检查表

- 管理端自动生成了根目录下的 `data/`。
- 标准整合包目录与管理端 `data/` 分开，并包含正确的 `mods/`、`config/`。
- 项目公共地址使用玩家可访问的公网 IP 或域名。
- 从另一台电脑访问 `/healthz` 成功。
- 已发布与模板版本一致的玩家端程序。
- 已发布至少一个整合包版本。
- 已通过菜单 `[7]` 制作真实玩家实例。
- 菜单 `[7]` 选择的发布版本与这个下载包的实际内容一致。
- 玩家实例中同时存在 `.dreamingfish-bootstrap/` 和 `DreamingFishUpdater/`。
- 玩家实例中存在 `.dreamingfish-bootstrap/bundled-release/manifest.json` 和 `manifest.sig`。
- PCL 使用 `{verpath}` JVM 参数，而不是绝对路径。
- 已完整测试更新、Minecraft 放行和 15 秒自动关闭。
- 玩家包中没有管理端 `data/`、私钥或管理端备份。
