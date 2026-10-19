# 2026 年 10 月 14 日审查复现材料

这些探针对应[完整代码审查报告](D:/Desktop/DreamingFish-Updater/docs/reviews/2026-10-14-comprehensive-review.md)，针对提交 `6920cc4`。它们记录当时实际错误行为；业务代码修复后，部分断言或预期输出需要相应更新，不能把“仍然复现缺陷”当作正确性测试通过。

**运行缺陷探针**

先完成仓库 Maven `verify`，使测试夹具、class 文件与 Surefire classpath 可用。需要完整 JDK 21、Node，以及玩家和管理前端已经安装的依赖。PowerShell 中执行：

```powershell
.\docs\reviews\repro-2026-10-14\run-probes.ps1 -JdkHome 'D:\Program Files\Java\jdk-21'
```

- `EngineReviewProbe.java`：游戏锁被持有时的离线移除，以及同长度、同修改时间的文件变化漏检。
- `ManagementReviewProbe.java`：SKIP 拒绝、导入副本保留、64 个慢读取连接使公共健康检查排队。只访问回环监听，结束时关闭全部测试连接。
- `workbench-probe.mjs`：打包真实 store，在模拟请求下复现 SKIP、错误重试重新上传和项目响应乱序。

输出位于仓库 `target/review-2026-10-14/run-<随机 ID>/`。测试会写入人工实例、生成 32 MiB 下载对象以及产生可检查的归档，不会读取现有管理数据。已保存的 `observations.txt` 是本次运行输出摘要。

**运行资源测量**

需要 Python 3 与 psutil，以及已经构建的 `management-cli/target/dfs-admin.jar`：

```powershell
python .\docs\reviews\repro-2026-10-14\resource-probe.py
```

脚本默认使用 `D:\Program Files\Java\jdk-21`，可在运行该进程前设置 `REVIEW_JDK` 指向另一个 JDK。它启动隐藏的 Java desktop 子进程，使用随机测试 home 和回环端口，创建 256.125 MiB 的人工源文件，测量列表、扫描、两次发布与空闲状态，最后通过 stdin 请求关闭自己启动的服务。

脚本需要约 0.6 GiB 可用空间用于源文件、对象、数据库和结果；不运行游戏、WebView 或真实公网下载。结果和人工数据保存在 `target/review-2026-10-14/resources-<随机 ID>/`，不会自动删除。`resource-results.json` 保存本次结果；它是单次 Windows 本机测量，不是最低配置或峰值容量承诺。

本次基线验证：JDK 21 与 JDK 25 的 Maven verify 均成功；最终 Java XML 统计 266 项通过，玩家前端 74 项、管理前端 15 项、管理 Rust 14 项、玩家 Rust 9 项通过。探针与这些既有测试的结果应分别理解。
