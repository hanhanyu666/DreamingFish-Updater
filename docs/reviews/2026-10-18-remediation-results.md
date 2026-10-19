# 2026-10-18 修复实施记录

> 后续独立复核发现了 F1–F4，本文的原验收结论属于当时交付记录。当前补充修复与验证见[2026 年 10 月 19 日后续记录](2026-10-19-followup-fixes.md)。

本轮起点：`b8793bbae74ffd57be176bfd153cac0e38bc472c`，Windows / PowerShell。原[审查](2026-10-18-comprehensive-review.md)、[计划](2026-10-18-remediation-plan.md)及原始探针保持历史含义。本轮只修改、构建、验证本地代码，不发布 Release，不部署服务，不使用真实管理数据做测试。

## 基线与约定

执行持续至 2026-10-19。批次早期计数是过程记录；最终矩阵、性能和剩余项以文末汇总为准。

- 未发现适用的 AGENTS.md。已有未提交权限生成文件、assets、两次审查报告与探针均保留；原文件清单及指纹保存在 `target/remediation-2026-10-18/baseline.json`。
- 原版本：管理端 / 桌面 0.2.5，玩家端 0.2.1，Bootstrap 0.1.2；修复记入未发布区，发行编号未调整。
- 迁移结果必须携带实际根目录；配置新格式显式版本化，默认 home 相对于实际桌面数据根解析。
- 项目异步操作固定项目身份和加载代次。导入稳定请求 ID 与持久收据分开表示网络状态、提交事实和后续检查结果。
- 完整内容证明不使用跨调用的大小 / mtime 哈希缓存。

## 批次进展

| 批次 | 状态 | 验收证据 |
| --- | --- | --- |
| 0 基线 | 已建立 | HEAD、工作区与历史探针已核对；后续回归断言正确行为 |
| A 数据定位 | 本地完成 | Rust 23 项通过，包含 6 个独立子进程退出点；Java 配置兼容定向测试见 batch-a-java.log |
| B 项目与导入 UI | 本地完成 | 两界面 27 项前端测试；真实 Java HTTP 导入工作流通过 |
| C 持久恢复与玩家安全 | 本地完成 | 12 项导入回归含独立 JVM 崩溃 / 恢复；3 项内容证明与跨进程锁验证通过 |
| D 网络资源 | 本地完成 | 64 慢连接 / 停滞回收通过；Rust 9 项代理测试含 20 次取消循环通过 |
| E 存储治理 | 本地完成 | 暂存 / 归档 / 发布收据、容量与清理、备份恢复 11 项及真实 HTTP 1 项通过 |
| F 窗口 / 发布脚本 / CSP | 本地完成，硬件项另列 | 每窗口共享恢复预算；8 个纯只读发布预演场景；玩家 77 项前端与 12 项 Rust 专项 |
| G 架构 / 性能 / 依赖 | 本地完成，上游与性能代价保留 | 服务作用域、6 项任务回归、300 个冷查询容量边界；两套官方 npm audit 归零；538 个 Maven / Cargo 坐标复核 |
| H 全量验证与文档 | 本地完成，未远程运行 CI / 部署 | Windows / Linux Java、两套前端、原生 release、包与隐藏桌面按依赖验证；源码构建身份约束 |

## 逐项结果

最终逐项表、三轮性能、完整验证与真实剩余项在本记录末尾；初期专项计数保留为批次历史，不替代最终汇总。

## 验证边界

真实硬断电、RTSS 注入、外部文档站、线上部署与正式发行不以本地测试替代。后续记录实际执行和未执行的检查。

### A：R1 / R4 / R5

- `migrate.rs`：源数据在逻辑提交前保持完整，迁移记录包含阶段、源 / 目标、连接快照和原设置。复制逐字节核对；检查绝对边界、路径重叠、链接 / junction，并取得数据锁。提交后清理失败仍使用已提交位置，报告保留内容。
- `profiles.rs`、`main.rs`、`service.rs`：配置 v2 相对 home、解析后的实际根、previous 恢复来源、旧路径重新定位；保存损坏配置不覆盖最后有效备份。Java 默认目录保存为相对路径，已有外部目录优先保持原身份。
- `cargo test --locked --manifest-path admin-desktop/src-tauri/Cargo.toml --bin dreamingfish-admin-desktop -- --test-threads=1`：23 passed。新增 `process_exit_at_each_commit_boundary_is_recoverable` 以测试子进程分别在 prepared、copied、settings、config、committed、source_removed 退出，重启后密钥字节和连接数量不变。另覆盖保存失败后续跑、两份损坏配置、恢复证据、同长度异内容和目录重叠。
- 已更新桌面说明、桌面 README 与 CHANGELOG。硬断电、真实跨盘和安装器升级需在最终边界表分别记录；不将子进程退出等同硬断电。

### B / C：R2 / R6 / R7 / R9 / R10 / R12、S5 的导入部分

- store 固定项目 ID 和选择代次，详情 / 文件原子应用；切换清空项目弹窗、草稿派生状态和资源，拒绝过期响应和错误归属的保存。导入任务有明确的项目、稳定请求身份、已跳过 / 已导入 / 检查失败 / 结果待确认 / 过期 / 需重选文件状态。新旧界面均先复查再重试，不以连接错误抹掉提交身份。浏览器刷新保留小型任务记录，不伪造 File。
- 管理 API state 提供 apiVersion=2 和 source-request-id / source-recheck / source-recovery 能力；旧基本请求仍兼容。stage 接受可选 requestId，重复 ID 返回同一暂存；真正缺失且没有提交 / 恢复记录的复查返回 410。旧 API 无法证明结果时保持待确认并提示检查版本。
- SourceImportRecovery 在第一处源修改前强制落盘意图，协调原文件归档、源内容、SQLite 规则和最终 receipt。requireProject / 项目写入口先恢复；CLI 与旧上传入口也转入同一导入协议。提交收据是最终事实；恢复遇到人工改动、归档损坏或身份冲突保留证据并阻止发布。提交后的取消不撤销事实。
- `SourceTransferRecoveryTest` + `SourceTransferServiceTest`：12 passed（batch-c-recovery.log）；六个提交点、四个撤销点使用独立 JVM Runtime.halt；恢复时再次退出、缺失归档、人为改动、稳定请求不重读正文均验证。
- `ContentProofAndLockTest`：3 passed（batch-bc-workflow.log）。严格内容证明绕过跨调用的磁盘缓存；同大小 / 同 mtime 改动离线被拒绝，联网后修复。独立 JVM 共享游戏锁期间不移走已知坏文件，不提交维护元数据。锁顺序维持 instance → game；无锁只允许不落盘的检查。
- `AdminWebServerTest#stagedImportApiKeepsContentUntilConfirmationAndProtectsMutationToken` 及新增稳定 ID、410、提交后复查与重复提交断言，均已在最终 HTTP 矩阵通过。

### D：R3 / R8、S6 分发部分

- 公共下载使用独立控制执行器与直接交接的下载执行器；默认控制 8 线程 / 64 队列、下载 16 / 无等待队列、无进展 30 秒、单任务 1 小时。超额 503 + Retry-After: 2；healthz 提供活动 / 排队 / 拒绝 / 停滞计数。保留 Range、HEAD、ETag、签名和流式正文。
- JDK 首次服务初始化前设置连接 256、空闲连接 32、空闲 15 秒、请求头 100 个 / 32 KiB；这些是 JDK 进程配置，公开来源见 [JDK 21 HTTP server 参数](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/module-summary.html)。JDK 21.0.9 本机源代码确认 maxReqTime 转为毫秒时乘 1000，采用秒值 3600；不能直接照搬文档中的单位文字。显式 JVM 属性在合法有界范围内保留；关闭资源上限的取值拒绝启动。
- presentation 按实际 payload 版本复用签名（最多 64 个、单项 512 KiB），配置变化立即生成新签名；当前分发进程首次生成仍需要私钥，未宣称私钥职责已隔离。
- Rust 代理改为会话拥有的异步连接 / 请求，默认连接 128、活动请求 16、连接期限 15 秒；短 API 响应头 / 停滞 30 秒，总 90 秒；长同步兼容 API 响应头 20 分钟、停滞 60 秒、总 1 小时。上传有进展时重置等待预算；下游写停滞 30 秒取消。断开取消全部连接并等待所属运行时退出，不自动重发写操作。
- `PublicFileServerLimitsTest`：64 个慢读取连接下 health 在 2 秒内 200，超额下载 503；1 秒测试停滞预算后名额可继续 Range 下载。`PublicFileServerTest` 原协议通过。相关 Java 15 项通过（batch-d-public.log）。
- `workbench::tests` 9 passed（batch-d-proxy.log）：响应头不返回、正文半途停住、持续慢正文、半途上传、20 次连接 / 断开、认证 / cookie / CSRF 和逐字节传输。20 次取消后活动请求均为零，Drop 每次在 2 秒测试预算内完成。

### E：R11、S6 存储 / 内存部分

- StorageService 提供保守容量统计、内容指纹绑定的清理预览、部分失败结果和跨进程租约。默认暂存 16 GiB、可恢复归档 64 GiB、磁盘余量 512 MiB、活动暂存 4 个、未提交暂存保留 24 小时；上传未知长度预留有界最大值，ZIP 同时预留压缩与 4 GiB 最大展开空间。上传和解压逐块计量、检查余量。
- 所有发布对象保守保留，统计区分当前 / 历史引用及未知引用；本轮不删除签名发布或对象。导入收据与恢复日志不作为缓存清理。明确选择放弃撤销才删除 before 归档；历史中会解释撤销不可用。过期暂存保留小型 EXPIRED 记录。
- CLI `storage [--forget-undo] [--confirm <stamp>]` 与 HTTP / 设置页复用同一用例。清理前再次核对指纹及选择，取得项目 / 上传 / 程序包锁，活动与恢复中数据保留，失败逐项报告。
- 源导入完成释放 payload；撤销使用 before 归档，重试仍通过 stage / receipt 查询成功。程序包发布使用持久意图和 published 收据，重复请求返回原发布；正式对象完成后释放 ZIP / bundle。未知发布意图保留，不能假装丢弃成功。
- 普通项目 / SQLite 访问取得稳定父目录共享维护锁；根替换使用排他锁，备份使用项目快照锁。根替换锁贯穿失败回退，避免另一个进程趁目录暂时不存在建立空库。默认恢复策略与原签名身份保持兼容。
- `StorageServiceTest`（4）、`BackupServiceTest`（4）、`PlayerPackageServiceTest`（2）、`ProjectLockTest`（1）及增强的真实 HTTP 导入测试（1）通过，见 batch-e-integrated.log。覆盖未知长度 / 配额拒绝、指纹过期、活动上传不删、明确放弃撤销后收据仍识别成功、重复清理、发布数据恢复、安装 / 回退双重失败保留双方。
- 管理 JVM 默认 -Xms64m / -Xmx512m；桌面和脚本入口验证 DFS_JAVA_HEAP_MB 为 128–16384 的整数并使用独立进程参数。Xmx 只限制 Java 堆，实际资源将在 G / H 实测；128 只是配置下界，不是所有项目的最低资源承诺。Windows EXE 固定默认预算，`dfs-admin.cmd` / `.ps1` 提供可调入口。


### F：S1 / S2 / S8

- recovery.rs 让 browser / renderer / unresponsive 共享每窗口 3 次 / 分钟预算，200 / 400 / 800 ms 退避，合并重复回调，代次拒绝旧 controller 事件。自动恢复不刷新预算；上限后托盘可手动重建，隐藏验收窗口保持隐藏。
- main 进入时即启动独立的后台绝对期限，默认 45 秒，可设 1–120 秒；即使 WebView / Java 初始化阻塞也写失败结果并退出。`both` / `limit` 故障模式用于真实隐藏窗口与手动恢复，模拟初始化挂起用于期限验收。没有把模拟故障称为真实 RTSS 注入。
- 发布计划先解析调用处的 NotesFile，DryRun 不调用 git / gh，不联网、不创建暂存或修改 dist。新安装包必须对应版本 / 标签 / URL / 公开签名；正式打包同时导出 .sig。8 个场景使用会立即抛错的 git / gh stub 验证；本轮未签名、未发布。
- 玩家 CSP 只允许内置脚本，禁 object / base / frame / form；Markdown 禁原始 HTML / 事件 / 危险 URL，保留普通图片、音乐、字体和链接。预览消息要求同源真实父页面；自定义原生命令校验内置 main 页面、命令格式和受限目录，不接受网页任意启动程序。

### G：S3 / S4 / S5 / S6

- ManagementCli 按实际数据根创建并复用 Services，请求作用域固定实例，活动请求 / 排队或运行任务阻止切根。HTTP 分出认证、项目、传输、发布、分发、存储和任务适配器；PlayerInstanceService 为 CLI / HTTP 共用，不构造 CLI 命令。
- ApplicationTasks 2 工作线程 / 8 队列，明确排队、运行、提交、成功、失败、未知、取消、等待凭据和过期。稳定 UUID / 摘要去重；仅排队可确定取消。重启不自动重放发布 / 外部上传，输入闭包与凭据不持久化。单项结果上限 16 MiB，详细结果按 256 项 / 64 MiB / 7 天收敛；按 ID 冷读也收敛，300 条历史反复查询不重填无界缓存，过期 ID 不变成新操作。HTTP 状态提供 cachedRecords / cachedBytes。
- 新网页先保存请求 ID，再提交 / 轮询；确认响应丢失只查询。旧页面继续同步 API，但共享导入 / 复查 / 已撤销契约；任务状态从 Java enum 生成 TypeScript，并用契约检查拒绝漂移。
- 目录按文件变化一次聚合，每页 200 项。实际浏览器使用 10,020 文件，方向键跨页、全量排序、全选和筛选外选择提示通过；截图与结果在 browser-selection.png / browser-validation.json。
- 中文改用系统字体回退，原字体及许可证未删；哈希静态资源 immutable，HTML / 认证 / 可变指针不缓存。public presentation 按 payload 复用签名，普通发布不重启公共服务。必要的内容校验仍直接读取当前字节。
- 依赖明细与上游剩余项见[依赖修复记录](2026-10-18-dependency-remediation.md)。不能把两套 npm 0 告警解释为所有平台零风险。

### H：统一验证与文档

- packaging/validate.ps1 顺序验证两套前端、契约与资源负例、Maven、两端 Rust、release 和本地发行包；隔离源副本拒绝使用已有目录作为新目标，依赖缓存可复用，构建输出与真实数据分开。
- build-info 记录源码身份及实际前端资产 SHA-256。Maven / Cargo 拒绝缺失、修改或不匹配的资产；打包复核管理 / 玩家 / Bootstrap JAR 和 core，桌面与 Java 就绪消息再核对身份。负例覆盖资源缺失、被改和源码变更后三端过期。
- CI 文件定义 Windows / Linux Java 21、两套前端和 Windows 原生 / 安装器包验收；没有触发远程 runner。工作流按本轮要求留在本地修改中，没有自动推送。
- README、桌面 / 工作区 / 部署 / 快速开始 / 简化维护 / 设计 / CONTEXT / CHANGELOG / 打包模板、ADR 0005 / 0007 / 0013 / 0020 / 0026 已对齐；新增 ADR 0038 / 0039、STORAGE 和单一 CAPABILITIES 矩阵。历史桌面计划标注为历史，原审查与探针指纹保持不变。
- 官网六页源码固定到 69d3822aa166390139c6d0433ccbb2708a9d55f4，生成[可应用补丁](../website-sync/2026-10-18.patch)，git apply --check、实际应用逐字节比较和 HTML 标识检查通过；线上网站没有变化。

## 最终逐项追踪

| 编号 | 改变的场景与实现 | 回归证据 / 边界 |
| --- | --- | --- |
| R1 | 迁移返回实际根，先记录 / 核对再提交；实际 data 根参与锁；重复连接 ID 保留两份；cleaning 支持部分删除后恢复 | migrate / main 定位测试，7 个子进程退出阶段；真实 Java 任务锁 + C→D SQLite 27 行及密钥；目标已有数据 / 同 ID / 部分清理 |
| R2 | 项目 ID + 代次固定详情、文件、草稿、弹窗和写操作，迟到响应不覆盖当前项目 | A/B/A、反序、失败加载、草稿和目录弹窗归属 |
| R3 | 公共下载 16 路 / 无等待队列，控制 8 线程 / 64 队列，连接 / 头 / 停滞 / 总期限有界 | PublicFileServerLimitsTest、原 Range / HEAD / ETag / 签名；64 慢连接健康检查及 503 |
| R4 | 区分不存在、损坏、读取失败；验证 previous，保留损坏原件及有效备份 | profiles / migrate 损坏主、两份损坏、恢复来源与清理证据 |
| R5 | 默认内部 home 为 v2 相对路径，外部绝对路径不搬；缺失或歧义明确重新定位 | 完整定位入口、AppData 回退解析、便携副本 / 外部身份与 C→D |
| R6 | 跳过独立结束，不提交 SKIP，不计入已导入 / 发布检查 | 新旧契约、真实 store、typed commit enum 与受保护丢弃复查 |
| R7 | 请求 / 暂存身份持久化，重试先查询；区分成功、撤销、未知、过期和需重选文件 | 丢确认 / 丢查询、服务器本地重试、提交后检查失败、HTTP 幂等与 410 |
| R8 | 会话拥有异步代理，区分短 / 长 / 停滞期限，断开取消并等待回收 | 9 项代理：不读、半上传、头 / 正文停滞、持续进展、20 次连接断开及认证 |
| R9 | 完整证明、安装目标、严格离线读当前字节；显示缓存不提供内容证据 | 同大小 / 同 mtime / 污染索引 / 离线拒绝 / 在线修复；必要 I/O 另测 |
| R10 | 引擎以 instance → game 锁保护全部写入 / 恢复；占用时仅可只读检查 | 独立 JVM 游戏锁、在线 / 离线及原启动、可选、停用、持续移除 |
| R11 | 分类、预算、租约、清理预览、部分失败、明确放弃撤销；释放大 payload，保留收据与签名对象 | Storage 5、Publish 峰值预留、Backup / Package / 维护锁及 HTTP；三轮暂存增量约 16 MiB → 1.5 KiB，收据保留 |
| R12 | 第一处源修改前持久意图，协调文件 / SQLite / receipt，冲突保留并阻止项目发布 | SourceTransferRecovery 5 + Service 7；独立 JVM 提交 / 撤销退出、再次恢复、缺档与人工修改 |
| S1 | 每窗口共同限次、退避、旧 controller 代次、手动重建；后台绝对期限 | budget / epoch 与真实隐藏 normal / both / limit / init-hang；最终严格五项通过；六次桌面对照无遗留 PID |
| S2 | NotesFile 按调用位置解析，DryRun 纯只读，新安装包 / .sig / latest 严格对应 | 8 场景，git / gh stub；未执行正式发布与签名 |
| S3 | npm 兼容修复归零；Jackson / rustls 更新，移除不用的 ZIP 特性 | 两端官方 npm 0；538 Maven / Cargo 坐标，仍有 10 包上游项 |
| S4 | 按实例复用服务、固定作用域、有界任务与跨根预留；冷读也收敛 | Services 100 次组装一次、任务 6 / 300 历史冷读、HTTP 丢确认与重启不重放 |
| S5 | typed plan / action / task；Java enum 生成 TS；旧基本接口保留，新能力不足明确升级 | 新旧契约、task-contract、真实 store / HTTP；旧收据缺字段不猜成功 |
| S6 | 目录预聚合 / 200 分页、系统字体、签名缓存、不重启服务、JVM / 存储预算 | 实际浏览器 10,020 文件；三轮 Java / 目录 / 桌面 / 内容证明完成，见性能报告 |
| S7 | 隔离验证、CI 定义、生产特性、源码 / JAR / 前端 / 原生标识、Linux LF 与私密数据审计 | 完整矩阵 + 原生收尾；缺失 / 修改 / 过期资源、CRLF / BOM 负例及错误核心拒绝 |
| S8 | CSP、Markdown、预览父源、原生命令来源 / 格式 / 目录边界 | 玩家 77 前端、12 Rust；保持图片 / 音乐 / 字体 / 实例策略 |
| S9 | 当前指南、模板、领域 / ADR、单一矩阵与网站补丁一致 | 本地对齐；官网 6 页补丁可应用未部署；历史与用户文件保留 |

### 验收中补齐的边界

- Linux 未消费 JSON 请求体时可能重置已写响应：统一限量 / 限时读取，关闭响应；30 次复用连接回归。
- 导出完成前先清理临时包并释放预算，避免下载结束但暂存仍在的竞态。
- launcher 快照曾在结构体字段中重复取 active 锁：改为单一有界作用域，超时回归与实际包共同验证。
- 部分删除后设置文件可能已不存在：使用 cleaning 和设置快照恢复锁位置，核对剩余内容再清理。
- Linux 脚本强制无 BOM / LF，审计拒绝 CRLF；CLI 冒烟显式确认合成项目的新版玩家要求。
- 打包显式启用根 custom-protocol 特性，原生 / core 标识须一致，development 不能通过。Cargo.toml 换行按规范化内容计算身份，输出资产仍逐字节校验。
- 错误核心负例检查拒绝、失败状态码及所属进程退出；返回式事件循环先清理再退出。
- 性能实测曾发现预算检查按每个对象重建租约，首次发布超过 180 秒。线程栈指向重复根锁 / Windows 路径解析；改为一次发布共用最大对象峰值预留，每对象归还临时计量，逐块余量查询复用 FileStore。100 对象 / 128 字节峰值 / 1 并发回归通过，同负载单轮发布恢复到约 78 秒；正式结论仍以三轮为准。


## 最终验收结果

R1–R12 的本地实现与正确性回归已完成；S1–S9 均有本地证据，上游、设备环境与线上交付的剩余项如下单列。没有发布 Release、部署服务或实现未来专属客户端功能。

最终构建标识：96d55f312c5e55a5da8a9323a8db0e9316672df491afbf54cb65802bc59c1d1e。管理端 / 桌面 0.2.5、玩家端 0.2.1、Bootstrap 0.1.2 的编号保持原值，都是本地未发布候选。

| 验证 | 真实结果 |
| --- | --- |
| Windows Java 21.0.9 / 全 6 模块 | 300 项，0 失败、0 错误、0 跳过 |
| Linux Java 21.0.12 / 全 6 模块 | 300 项，0 失败、0 错误、2 项 Windows 条件跳过 |
| 新 / 旧管理契约与真实 store / Chrome 初始化 | 39 项通过 |
| 玩家 Vue / 内容安全 / 现有组件 | 77 项通过 |
| Windows Rust 管理 / 玩家 | 37 / 12 项通过 |
| 上述 Windows 常规测试合计 | 465 项通过；不把额外探针重复计数 |
| 生产资源负例 / Linux 启动审计负例 | 资源缺失、篡改、过期、TOML 换行；CRLF / BOM 2 项通过 |
| 发布脚本 | 8 个纯只读场景通过；没有 gh / git / 下载 / 发布 |
| 实际 Windows 包流程 | 创建 / 3 次发布、签名基线、薄部署、程序篡改拒绝 / 恢复、HTTP 最新 / 历史通过 |
| 4 ZIP / 未签名 NSIS | 4 ZIP 私密数据审计通过；NSIS 生成，不安装到真实系统 |
| 实际 Linux 包 | 包内运行时、版本 / 帮助、创建 / 扫描 / 人工发布、tasks / storage、4 个非法堆值通过 |
| Windows可调启动器 | .cmd / .ps1 合计 12 个默认 / 合法 / 非法参数用例通过 |
| 真实隐藏桌面 | normal / both / limit / init-hang / stale-core 全通过；失败退出 1，无观测到的遗留进程 |
| 桌面旧 / 新资源对照 | 各 3 轮成功，观测到的所属进程退出 |

Linux 的两项跳过为 PathSafetyTest.rejectsWindowsJunctionsAsBothAncestorsAndRoots 和 LocalModManagerTest.preservesReadOnlyPlayerModsWhenDisablingAndRestoring；它们在 Windows 实际通过。WindowsIntegrationTest 的 OS 条件不被当作 Linux 图形功能覆盖。未删除原测试；公共发布后重启服务的旧断言改为不重启以匹配新行为。

完整 Java 矩阵后只追加了桌面控件握手修复，最终 Java 源码 / POM 未变，重新生成的 12,329 个类条目逐字节一致。该 300 项证据被显式复用；更新后的前端 39 / 77、Rust 37 / 12、生产包与实际隐藏验收重新执行。原失败日志保留，不冒充一次全绿的旧运行。

重现完整入口（新目录必须不存在）：

```powershell
.\packaging\validate.ps1 -JdkHome 'D:\Program Files\Java\jdk-21' -IsolatedRoot <新的绝对目录> -WithPackaging -WithDesktopSmoke
```

专项与包验证代码集中在 packaging/tests、desktop-remediation-smoke.py、audit-distributions.py。原始证据位于 target/remediation-2026-10-18；[机器可读汇总](../../target/remediation-2026-10-18/delivery-verification.json)、[最终 UI / 包矩阵](../../target/remediation-2026-10-18/final-ui-verification/result.json)、[Java 类一致性](../../target/remediation-2026-10-18/final-ui-verification/java-unchanged-proof.json)。

## 性能与资源结论

[完整三轮性能记录](2026-10-18-performance-results.md)列出每阶段中位耗时、CPU、采样工作集 / 私有提交、进程逻辑 I/O 和数据增长。两组场景各六份输入指纹一致。

- 10,000 条目 / 2,002 目录完整派生输出相同，中位约 7.82 → 0.098 秒；不是浏览器 FPS。
- 1088 文件列表约 0.45 → 0.25 秒；30 次状态查询约 3.54 → 2.69 秒。
- 16 MiB ADD 导入的管理存储新增约 16 MiB → 1.5 KiB，目标源文件两侧都保留，提交收据仍可复查。
- 完整内容证明每次实际多读 256 MiB，中位约 0.225 秒；旧缓存匹配不算可信校验。
- 桌面启动工作集合计约 912 → 879 MiB、私有提交约 685 → 661 MiB；耗时约 3.31 → 3.90 秒，未声称全面改善。
- 首次发布墙钟中位约 85 → 100 秒，CPU 相近；仍有约 17% 耗时代价。诊断发现的逐对象预算锁回退已修复；本轮没有通过减弱校验、去掉强制落盘或删除历史来提升数字。

## 真实剩余项与交付边界

1. Rust 仍有 10 个上游包 / 11 个公告标识（glib 重复标识归为同一问题），含 Linux GTK 绑定和停止维护图谱；[依赖记录](2026-10-18-dependency-remediation.md)列出触发范围、修复阻碍与缓解。两套官方 npm audit 均 0，生产 Maven / Cargo 可兼容修复已落实；本检查不覆盖全部 JDK / WebView2 / 系统或构建插件安全。
2. 真实 RTSS / GPU 注入、硬断电与不同存储设备、安装 / 升级 / 自更新失败 / 忙碌 / 卸载数据选项，尚未在专用可丢弃 Windows VM 完整验收。当前主机无 WindowsSandbox.exe，未提供专用安装验收 VM；不在真实系统安装覆盖。子进程退出与模拟故障不能替代这些结果。
3. 发布、presentation、持久导入和启动存在如上性能代价，不能宣称所有阶段提速；可进一步定位 I/O / 路径开销，但不降低安全或恢复保证。单机数据不推断玩家承载量。
4. CI 工作流已本地新增并完成相关本机检查，未推送 / 触发远程 runner。官网六页源补丁已验证，线上未更新。两项均按本轮本地范围留待后续交付。
5. 正式版本号、签名更新和 Release 尚未准备上线。管理核心 / 桌面、玩家 Java sidecar 与原生包均需要后续发行；仅更新桌面外壳不能向现有玩家交付 R9 / R10。签名私钥及旧信任序列未更换。

最终本地 ZIP 与未签名安装器保存在 [local-unsigned-packages](../../target/remediation-2026-10-18/local-unsigned-packages/README.txt)，附 SHA256SUMS；只用于审阅和测试，不作为自动更新。原有 23 个发行文件指纹保持，原有未提交权限 / assets / 历史报告与探针保持字节不变；计划文件只更新执行状态并回链本结果。
