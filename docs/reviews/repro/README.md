# 架构审查复现探针

这些探针最初用于复现 2026 年 10 月 1 日审查发现的问题，已在 10 月 2 日转为对修复行为的检查。它们使用断言检查路径拒绝、副本保留、事务恢复、预览失效、单文件签名和响应体超时；对应场景也已进入正式测试。`DELETE_AFTER_RELEASE` 单独展示尚未实现的历史撤回能力，不属于 F1–F8 的修复范围。

探针放在普通测试源码目录之外，复用仓库现有测试夹具和测试类路径，只访问随机创建的测试目录以及本机临时 HTTP 端口；使用临时项目身份和人工构造的文件，不读取服主项目或玩家实例。

## 运行条件

需要 Windows、Java 编译器，以及仓库已缓存的 Maven 依赖。本次验证使用 Java 25.0.3。模拟文件被占用的探针使用 JDK 的 `ExtendedOpenOption.NOSHARE_DELETE`；它属于平台相关 API，仅用于本地故障注入。目录联接探针会在自己的输出目录内创建一个指向同级测试目录的 NTFS junction。

在仓库根目录执行：

```powershell
.\docs\reviews\repro\run-probes.ps1
```

若已经完成当前源码的 Maven 测试或验证构建，可以使用：

```powershell
.\docs\reviews\repro\run-probes.ps1 -SkipBuild
```

每次运行创建新的 `target/architecture-review-2026-10-01/run-<随机标识>`，其中的 `observations.txt` 保存输出。脚本不删除已有输出，也不操作仓库之外的目录。

## 原始观察与修复结果

| 输出标识 | 10 月 1 日原始结果 | 10 月 2 日修复后检查 |
| --- | --- | --- |
| `DELETE_AFTER_RELEASE` | 释放后不能提交删除决定 | 仍为能力缺口，后续独立实现撤回 |
| `RELEASE_ROLLBACK` | 旧基线文件被删除，已释放基线文件保留 | 两种基线的副本均保留 |
| `POLICY_PREVIEW` | 零文件变化的旧预览能发布新的强制目录规则 | 旧预览被拒绝，新预览列出规则变化 |
| `RELEASED_DISABLED_MOD` | 活动副本和停用副本都不存在 | 已交还玩家的副本恢复 |
| `LOCAL_MOVE_FAILURE` | 前一个模组已移走，存储文件没有索引 | 原文件恢复，不留下无索引副本 |
| `JUNCTION_ESCAPE` | 允许写入实例外 | 拒绝联接，实例外没有写入 |
| `STATIC_SIGNATURE_RACE` | 跨切换读取产生 `INVALID_SIGNATURE` | 使用完整 `.signed` 载荷验证新版本 |
| `STALLED_BODY` | 超时和取消均未结束读取 | 阻塞读取在期限内结束 |

静态签名探针模拟静态托管更新前后的读取顺序；内置 HTTP 文件服务把签名放在同一个响应头中，不受这个特定问题影响。下载探针会在打印观察后放行响应体，让工作线程正常退出。
