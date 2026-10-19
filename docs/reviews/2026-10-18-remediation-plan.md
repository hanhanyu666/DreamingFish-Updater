# DreamingFish Updater 全面修复执行计划

执行状态（2026-10-19）：本地实施与验证已完成；逐项证据、三轮性能及真实剩余项见[修复结果](2026-10-18-remediation-results.md)。以下保留原计划要求，不把上游 / 硬件 / 远程交付事项标成已验收。

当前状态：本地实施与验证完成。原任务书创建时为待执行；实际已完成项目与未执行环境项以修复结果为准。

目标：修复 [2026 年 10 月 18 日审查报告](D:/Desktop/DreamingFish-Updater/docs/reviews/2026-10-18-comprehensive-review.md) 中的 R1–R12，并处理报告另外指出的窗口恢复、发布脚本、依赖、架构复用、性能、构建验证和文档偏差。完成后应交付可验证的代码、回归测试、同步后的文档和逐项完成记录。

计划基线为 `b8793bbae74ffd57be176bfd153cac0e38bc472c`；管理端／管理桌面为 0.2.5，玩家端为 0.2.1，Bootstrap 为 0.1.2。执行时重新检查 HEAD、工作区及适用的 AGENTS.md，以届时实际代码为准。仓库已有未提交的权限生成文件、品牌素材、审查报告和探针，不能用重置或清理工作区的方式覆盖它们。

本轮继续使用现有 Java、Rust、Vue 和 SQLite 架构。修复中需要的局部拆分、任务状态与接口调整属于范围内；服务器状态页、账号登录、完整启动器、聊天和游戏桥接等未来产品功能不在本轮实施。

**一、完整范围与完成记录**

执行线程在下面记录状态，并为每项链接最终实现、对应测试和验证结果。只有代码行为和验收均达到要求，才标为完成；不能仅因代码修改或某条旧探针不再运行就关闭问题。

| 项目 | 必须交付的结果 | 批次 | 初始状态 |
| --- | --- | --- | --- |
| R1 | 迁移失败后实际数据根目录、连接记录和提示一致；中途退出可恢复 | A | 本地修复与回归完成 |
| R2 | 项目切换、草稿、弹窗、异步响应与写操作始终绑定正确项目 | B | 本地修复与回归完成 |
| R3 | 公共下载连接、工作量与等待队列有界；慢下载不会饿死控制请求 | D | 本地修复与回归完成 |
| R4 | 损坏旧连接配置不能导致原配置和有效备份被静默删除 | A | 本地修复与回归完成 |
| R5 | 默认便携数据随程序移动；外部目录保持明确身份，不误连旧副本 | A | 本地修复与回归完成 |
| R6 | 跳过导入有独立结束状态，不发送非法提交动作 | B | 本地修复与回归完成 |
| R7 | 重试先查询实际结果，已完成导入不会重传或重复提交 | B、C | 本地修复与回归完成 |
| R8 | 桌面代理具有分类型期限、活动请求取消和完整退出能力 | D | 本地修复与回归完成 |
| R9 | 完整校验与严格离线许可不再依赖可过时的元数据缓存 | C | 本地修复与回归完成 |
| R10 | 所有影响游戏内容的提交和恢复都由引擎自身保证持锁 | C | 本地修复与回归完成 |
| R11 | 暂存、恢复数据、对象和缓存有分类、计量、配额与安全回收 | E | 本地修复与回归完成 |
| R12 | 管理端文件导入有持久操作日志和崩溃恢复，重试可识别最终结果 | C | 本地修复与回归完成 |
| S1 | WebView 各类故障统一限次、记录，达到上限后仍可手动恢复 | F | 本地实施与验证完成 |
| S2 | 发布说明相对路径正确；预演和测试不会真实发布 | F | 本地实施与验证完成 |
| S3 | 当前 npm 告警逐项核对、修复可修复依赖，残余风险有准确记录 | G | 可兼容修复完成；10 个上游包保留 |
| S4 | 管理服务按实例组装，重任务有明确生命周期，HTTP／CLI 复用应用用例 | G | 本地实施与验证完成 |
| S5 | 新旧界面的导入和发布行为对齐，关键接口与状态使用明确类型 | B、G | 本地实施与验证完成 |
| S6 | 重复扫描／组装、目录聚合、字体、分发签名与资源预算得到针对性优化和验证 | D、E、G | 三轮实测完成；性能代价如实保留 |
| S7 | 统一验证入口、CI、干净构建及发行包资源一致性检查 | H | 本地通过；远程 CI 未触发 |
| S8 | 玩家 CSP 与内容／原生能力边界加固，现有预览和资源功能仍正常 | F | 本地实施与验证完成 |
| S9 | 当前行为文档、ADR、备份边界、版本与能力矩阵全部对齐 | 每批、H | 本地对齐；官网补丁未部署 |

S 编号是本计划为审查报告中未使用 R 编号的事项补的跟踪号，不代表又发现了一批已复现漏洞。性能建议按这里的交付要求落地；没有证据支持的优化不得牺牲正确性。

**二、执行顺序与共同约束**

| 批次 | 工作内容 | 依赖与收口条件 |
| --- | --- | --- |
| 0 | 建立基线、转换缺陷探针、确定接口和迁移兼容策略 | 保留历史观察，建立修复后的正确断言 |
| A | 数据迁移与便携路径 | 可独立开展；必须先完成再改自动清理旧数据的逻辑 |
| B | 项目隔离与前端导入流程 | 提前与 C 约定任务身份和结果；先可修 R2、R6 |
| C | 导入持久化恢复、文件校验与游戏锁 | R7 的完整验收依赖这里的持久结果；E 不得先删除相关数据 |
| D | 公共服务与桌面连接资源边界 | 若公共端口面向互联网，此批与 A 同等优先；取消不等于回滚已提交操作 |
| E | 存储治理与资源预算 | 依赖 C 的事务、收据及引用规则；与备份恢复行为一起验证 |
| F | 窗口恢复、发布脚本、玩家 CSP | 可独立修复；最终必须用真实桌面产物验收 |
| G | 应用服务拆分、复用、性能与依赖 | 在关键流程有测试后做局部重构，维持已有接口行为 |
| H | 全量验证、文档一致性与交付 | 所有项目都有结果、证据及明确剩余项 |

每批采用“复现／断言 → 修复 → 定向测试 → 文档 → 更新完成记录”的流程。格式化和行为调整分开形成可审阅变更。过程中可自主解决常规实现选择，不必为类名、文件拆分和普通测试方式反复询问用户。

必须保留以下约束：

- 不修改既有签名发布内容，不重置项目密钥、信任序列或玩家偏好；回滚仍生成新的不可变发布。
- 默认维护规则、可选内容、持续移除、首次提供、玩家自行管理与备份语义保持兼容。
- 新旧网页、CLI、桌面、玩家端的职责分开；业务正确性不能只依赖某个界面的按钮禁用。
- 不把全部远端页面加入原生命令权限，不关闭 SSH 主机检查，不把令牌或私钥写进日志。
- 测试使用新的人工目录和回环服务；真实项目、实际 AppData、真实玩家实例和线上服务不用于破坏性验证。
- 文件清理与移动先验证绝对路径边界、符号链接／junction、目录重叠及占用；只能影响属于当前操作的对象。
- 原审查报告、原始测量和缺陷观察保留为历史。新增正式回归用例验证正确行为，修复记录回链历史报告。

**批次 0：基线与协议约定**

首先阅读原报告、[本次复现材料](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/README.md)及[旧缺陷探针](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-14/README.md)。记录实际版本、工作区状态和本次修改起点。旧探针可能断言“错误仍然发生”，不能直接把它们放进修复后的成功测试集合。

在改接口前确定四个小契约：迁移结果如何携带实际根目录；项目会话如何固定身份；导入任务如何查询持久结果；完整校验如何绕过缓存。契约需写入真实类型和测试，不只写在讨论中。

旧配置目前严格拒绝未知字段，新配置格式需要明确版本迁移；远程管理 API 要提供可识别的版本或能力信息。保持当前基本连接兼容，新增能力不足时明确解释需要升级，不能靠缺失字段默认为成功。升级过程中，新旧网页对旧 API 的合理调用仍应有清楚的行为。

**批次 A：数据迁移与便携路径，覆盖 R1、R4、R5**

主要入口：[迁移](D:/Desktop/DreamingFish-Updater/admin-desktop/src-tauri/src/migrate.rs)、[连接配置](D:/Desktop/DreamingFish-Updater/admin-desktop/src-tauri/src/profiles.rs)、[数据根目录与桌面启动](D:/Desktop/DreamingFish-Updater/admin-desktop/src-tauri/src/main.rs)、[本机服务连接](D:/Desktop/DreamingFish-Updater/admin-desktop/src-tauri/src/service.rs)、[Java 数据目录兼容](D:/Desktop/DreamingFish-Updater/management-cli/src/main/java/cn/dreamingfish/updater/management/cli/ManagementSettingsStore.java)。

实现要求：

1. 迁移返回明确的成功、回退、待恢复或待清理结果，包含真实有效的数据根目录和可展示的原因。调用方据此选目录，不能再只接收一条提示后始终使用新目录。
2. 在移动管理目录前写入可恢复的迁移记录，保存源、目标、事务版本和阶段。连接配置、管理设置与数据目录切换组成一个逻辑提交；任一步失败后仍能确定有效位置，重启可继续恢复。
3. 迁移期间阻止同一份管理数据被另一个实例使用。同盘改名和跨盘复制均需处理被占用、磁盘不足、目标已存在、大小写路径、链接及源目标重叠。
4. 跨盘复制在删除源前完成文件清单和内容核对，尤其包括数据库、密钥和配置。管理设置改写失败必须作为迁移失败处理，不能吞掉错误。
5. 将旧配置不存在、解析失败、读取失败分开处理。主配置损坏时验证 previous 是否可恢复；保留损坏原件及有效备份，并报告恢复来源。只有确认新记录保存成功才能清理对应旧文件。
6. 默认管理目录改为基于实际数据根目录解析的相对位置，外部管理目录继续保存明确绝对路径。若程序目录不可写而使用 AppData，默认目录仍相对于这次实际根目录解析。
7. 兼容旧连接记录。自动转换必须有足够身份依据；只有旧绝对路径而出现多个合理副本时，提供重新定位流程，不根据目录名猜选，更不能静默创建空库掩盖旧路径失效。
8. 标准源目录与管理目录分开定位。程序搬家不能擅自改写所有外部目录；缺失时显示当前配置位置并提供选择入口。日志和旧缓存清理失败应报告真实状态，不能声称“没有任何残留”。

验收场景：

- 搬运前失败、搬运后保存连接失败、管理设置写入失败、删除旧目录失败，均能找到正确数据；报错文本与实际使用根目录一致。
- 子进程在各迁移阶段退出，再启动后结果唯一且可继续使用；密钥字节、数据库记录和连接数量符合预期，没有自动生成另一套空项目。
- 旧主配置截断且 previous 有效、两份都损坏、权限拒绝，均保留恢复来源；不能无提示变成零条连接。
- 默认便携目录换盘、改名、复制后保留原副本；已选外部目录不移动；目标 data 已有项目；程序安装在不可写目录。
- 新格式默认连接搬家后打开新位置；旧格式遇到歧义时可解释地要求重新定位。测试需要覆盖完整数据定位入口，不能只测试迁移辅助函数。

文档同步：桌面使用说明、桌面 README、安装／便携模板、迁移说明、CHANGELOG；明确默认可携带目录与外部源目录的区别。

**批次 B：项目隔离与导入流程，覆盖 R2、R6、R7 和 S5**

主要入口：[新版 store](D:/Desktop/DreamingFish-Updater/admin-desktop/src/workbench/store.ts)、[类型](D:/Desktop/DreamingFish-Updater/admin-desktop/src/workbench/model.ts)、[任务界面](D:/Desktop/DreamingFish-Updater/admin-desktop/src/workbench/Tasks.vue)、[界面切换](D:/Desktop/DreamingFish-Updater/admin-desktop/src/workbench/host.ts)、[旧版导入流程](D:/Desktop/DreamingFish-Updater/management-cli/src/main/resources/cn/dreamingfish/updater/management/cli/web/file-transfer.js)。

项目会话要求：加载时捕获固定项目 ID 和请求代次，详情、文件与派生状态核对后一起更新。取消旧请求作为辅助，仍需拒绝迟到响应。草稿、弹窗、预览、撤销计划和异步写操作保存自己的项目身份。项目切换后，已发出的 A 项目操作可以得到 A 的最终结果，不能把结果显示到 B 或改成向 B 重发。

将任务状态和服务端动作分开建模。任务可包括“等待上传、上传中、等待确认、提交中、已导入、已跳过、结果待确认、检查失败、暂存过期”等状态；具体命名由实现决定，但不能用一个 error 混淆所有情况。`SKIP` 是客户端决策，不作为后端不支持的提交动作发送。

导入流程要求：

1. 任务从创建时绑定项目、目标路径和稳定请求身份。拿到暂存 ID 后保存并复用；服务端增加持久请求身份时保持旧请求兼容。若上传响应本身丢失，也应能查询或安全回收该次暂存，避免失去所有追踪依据。
2. 重试先调用复查，区分未提交、已提交、已撤销、过期和无法确定。已提交直接展示结果；未提交的有效暂存继续确认／提交；真正缺失或未完成的上传才重新传输。
3. 不因连接超时就认定后端没做事。结果未知时保留 ID 并查询；查询失败时显示“尚不能确认结果”，不能盲目覆盖原 ID。
4. 服务器本地导入任务不依赖浏览器 File 对象，也能复查与重试。页面刷新后浏览器文件不可恢复时，明确要求重新选择所需文件，不能把一个空对象当文件发送。
5. 跳过产生独立结束状态，释放可释放暂存；不计入已导入数量和发布检查 ID。界面切换、项目切换、清除完成项等所有 `state !== done` 判断一起调整，避免跳过项永远锁住界面。
6. 文件提交成功而后续发布检查失败时，文件列表仍刷新，并显示“已导入，检查尚未通过”。重新扫描不能重复导入。
7. 发布预览过期拒绝后，刷新检查并要求重新确认，不能自动再次发布。处理新旧页面相同行为，形成共享逻辑或共用契约测试。
8. 分清暂停后续任务与取消当前上传。进入文件提交阶段后的取消规则由 C 定义；不通过强杀写入来假装取消成功。

验收场景：A/B/A 连续切换、反序响应、加载失败、弹窗打开后切换、写操作期间切换；相同文件自动跳过与手动跳过；服务端提交成功但客户端收到错误／超时；服务器本地文件重试；暂存过期；旧 API 能力不足；导入成功但检查失败；旧发布确认被拒绝。既要测真实 store，也要有真实 HTTP 到 Java 服务的工作流测试。

文档同步：新旧工作区行为矩阵、上传／导入重试说明、暂停取消说明、发布预览过期说明。将“界面错误”和“服务端实际结果”区分清楚。

**批次 C：持久恢复与玩家文件安全，覆盖 R7、R9、R10、R12**

主要入口：[源文件导入服务](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/SourceTransferService.java)、[操作记录](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/SourceOperationJournal.java)、[文件索引](D:/Desktop/DreamingFish-Updater/update-engine/src/main/java/cn/dreamingfish/updater/engine/LocalFileIndex.java)、[更新引擎](D:/Desktop/DreamingFish-Updater/update-engine/src/main/java/cn/dreamingfish/updater/engine/UpdateEngine.java)、[事务安装](D:/Desktop/DreamingFish-Updater/update-engine/src/main/java/cn/dreamingfish/updater/engine/TransactionInstaller.java)。

管理导入采用持久操作日志：在第一处源文件修改之前，记录任务／项目身份、目标、原文件及哈希、归档位置、规则快照、预计新状态和阶段。把准备、修改中、已提交、恢复中及恢复失败等状态定义清楚；状态名称可调整，但每个阶段必须能决定重启后的动作。

文件系统与 SQLite 不能仅靠一个 Java catch 变成事务。需要协调两者的提交顺序，持久记录足够的恢复信息，并使用原子替换和可用的落盘机制。恢复前核对路径和指纹；发现人工修改、归档缺失或冲突时停止自动覆盖，保留证据并给出可操作提示。成功结果与幂等收据在清理大文件暂存后仍可查询。

启动／首次访问项目时处理未完成导入，恢复完成前禁止对该项目发布或进行冲突写入。检查 CLI、网页及桌面入口都遵守同一约束；不可只在某一个 HTTP handler 中增加保护。新日志与旧 stage／receipt 格式要有兼容读取策略，不能猜测无法证明的历史操作已经成功。

玩家校验策略：明确快速展示、完整校验、安装目标验证和严格离线许可的调用边界。需要证明内容正确的路径直接计算当前内容指纹，不信任磁盘索引中的旧哈希。允许使用同一次受控校验中的结果减少重复读取，但不得只以大小、mtime 或新增 fileKey 作为内容未变的证明。保留自行管理、首次提供等现有政策允许的差异，不能把完整检查变成覆盖全部玩家文件。

玩家持锁策略：引擎在进入任何修改游戏内容或关联维护状态的分支之前，必须取得并验证锁，覆盖恢复、在线安装、离线修复、基线提交、归档、偏好和受管理音乐资源等路径。独立缓存预下载可以不占游戏锁，但不能修改正在使用的游戏文件。审查 InstanceUpdateLock 与 GameUpdateLock 的顺序、嵌套调用及 PlayerController 的既有保护，避免修复后发生重入失败或死锁。

验收场景：

- 用独立子进程在备份后、新文件复制后、旧路径移除后、规则更新后、receipt 提交前／后退出；重启后恢复为一致状态，重试不会重复应用。
- 恢复过程中再次退出、恢复文件被人工修改、磁盘写入失败、归档缺失，均保留证据并阻止不安全发布。
- 同长度同 mtime 内容变化、索引被修改／损坏、强制完整检查、断网离线许可；不能再次得到对已改内容的错误“已验证”结果。
- 另一进程持有游戏运行锁时，在线与离线请求、待恢复事务、文件修正／清理均不提交游戏内容和维护元数据；正常无锁占用时仍可更新。
- 保留原有启动门禁、回滚、可选内容、停用模组、持续移除和玩家选择回归测试。

真实硬断电持久性与子进程退出测试分开记录。代码应尽可能使用合适的持久化机制，但没有硬断电验证时，文档不能承诺所有设备上绝对不会丢失最后一次写入。

文档同步：快速检查与完整校验、离线启动条件、游戏运行期间允许的操作、管理导入恢复与人工处理办法；写明提交后取消不能撤销已完成事实。

**批次 D：公共下载与桌面代理，覆盖 R3、R8 和部分 S6**

主要入口：[公共文件服务](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/PublicFileServer.java)、[服务控制](D:/Desktop/DreamingFish-Updater/management-cli/src/main/java/cn/dreamingfish/updater/management/cli/PublicServiceController.java)、[管理 HTTP](D:/Desktop/DreamingFish-Updater/management-cli/src/main/java/cn/dreamingfish/updater/management/cli/AdminWebServer.java)、[桌面代理](D:/Desktop/DreamingFish-Updater/admin-desktop/src-tauri/src/workbench.rs)、[玩家传输](D:/Desktop/DreamingFish-Updater/update-engine/src/main/java/cn/dreamingfish/updater/engine/HttpTransfer.java)。

公共服务要求：连接总数、活动下载数、等待工作量和停滞时间均有明确上限；健康／轻量控制请求保留执行能力。超出下载容量时快速给出可重试响应，保持 Range、HEAD、ETag、签名、内容长度与流式传输正确。验证玩家端能合理处理限流和恢复，不将过载误报为文件损坏，也不无限重试。

不能仅把固定池换成虚拟线程，或只换成有界队列就视为完成。要核对 HTTP 实现中的接收、调度和阻塞写响应各层；尤其在 JDK 21 下确认慢响应不会占满健康请求使用的执行资源。若当前 HTTP 实现无法满足边界，可局部调整传输实现，保留协议与业务服务，不据此重写整个管理端。

桌面代理要求：连接、响应头、正文无进展和总任务期限分开处理；短 API 和大文件传输使用不同策略。持续有进展的合法大文件不能因统一短超时被切断。发布／扫描等长业务与 G 的任务 ID 协作；兼容旧同步 API 时，超时提示结果未知并提供查询，不能自动重发写操作。

连接会话要拥有全部活动请求、工作任务和线程的生命周期。断开时停止接收新请求，取消尚未完成的网络工作，关闭相关传输，并在有限期限内等待退出。中断客户端传输不等于回滚服务端已经提交的数据；服务端完成结果仍通过 B／C 的任务协议查询。保留 Host、Origin、桌面令牌、CSRF、cookie 隔离与只代理当前服务的限制。

验收要求：

- 在隔离回环服务上占住旧复现中的 64 个下载，以及配置上限附近的连接；健康请求在 2 秒内返回，新下载有明确限流结果，实际队列和线程／任务计数不无限增长。
- 测试“不读取响应”“上传半途不再发送”“响应头不返回”“响应体半途停住”“正常慢速持续传输”。只测试连接失败不够。
- 断开后 10 秒内本次连接的活动任务和所属端口退出；至少 20 次连接／断开后不累积活动转发或线程。上述时间是本机隔离测试的验收预算，产品配置需记录实际默认值和适用范围。
- 释放慢连接后服务恢复；限流、断点续传、取消与玩家可信离线规则一起测试。
- 日志与状态接口可看到活动数量、队列长度、拒绝次数和停滞次数，不记录认证秘密。

可同时优化公共 presentation：在配置提交或明确缓存失效时生成签名结果，避免每次 GET 都读私钥并签名。实现必须覆盖 CLI 与网页修改、重启和旧数据初始化，保证 payload、签名和 ETag 来自同一版本；只有完成分发职责隔离后，才可声称分发进程不再需要私钥。

文档同步：配置默认值、过载与重试说明、桌面断开语义、推荐部署拓扑、静态托管与缓存配置。

**批次 E：存储治理和内存预算，覆盖 R11 和部分 S6、S9**

主要入口：[程序包暂存](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/PlayerPackageService.java)、[源文件导入](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/SourceTransferService.java)、[对象存储](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/ObjectStore.java)、[备份](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/BackupService.java)、[发放界面](D:/Desktop/DreamingFish-Updater/admin-desktop/src/workbench/DeliveryView.vue)。

先给数据分类并建立生命周期，再实现删除：

| 数据 | 默认处理 | 必须保护的关系 |
| --- | --- | --- |
| 进行中的上传／解压／导入 | 不清理，受总配额和磁盘余量限制 | 当前任务和事务租约 |
| 未提交且过期的暂存 | 可按明确保留期自动回收；提供手动清理 | 排除待确认、恢复中及仍活跃任务；过期结果可识别 |
| 已发布玩家程序的 ZIP 与解压副本 | 正式对象提交验证后释放可重建副本 | 成功收据保留，可查询且重试不重复发布 |
| 已提交导入的原文件与必要 payload | 根据撤销／恢复承诺保留 | 操作记录、归档和事务日志引用 |
| 历史发布与对象 | 保守保留，只有明确清理计划才删除 | 所有保留发布、玩家程序、未发布品牌资源、修正与恢复引用 |
| 可重建缓存和旧日志 | 可按单独策略回收 | 不把玩家备份误归为缓存 |

新增容量统计和清理预览，列出可释放大小、受保护原因、将丧失的撤销／回滚能力。确认清理时再次验证预览依据，避免预览后新任务开始而误删。所有入口复用同一服务；文件占用或部分清理失败时返回实际结果，不显示全成功。

设置可配置的暂存总配额、最大并发暂存、保留期和最低磁盘余量。上传前与解压／复制过程中都要检查，未知 Content-Length 和 ZIP 展开不能绕过限制。预估需要包含暂存、解压、旧副本及备份 ZIP／加密中间文件，不只计算最终输出大小。

清理不能删除 R7 的幂等结果或 R12 的恢复日志。若大文件 payload 已释放，保留足够的小型收据让客户端识别已完成；历史请求过期应明确返回过期／需重新确认，不能把它静默当作全新操作。GC、发布、恢复和备份共用一致的锁及引用快照规则。

备份按实际用途明确命名与范围。当前加密归档以恢复发布能力为主，源目录、运行设置、账户、连接、导入撤销历史等是否包含都要逐项列明。新增任务日志如果是恢复一致性必需的数据，需纳入一致备份或在安全快照前完成／拒绝未完成操作。另提供“完整搬迁需要保存哪些内容”的清单，避免把发布备份称为整个工作台镜像。

管理核心增加可说明的 JVM 预算入口，贯穿 CLI 启动脚本与桌面启动器；参数应结构化验证并以独立参数传给进程，不能拼接不可信 shell 命令。以实际项目验证默认预算和可调整方式，不把此前 256 MiB 堆测试写成所有场景的最低配置，也不把 Xmx 称为整个进程内存上限。

验收场景：多次上传／发布程序包后无可重建暂存持续堆积；放弃、失败、刷新、过期、重复清理；活动任务与恢复中数据不被删；配额耗尽明确拒绝且已有内容完好；清理后重试仍识别成功；备份恢复结果与范围说明一致。记录清理前后各类字节数，并验证被保留文件的内容。

文档同步：存储分类、默认配额与保留期、清理影响、备份范围与迁移清单、JVM 配置及资源测量口径。

**批次 F：窗口、发布脚本和内容隔离，覆盖 S1、S2、S8**

窗口恢复主要入口：[桌面生命周期](D:/Desktop/DreamingFish-Updater/admin-desktop/src-tauri/src/main.rs)、[窗框](D:/Desktop/DreamingFish-Updater/admin-desktop/src-tauri/src/frame.rs)。统一处理浏览器进程退出、渲染进程退出和无响应，给每个窗口或明确的故障单元设置限次与退避，并完整记录原因、尝试和结果。

窗口对象与活动连接状态必须协调：达到上限后可显示明确错误，通过连接页／托盘手动重建或重连，不能保留一个“已有工作区”记录却永远找不到窗口。主窗口失败也要保留可达的恢复入口。连接中、断开中、自更新中及退出中不应被迟到故障事件重新拉起。隐藏窗口恢复后仍隐藏，避免后台验收抢焦点。

`--background-check` 的总超时应从程序验收开始计算，不能等页面加载完成后才开始计时；初始化卡住时也应生成失败结果并清理自己创建的进程。正常与模拟故障均要验证主窗口、工作区、托盘和退出。实际 RTSS 环境无法复现时如实记录，不将模拟通过写成已覆盖所有注入问题。

发布脚本主要入口：[publish-release.ps1](D:/Desktop/DreamingFish-Updater/packaging/publish-release.ps1)。在改变工作目录前将 NotesFile 解析为绝对路径，后续复用。测试仓库根目录相对路径、其他工作目录、绝对路径、空格与中文路径，以及不存在的文件。使用 stub／mock 的 gh 或可测试的参数构造逻辑验证调用，不能为测试创建真实 Release。

使 DryRun 的行为与“只列出计划”的承诺一致：可以做必要只读检查，输出资产、目标和清单决策；避免覆盖实际 dist 清单或上传产物。核对未带新桌面安装包时沿用 latest.json、标签与 URL 匹配、签名产物缺失等路径。签名私钥不进入仓库或测试日志。

内容隔离主要入口：[玩家 Tauri 配置](D:/Desktop/DreamingFish-Updater/player-ui/src-tauri/tauri.conf.json)、[Markdown 渲染](D:/Desktop/DreamingFish-Updater/player-ui/src/lib/markdown.ts)、[玩家原生入口](D:/Desktop/DreamingFish-Updater/player-ui/src-tauri/src/lib.rs)。基于现有资源与 IPC 需求设置生产 CSP，限制脚本、对象、基础地址、frame 和连接来源；保留本地图片、受支持远程图片、音乐、字体与预览必需的能力。

测试普通文字、外部链接、恶意 HTML、事件属性、危险 URL、跨来源消息与非预期页面的原生命令访问。不能用放开任意脚本源或关闭权限检查来修复兼容问题。CSP 不能代替 Markdown 清洗和 Rust／Java 侧的路径与权限检查。

文档同步：窗口自动恢复次数与手动恢复流程、后台验收结果、发布脚本用法与预演语义、内容格式和支持范围。

**批次 G：架构复用、性能和依赖，覆盖 S3、S4、S5、S6**

主要入口：[服务组装](D:/Desktop/DreamingFish-Updater/management-cli/src/main/java/cn/dreamingfish/updater/management/cli/ManagementCli.java)、[管理 HTTP](D:/Desktop/DreamingFish-Updater/management-cli/src/main/java/cn/dreamingfish/updater/management/cli/AdminWebServer.java)、[扫描](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/ScanService.java)、[源文件列表](D:/Desktop/DreamingFish-Updater/management-core/src/main/java/cn/dreamingfish/updater/management/SourceFileService.java)、[新版目录聚合](D:/Desktop/DreamingFish-Updater/admin-desktop/src/workbench/model.ts)、[新版文件页](D:/Desktop/DreamingFish-Updater/admin-desktop/src/workbench/FilesView.vue)。

服务与任务拆分：

1. 按实际管理实例／数据根目录构建服务上下文，数据库初始化与迁移只在明确生命周期执行。切换根目录时等待或拒绝冲突任务，再替换上下文；不能留下请求继续写旧实例却展示新实例的状态。
2. 按认证、项目、导入、发布、分发、存储等职责拆分 HTTP handler，保留统一的认证、错误映射和任务入口。HTTP 不再通过构造 CLI 命令来完成业务；HTTP 和 CLI 调用同一应用用例。
3. 重任务采用有界执行器和可查询任务 ID，表达排队、执行、提交、成功、失败、结果待核对和取消状态。让扫描、发布、导入、备份、导出等有进度与最终结果；复用 C 已有的导入事务，不另造互相矛盾的完成状态。
4. 上传接收／解压与真正修改标准目录的提交分阶段管理，避免慢上传一直占住全局写锁。项目内冲突写入串行，独立项目受全局资源配额约束；涉及全局设置、迁移、备份恢复和 GC 时保留必要排他协调。
5. 明确跨进程文件锁和进程内锁的顺序，CLI 与 HTTP 同时操作也不能破坏一致性。不同项目若共用或重叠源目录，需要按实际文件资源协调或拒绝不安全配置，不能只按项目 ID 判断互不影响。
6. 任务中不明文保存 WebDAV／S3 密码、令牌或签名私钥。需要外部凭证而无法恢复的任务等待重新提供，不能在恢复时盲目重发外部写操作或创建重复发布。
7. 将请求／响应／错误码／任务状态定义为明确 DTO 或 schema。关键状态消除 any 与普通字符串动作；实现输入运行时校验和兼容样本。枚举名字由同一来源维护，新旧界面共用工作流或共享同一套契约测试。

架构验收重点是职责与行为：重复请求不再反复初始化数据库；同一用例从 CLI、网页、桌面得到一致业务结果；长任务的网络连接断开后可查询结果；队列满时有明确拒绝；任务重启不能导致再次发布；新旧 API 能力识别正确。文件行数下降只是副产品，不能替代这些验收。

Minecraft 语义目前位于通用包中，需要收窄 ADR 中“完全平台无关”的声明，并让模组元数据读取与目录语义有清楚的适配边界。优先抽离现有代码里真实重复的职责；没有第二个使用场景时，不创建空泛插件框架或为了复用破坏 Java 8 Bootstrap 的独立边界。

性能工作：

- 合并同一次列表请求的重复目录遍历，复用一批导入任务的稳定元数据，减少重复服务组装和相同请求内的重复工作。目录或配置改变时使缓存失效。
- 扫描／发布继续保证预览与最终发布内容一致。优化必须说明缓存信任边界；不能以大小、mtime 缓存替代必要的内容证明。无法安全省略的全量验证保留，并准确说明成本。
- 前端预计算目录后代数量和大小，避免每个目录重复过滤全量文件。大目录采用分页或虚拟列表，保持搜索、排序、Ctrl／Shift 选择、隐藏选择计数和键盘操作正确。
- 字体采用经过验证的子集／按需加载或系统字体回退方案，保留字体许可证和中文字符回退；给内容哈希静态资源合理缓存，HTML、认证数据和可变最新清单使用合适的更新策略。
- 桌面界面与 Java 核心分别测量。排查不必要的隐藏页面加载、预览和缓存资源；不能为了省内存合并原本需要隔离的认证会话或连接上下文。

性能验收采用同机、同数据、同配置的前后对照，每种方案至少三轮，报告中位耗时、采样峰值、CPU 时间、进程 I/O 与存储增长。除原来的 1088 文件／256.125 MiB 基线外，增加含真实格式模组元数据的人工 JAR 和数千至上万小文件场景。完整校验增加的必要读取单独说明，不能用降低校验强度换取“性能提升”。

输出原始数据和实际配置；至少证明重复初始化、目录聚合及暂存增长得到改善，长任务仍在有界资源内完成。RSS／工作集、Java 堆、私有提交内存及多个进程共享页面不能混成一个指标。不把单机测试换算成未经验证的在线玩家承载数量。

依赖处理：以执行日官方公告和实际锁文件重新检查，而不是机械使用审查当天计数或猜测版本。对 Vue server-renderer、Vitest／mocker、nanoid、source-map-js 对应告警追踪依赖链和触发入口，更新存在的兼容修复版本并运行相关测试，保持两端版本策略可解释。不要强制 audit fix 导致无审查的大版本变更。

可用时补充 Java、Rust 的依赖公告检查，报告覆盖范围。若上游没有可验证修复，优先评估移除不需要的依赖或有效缓解；仍不能解决的项目保持未解决状态，记录公告、触发条件、缓解与阻碍，不能通过忽略告警或改文案宣布全部修复。

文档同步：当前架构图、任务与取消语义、API 能力及兼容矩阵、资源预算和性能数据、依赖处理记录。

**批次 H：构建、自动验证、文档与交付，覆盖 S7、S9**

统一入口应按照真实依赖顺序执行：安装／核对依赖 → 玩家前端测试及构建 → 管理前端测试、网页构建与桌面前端构建 → Maven 验证 → 两端 Rust 测试 → 桌面 release 与发行包检查。前端输出必须来自这次源代码，不能仅因旧 dist 存在就允许发布构建通过。

增加构建标识与产物一致性检查，验证嵌入 JAR 的网页入口、玩家预览、桌面前端、管理核心和版本信息属于同一构建。对缺失或过期的前端资源给出可理解的构建失败。至少完成一次隔离的干净构建；清理标准构建目录前先保存仍需留存的审查证据，不使用全仓库强制清理。

建立已提交的 CI 工作流：Java 21、两套前端和两端 Rust；管理核心在 Windows／Linux 上分别验证。原生桌面与安装器使用适合的平台 runner。增加协议兼容样本、关键工作流、文档命令冒烟和发行包无管理私密数据检查。流水线不得携带真实项目密钥；若需要验证更新签名，使用隔离测试密钥和测试配置。

以下命令是现有验证入口，实施时可封装进统一脚本。命令在 `D:\Desktop\DreamingFish-Updater` 执行，各步骤失败必须停止依赖步骤：

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21'
function Invoke-RepairCheck([string]$Executable, [string[]]$ToolArguments) {
    & $Executable @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "验证失败：$Executable，退出码 $LASTEXITCODE" }
}
Invoke-RepairCheck 'npm.cmd' @('--prefix', 'player-ui', 'ci')
Invoke-RepairCheck 'npm.cmd' @('--prefix', 'player-ui', 'test')
Invoke-RepairCheck 'npm.cmd' @('--prefix', 'player-ui', 'run', 'build')
Invoke-RepairCheck 'npm.cmd' @('--prefix', 'admin-desktop', 'ci')
Invoke-RepairCheck 'npm.cmd' @('--prefix', 'admin-desktop', 'test')
Invoke-RepairCheck 'npm.cmd' @('--prefix', 'admin-desktop', 'run', 'build:web')
Invoke-RepairCheck 'npm.cmd' @('--prefix', 'admin-desktop', 'run', 'build')
Invoke-RepairCheck '.\mvnw.cmd' @('-B', '-ntp', 'verify')
Invoke-RepairCheck 'cargo' @('test', '--locked', '--manifest-path', 'admin-desktop/src-tauri/Cargo.toml')
Invoke-RepairCheck 'cargo' @('test', '--locked', '--manifest-path', 'player-ui/src-tauri/Cargo.toml')
Invoke-RepairCheck 'cargo' @('build', '--release', '--locked', '--features', 'custom-protocol', '--manifest-path', 'admin-desktop/src-tauri/Cargo.toml')
```

JDK 路径按实际机器调整。上面的 cargo 构建不能代替发行打包：正式本地验收还需通过打包脚本或等效受控流程更新内置 Java 核心、Bootstrap 和资源，并核对版本；不能运行带旧 core 的新外壳后声称新核心已验收。正常开发先运行定向测试；在行为收敛后运行完整矩阵，不需要每改一句文档都重跑全套测试。

另执行转换后的正确性探针、真实 HTTP 工作流、隐藏桌面验收、资源对照及发行包 smoke／audit。可复用 [已有桌面测量脚本](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/desktop-resource-probe.py) 和 [核心测量脚本](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/resource-probe.py)，但修复改变输出格式或任务协议时应同步适配，不能继续读取旧结果冒充新验收。

安装／升级验收包括从旧配置与旧管理数据升级、便携覆盖程序后保留 data、自更新下载失败、所属服务忙碌、安装后重开、卸载保留数据与按选项清理缓存。能在隔离环境执行的用测试数据完成；缺少环境的项目单独记录待验收，不能只写“构建成功”就关闭。

**三、文档逐项对齐清单**

每批随代码更新相关内容，最后整体核对一次。文档描述已实现行为，不预先把本计划中的目标写成当前功能。

| 文档 | 必须对齐的内容 |
| --- | --- |
| [README](D:/Desktop/DreamingFish-Updater/README.md) | 当前桌面支持／验证状态、版本关系、校验与恢复承诺、默认部署方式；不擅自把试用状态写成已正式验收 |
| [管理桌面说明](D:/Desktop/DreamingFish-Updater/docs/ADMIN-DESKTOP.md) | 数据真实位置、迁移结果与残留、便携搬家、外部目录、连接取消、窗口故障及恢复入口 |
| [桌面构建说明](D:/Desktop/DreamingFish-Updater/admin-desktop/README.md) | 新配置兼容、后台检查、资源预算、自动更新、构建及签名流程 |
| [工作区说明](D:/Desktop/DreamingFish-Updater/docs/ADMIN-WORKSPACE.md) | 新旧界面能力、跳过／重试／结果未知、任务恢复、暂停取消、发布预览过期后的真实行为 |
| [部署文档](D:/Desktop/DreamingFish-Updater/docs/DEPLOYMENT.md) | 当前 DELETE／RELEASE 语义、可信离线、限流／超时、静态分发与缓存、存储清理、备份及完整搬迁清单 |
| [快速开始](D:/Desktop/DreamingFish-Updater/docs/QUICKSTART.md) | 创建、发布、部署与升级操作仍能按文字完成；新设置默认值一致 |
| [简化维护说明](D:/Desktop/DreamingFish-Updater/docs/SIMPLE-MANAGEMENT.md) | 原有文件策略不被修复改变，严格校验与游戏锁行为可解释 |
| [设计文档](D:/Desktop/DreamingFish-Updater/docs/V1-DESIGN.md) | 当前模块边界、任务生命周期、公共分发与管理职责、内容校验策略 |
| [领域说明](D:/Desktop/DreamingFish-Updater/CONTEXT.md) | 管理备份、临时缓存、可恢复归档、任务／事务含义与实际范围一致 |
| [CHANGELOG](D:/Desktop/DreamingFish-Updater/CHANGELOG.md) | 在未发布区记录具体修复、兼容变化与验收；最终发行版本决定后再统一编号，不改历史版本事实 |
| [打包模板](D:/Desktop/DreamingFish-Updater/packaging/templates) | 安装与便携数据说明、运行预算、迁移和备份说明与主文档相同 |
| [ADR 0005](D:/Desktop/DreamingFish-Updater/docs/adr/0005-allow-offline-starts-from-a-verified-installation.md) | “已验证”的含义及离线修复持锁要求 |
| [ADR 0007](D:/Desktop/DreamingFish-Updater/docs/adr/0007-separate-the-universal-core-from-platform-support.md) | 通用文件机制与 Minecraft 适配的实际边界 |
| [ADR 0013](D:/Desktop/DreamingFish-Updater/docs/adr/0013-use-only-the-built-in-http-artifact-source-in-v1.md) | 标记被后续静态分发／外部托管决策替代，保留原决定的历史 |
| [ADR 0020](D:/Desktop/DreamingFish-Updater/docs/adr/0020-keep-transaction-backups-only-until-commit.md)、[ADR 0026](D:/Desktop/DreamingFish-Updater/docs/adr/0026-provide-complete-encrypted-management-backups.md) | 临时事务备份与长期可恢复归档的区别、加密备份准确范围 |
| [历史桌面实施计划](D:/Desktop/DreamingFish-Updater/docs/ADMIN-DESKTOP-PLAN.md)、[历史审查目录](D:/Desktop/DreamingFish-Updater/docs/reviews) | 历史状态清楚标注；0.3.0 临时编号不再冒充当前版本；原测试和证据不重写，增加当前文档／修复结果链接 |

新增一份当前能力矩阵，覆盖 CLI、新网页、旧网页、桌面，以及所需后端版本／能力。可以新建 `D:\Desktop\DreamingFish-Updater\docs\CAPABILITIES.md`，也可在已有文档中集中维护；关键是只有一个明确的当前入口。检查“自动、完整、全部、恢复、无残留、便携”等承诺，并把它们对应到具体实现与验收。

官网文档与当前仓库不是同一份可编辑源。若执行环境能访问官网源仓库，可同步源文件并验证；若不能访问，列出待同步页面和可应用修改，明确官网尚未对齐。不能只改本地 README 就声称外部网站已更新。线上发布在本地修复与验收之后另行安排。

**四、最终完成标准与交付内容**

完成必须同时满足：

1. R1–R12 全部有修复实现和对应正确性回归；S1–S9 全部有可核查结果。无法解决的上游、环境或外部站点事项保持明确剩余状态，不从列表中消失。
2. 当前全部相关测试通过。此前 391 项只是历史基线，不是固定目标数量；新增场景应纳入测试，任何跳过、失败和删除旧测试都说明原因。
3. 迁移、导入和恢复的故障注入通过；游戏运行锁由独立进程验证；网络饱和与取消测试覆盖实际 HTTP／代理。
4. 资源、磁盘清理与重试不会破坏互相依赖的数据；限额参数、默认值与文档一致，原始性能数据可复核。
5. 干净构建包含本次前端与核心，协议和旧数据兼容通过；发行包不含管理账户、项目私钥、真实数据或测试秘密。
6. 文档逐项更新，历史记录保留；确认需要发行的组件和版本关系。管理端与玩家端均有修复时，两者都应列入后续发布准备，不能只更新桌面外壳就宣称 R9／R10 已交付给玩家。

最终新增一份修复结果记录，例如 `D:\Desktop\DreamingFish-Updater\docs\reviews\2026-10-18-remediation-results.md`，包含每个 R／S 编号的状态、实现位置、测试名称与命令结果、文档改动、兼容说明、前后性能数据和仍待完成事项。面向用户的总结继续使用“什么场景、原来怎样、现在怎样”的表述。

本轮代码、测试和文档修复完成后再准备正式发布；不要在本次修复中顺带部署线上管理服务、修改真实项目或创建 GitHub Release。

**五、可直接交给执行线程的任务说明**

> 请在 DreamingFish-Updater 当前工作区实施 `D:\Desktop\DreamingFish-Updater\docs\reviews\2026-10-18-remediation-plan.md`。先阅读该计划和其引用的 2026 年 10 月 18 日审查报告，核对当前代码与既有未提交修改，然后按依赖顺序完成 R1–R12、S1–S9 的代码修复、回归测试、性能验证和文档对齐。保留已有数据、签名发布和用户改动，测试使用隔离目录。常规实现选择可自主决定，持续推进到完成，不停在建议层面。每批记录进展与验收证据，最后交付逐项修复结果和真实剩余项。此次完成本地修改与验证，不实施未来专属客户端的新产品功能，不自动部署线上或发布 Release。
