# 梦鱼更新系统架构与代码审查

2026 年 10 月 2 日状态：F1–F8 已落实代码修复与回归测试，详见[修复记录](D:/Desktop/DreamingFish-Updater/docs/reviews/2026-10-02-priority-fixes.md)。本文保留 10 月 1 日的审查证据；以下“当前实现”和行号均指当时快照，不能当作修复后仍存在的缺陷。历史文件撤回仍是后续功能草案。

本次审查针对 2026 年 10 月 1 日工作区中的实际代码，包括尚未提交的修改。结论是：现有模块划分和可信发布、事务安装的基础值得保留；当前最需要重构的是文件所有权、玩家偏好、历史文件处置之间的决策模型。功能分支分散已经导致可复现的数据保留错误。

审查基于提交 `133d558ad73aa3cb18d0ed0384678932abe3c475` 加当时的工作区修改。生产源码统计为 203 个文件、29,868 行，范围包括六个 Java 模块、Vue 玩家界面和 Rust 桌面桥接。本次新增审查文档、领域术语和隔离复现探针，没有修改生产业务实现。

配套文件：[策略重构草案](D:/Desktop/DreamingFish-Updater/docs/reviews/2026-10-01-policy-refactor-proposal.md)、[复现说明](D:/Desktop/DreamingFish-Updater/docs/reviews/repro/README.md)、[执行脚本](D:/Desktop/DreamingFish-Updater/docs/reviews/repro/run-probes.ps1)。

## 服主提出的新场景

场景是：服主已经把某个文件放弃管理并发布，过几天发现这个文件会造成严重问题，需要从玩家实例中移除。

**当前实现缺少这个操作。** 放弃管理后，文件不再进入后续的删除预览；`releasedPaths` 继续保留所有权释放声明，而玩家的安装记录也不再包含这个文件。不能通过一次普通的“从清单移除”发布来撤回它。探针得到 `previewChanges=0 deleteDecisionAccepted=false`。

这属于当前领域模型的能力缺口，不应直接归为违反现有 V1 要求的代码缺陷。建议增加独立的“撤回历史文件”：选择曾发布的内容、声明原因和处理要求，把撤回规则放进此后所有完整目标状态。它需要覆盖活动目录和停用存储区，也需要保护玩家自行替换的同名文件。详细设计见重构草案。

## 优先处理的问题

P1 表示涉及数据丢失或已声明的文件边界失效，应优先修复；P2 表示特定条件下行为错误、发布不一致或更新卡住。以下区分动态复现与静态确认，不能把普通测试通过理解为这些场景也通过。

| 编号 | 优先级 | 问题 | 确认方式 |
| --- | --- | --- | --- |
| F1 | P1 | Windows 目录联接绕过实例路径边界 | 动态复现 |
| F2 | P1 | 已释放的停用模组重新启用时丢失全部副本 | 动态复现 |
| F3 | P1 | 回滚丢失累计释放路径，旧玩家副本被删除 | 动态复现 |
| F4 | P1 | 恢复失败后的清理可能删除唯一旧数据副本 | 静态控制流确认 |
| F5 | P2 | 本地模组移动失败后留下无索引副本和部分移动状态 | 动态故障注入 |
| F6 | P2 | 发布未绑定预览策略，旧预览可发布新的强制规则 | 动态复现 |
| F7 | P2 | 静态托管的可变清单与签名分开切换 | 动态模拟跨切换读取 |
| F8 | P2 | 流式响应体没有有效的读取超时和主动取消 | 动态复现 |

### F1 Windows 目录联接绕过路径检查

位置：[PathSafety.java](D:/Desktop/DreamingFish-Updater/protocol/src/main/java/cn/dreamingfish/updater/protocol/PathSafety.java:85)。

`resolveInside` 先检查规范化后的路径前缀，再逐段调用 `Files.isSymbolicLink`。Windows junction 是 reparse point，本次 Java 环境中 `isSymbolicLink` 返回 false。令 `instance/mods` 指向同级的测试目录后，`resolveInside(instance, "mods/probe-marker.txt")` 被接受，写入实际发生在实例外。

探针结果：`javaIsSymbolicLink=false acceptedByPathSafety=true realPathOutsideInstance=true`。

这会影响安装、移除、归档和还原等依赖这个公共函数的操作。它也可能使实例内的目录别名指向更新器自身的数据目录。此处的风险是本地文件操作越界，不代表已经证明公网攻击者可以任意选择本机路径。

建议对现有根目录及每段现有路径检查实际位置和 Windows 重解析点；对尚不存在的目标验证最近的现有父目录。创建父目录后、实际文件操作前再次检查。仅给末端文件加 `NOFOLLOW_LINKS` 不能解决父目录联接。补充 junction、符号链接根目录、父目录别名和安装期间路径变化的测试。

### F2 放弃管理后的停用副本被当成旧官方缓存删除

位置：[LocalModManager.java](D:/Desktop/DreamingFish-Updater/player-app/src/main/java/cn/dreamingfish/updater/player/LocalModManager.java:213)，相关分支从 238 行开始。

可复现场景：

1. 玩家停用一个托管模组，文件进入 `local-mods/disabled`，记录 `managedAtDisable=true`。
2. 服主放弃管理这个文件，下一发布的 `releasedPaths` 包含它。
3. 玩家重新启用。`restoreEnabledUnmanaged` 根据停用时的标记跳过恢复。
4. `finalizeSuccessfulUpdate` 仍按停用时的标记删除存储副本；当前发布又没有文件可安装。

探针结果：`activeCopyExists=false storedCopyExists=false remainingPreferenceCount=0`。这是实际的数据丢失，违背“放弃管理并保留玩家副本”的语义。

建议根据当前所有权与当前发布决定恢复或处置，不能用历史布尔值代替当前状态。对已释放资源先恢复或继续保留，再提交偏好变更；不得清理其唯一副本。至少覆盖“停用期间删除”“停用期间放弃管理”“停用期间重新接管”“同组件改名”四类回归场景。

### F3 回滚复用了旧释放记录，破坏跨版本更新

位置：[PublishService.java](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/PublishService.java:129)，尤其是 141 行。

普通发布会从当前基线累计 `releasedPaths`；回滚却直接采用被回滚历史版本的 `old.releasedPaths()`。

探针依次发布“空包、托管 retired.jar、释放 retired.jar、回滚到空包”。一个玩家仍停留在托管版本，另一个已更新到释放版本。最终清单中的释放记录变成空列表：前者的副本被删，后者的副本保留。

探针结果：`releasedPathsBefore=[mods/retired.jar] releasedPathsAfter=[] oldBaselineFileExists=false releasedBaselineFileExists=true`。

建议回滚内容时继承当前仍有效的所有权释放和撤回规则，再排除历史目标中被明确重新接管的资源。回滚内容与回滚所有权应是两个显式决策，不能因为复用历史清单而顺便撤销所有权转移。

### F4 恢复失败后的 finally 可能销毁回退数据

位置：[BackupService.java](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/BackupService.java:106)，尤其是 123 行。

恢复过程中先把现有数据目录移动到 `rollbackRoot`，再把验证过的恢复目录移入。若移入失败，且随后恢复旧目录也失败，`oldMoved` 仍为 true；`finally` 会对 `rollbackRoot` 执行递归删除。这时该目录可能是唯一完整的旧数据副本。

这是静态控制流确认的条件性缺陷；本次没有在真实管理目录上做失败注入，不能声称已观察到实际备份数据被删除。Windows 文件占用、目标目录被并发创建或权限变化均需要纳入针对性故障测试。

建议仅在新状态已成功接管，或旧状态已成功恢复后清理旧目录。恢复失败时保留两个候选目录，并把路径写入错误信息。数据副本是否可删应由恢复状态机决定，而不是由“曾经移动过”的布尔值决定。

### F5 本地启停没有与文件移动共同提交

位置：[LocalModManager.java](D:/Desktop/DreamingFish-Updater/player-app/src/main/java/cn/dreamingfish/updater/player/LocalModManager.java:185)。

`reconcileDesiredState` 逐个移动文件，最后才统一保存存储索引。对两个本地模组都设置停用，并使用 Windows 文件占用令第二个模组移动失败：第一个文件已经离开活动目录，最终偏好记录仍不知道它的存储位置。

探针结果：`ioFailure=true firstActiveCopyExists=false storedFileCount=2 indexedStoredFileCount=0`。

文件字节还在停用存储区，因此与 F2 的全部副本丢失不同；但界面无法正常恢复这些副本，实例已处于部分修改状态。崩溃或偏好写入失败也存在同样窗口。

建议把本地启停纳入更新事务，或建立具有日志与恢复能力的本地文件事务。偏好索引、移动记录、文件状态必须共同提交。现有锁可以减少并发，但不能提供失败回滚。

### F6 发布确认没有绑定完整预览

位置：[PublishPreview.java](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/PublishPreview.java:6)、[PublishService.java](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/PublishService.java:51)、[AdminWebServer.java](D:/Desktop/DreamingFish-Updater/management-cli/src/main/java/cn/dreamingfish/updater/management/cli/AdminWebServer.java:1998)。

预览保存文件与文件差异，没有冻结项目策略。发布只检查基线和最终文件扫描；普通托管与目录强制同步的文件仍都使用 `ENFORCED`，故切换强制目录不会改变这份扫描结果。API 的发布请求也不携带预览标识。

探针使用零文件变化的旧预览，修改规则为强制同步 `mods`，随后直接发布成功；玩家的自选模组被归档。结果为 `oldPreviewChanges=0 publishedForcedDirectories=[mods] customModArchived=true`。

管理网页的专门按钮会重新扫描，这能减少一部分旧预览问题；但核心发布服务仍接受旧预览，重新扫描的文件差异也无法表达强制范围的变化。CLI、其他调用者和多窗口操作仍需要同一个一致性边界。

建议预览包含策略差异、完整候选目标和配置修订号。发布提交 `previewId + previewDigest + projectRevision`，任何影响文件处置的变化都使旧确认失效。玩家未知文件的具体数量只能在本机计算，但服主预览必须说明新规则会移出哪些范围的本地内容。

### F7 静态分发的清单与签名不是一个发布单元

位置：[StaticDistributionService.java](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/StaticDistributionService.java:132)、[SignedPayloadSupport.java](D:/Desktop/DreamingFish-Updater/update-engine/src/main/java/cn/dreamingfish/updater/engine/SignedPayloadSupport.java:37)。

静态导出先写 `latest`，再写 `latest.sig`；上传也分文件提交。玩家端取到可变清单后，再获取对应的 `.sig`。两个读取跨过更新切换时，会组合出旧载荷和新签名，或相反。CDN 对两个对象的缓存更新不同步也需要考虑。

探针分别构造两份有效签名发布，模拟跨切换读取，结果为 `STATIC_SIGNATURE_RACE result=INVALID_SIGNATURE`。签名检查正确拒绝了不一致内容，问题在分发协议的发布单元；不能通过放宽签名校验解决。

内置 HTTP 服务把签名放在同一个载荷响应头中，不受这个特定问题影响。静态发布建议使用单个签名信封，或让单个可变指针引用载荷和签名均不可变的发布位置。整合包、玩家程序、展示内容都应采用一致方案。

### F8 响应体停滞时超时与取消没有生效

位置：[ObjectDownloader.java](D:/Desktop/DreamingFish-Updater/update-engine/src/main/java/cn/dreamingfish/updater/engine/ObjectDownloader.java:132)，以及 172 行的阻塞读取。

本次环境中，`BodyHandlers.ofInputStream()` 收到响应后返回，后续 `input.read()` 可以继续阻塞。取消标记和线程中断检查都位于读取返回之后；调用方也可能阻塞在 `completed.take()`。

探针设置 200 ms 请求超时，服务器先发送一个字节并停住。500 ms 后任务未结束；随后设置取消，再等 400 ms，任务仍未结束。打印后放行服务器响应，任务才能继续并结束。

结果为 `finishedBeforeCancel=false finishedAfterCancel=false`。清单、签名等同类流式读取也应一起检查，不只修下载循环。

建议给响应体设置明确的读取期限或空闲期限，取消时关闭活动响应流并取消请求。返回失败前等待工作线程停止，确保后续重试不会与上次下载同时写 `.part` 文件。

## 架构合理性

| 现有设计 | 判断 | 后续处理 |
| --- | --- | --- |
| 协议、更新引擎、管理核心、输入输出适配分模块 | 合理，核心不必依赖具体 UI | 保留模块结构，明确领域决策入口 |
| 完整目标状态与单调发布序号 | 合理，支持玩家跨版本更新 | 历史处置规则也必须是完整目标状态的一部分 |
| 项目签名与内容寻址对象 | 合理，兼顾信任与文件复用 | 修分发一致性和路径边界，保留密码学验证 |
| 暂存、备份、日志、提交、恢复 | 基础较好，存在故障注入测试 | 把本地模组移动、索引和新撤回动作纳入同一恢复模型 |
| Java 8 启动引导器与独立玩家进程 | 职责清楚，并有打包后的隔离验证 | 保留兼容边界，规范与侧车的协议契约 |
| Java 管理端、Java 侧车、Rust 桌面壳、Vue UI | 能运行，但多语言增加契约维护成本 | 先统一模型与契约，再评估语言迁移收益 |
| SQLite 与磁盘对象分工 | 符合自托管管理端规模 | 补充配置修订、明确完整备份边界 |

不建议在本轮引入微服务或全面重写。上述错误发生在业务含义和状态衔接层，换一种语言仍会保留同样的问题。

## 复杂度集中在哪里

### 文件决策散落在多个模块

`RuleSet`、`PublishService`、`UpdatePlanner`、`LocalFileOverrides`、`LocalFileManager`、`LocalModManager` 和 UI 都分别解释一部分规则。单文件强制、目录强制、本地豁免、模组停用和所有权释放必须在多个位置一起修改。`isForced` 在玩家文件管理、模组管理和引擎内分别实现。

`LocalFileOverrides` 同时容纳玩家偏好和服主强制路径，调用方再用 `withForcedManagement` 补入远程事实。这个对象的名称与责任已不一致。建议把玩家选择作为事实输入，让统一策略解析器读取签名的服主约束，输出有效决策。

### 接受的发布与实际本地状态没有分清

`VerifiedInstallation` 的文件记录是当前清单文件列表的复制，包括被玩家豁免、实际可能未安装的条目。它适合记录“接受过哪份可信目标”，但不足以表达“本机现在持有哪些资源、谁拥有副本、副本位于哪里”。`managedAtDisable` 正在弥补这个缺口，却无法随所有权变化正确更新。

建议保留可信发布记录，另建本地资源状态记录：当前归属、实际位置、内容身份、玩家选择、最近应用的处置规则。两者与事务日志一起提交。

### 大文件是维护成本的信号

管理网页 `app.js` 约 3,894 行，`AdminWebServer` 约 2,105 行，`InteractiveConsole` 约 1,179 行，玩家状态 store 约 1,041 行，Rust 桥接约 844 行，`PlayerController` 约 685 行。

行数本身不证明设计错误；这里值得拆分的是账号、源文件、发布、分发、展示内容等变化原因，以及侧车进程、窗口控制和媒体访问的不同契约。应先建立统一应用服务，再按这些职责拆分适配器。单纯把一个文件切成几个文件不会消除规则重复。

可选音乐下载目前直接参与 `UpdateEngine` 的主流程，应单独管理展示资源的更新与失败策略。启动许可应依赖整合包和程序的必要条件，避免可选内容扩大更新核心的责任。

## 文档与实现需要对齐

1. ADR 0012 仍把 default-only 描述为正常策略，而新发布已移除 `DEFAULT`，只保留旧协议读取。后续若恢复“只首次下发”，要区分它和“每次缺失就补齐”。
2. 设计文档描述无签名基线时可在网络不可用后未验证启动；引擎当前会在联系网络之前因缺少基线抛出本地状态错误。相关静态分类测试不足以证明完整启动流程支持该路径。
3. 设计文档描述已有游戏运行且无需更新时可以再次启动；玩家控制器在最开始无条件要求独占游戏锁，实际主流程会更早拒绝。
4. ADR 0026 声明备份包含配置；目前归档显式包含数据库、密钥、清单、程序清单和对象，管理设置与 Web 账号文件由 CLI 另行存储。应把“恢复发布能力”和“恢复完整管理实例”写清，并覆盖后一种的配置恢复测试。

这些是静态核对发现的契约或覆盖缺口，未全部加入本次动态探针；需要在对应修复中明确产品行为。

## 已完成的验证

| 检查 | 结果 |
| --- | --- |
| 六个 Java 模块的 `clean verify` | 成功 |
| Java 测试 | 166 项通过，最终报告无失败、错误或跳过 |
| 玩家界面测试 | 13 个测试文件、57 项通过 |
| 玩家界面类型检查与生产构建 | 成功 |
| Tauri `cargo check --offline` | 成功 |
| Rust `cargo test --offline` | 9 项通过 |
| 隔离审查探针 | 八种观察结果，见复现说明 |

全仓库根级 `clean` 曾因已有打包目录中的 `DreamingFishAdmin.exe` 无法删除而失败。改用六个代码模块的干净构建后成功，含 Bootstrap 打包后的独立验证。该文件删除失败的原因没有在本次审查中进一步确认。

最终 Java 验证命令：

```powershell
.\mvnw.cmd -o -pl protocol,update-engine,management-core,bootstrap-agent,management-cli,player-app clean verify
```

本次没有进行真实 Minecraft 启动器验收、实际 CDN 上传切换测试、Linux 文件系统故障测试或依赖漏洞专项审计。Windows 路径探针只使用隔离测试目录；备份恢复的 F4 根据控制流确认，不以真实项目数据做破坏性复现。

## 重构顺序与完成标准

1. **先修文件安全与恢复问题。** 修复 F1 至 F5，并把复现场景转换为断言正确结果的正式回归测试。
2. **增加显式撤回规则。** 先解决“已释放文件后续需移除”的实际需求。规则必须持续出现在完整目标中，并具有历史内容匹配和归档恢复机制。
3. **提取统一策略解析器。** 将现有行为完整映射到决策表，用旧实现与新实现比较结果，再替换规划逻辑。
4. **统一本地资源状态和事务。** 活动文件、停用副本、已释放副本、撤回归档应共享身份与恢复记录。
5. **修发布预览及静态分发协议。** 把完整候选目标作为确认对象，签名载荷以一致的发布单元分发。
6. **最后整理适配器与界面。** UI 使用决策结果与原因，保留简单预设，不自行重建强制优先级。

完成标准是同一服主目标、同一玩家选择和同一本地实际状态产生一致、可解释的处置计划；跨版本更新、回滚与崩溃恢复都必须满足这个标准。详细模型、迁移方法和验收场景见配套草案。
