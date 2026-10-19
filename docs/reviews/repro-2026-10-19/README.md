# 2026 年 10 月 19 日重构复核材料

对应[独立复核报告](D:/Desktop/DreamingFish-Updater/docs/reviews/2026-10-19-remediation-review.md)。生产源码标识 `96d55f312c5e55a5da8a9323a8db0e9316672df491afbf54cb65802bc59c1d1e`。这些探针只用于复现本轮观察，不修改生产源码；其中断言描述的是缺陷，修复后需要转为正式正确性回归。

**Java 探针**

先构建两套前端并执行 Maven verify，确保当前生产类、测试夹具与 Surefire classpath 可用。在仓库根目录执行：

```powershell
.\docs\reviews\repro-2026-10-19\run-java-probe.ps1 -JdkHome 'D:\Program Files\Java\jdk-21'
```

脚本创建新的 `target/review-2026-10-19/java-<UUID>` 与 classes 目录。三个场景分别为冲突上传 ID、事务专用临时文件的部分内容状态、活动执行器和独立查询子进程。测试内容与密钥均由夹具生成，不接触真实数据。

`PARTIAL_COPY` 是构造复制中断后磁盘状态，再调用提交和恢复入口；输出里的 `restartRecoveryBlocked` 表示重新进入恢复路径仍被拒绝，不代表本场景实际重启过 JVM 或进行硬断电。`LIVE_TASK` 则实际启动独立 Java 子进程查询，父进程任务始终保持运行。结果见 [java-results.txt](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-19/java-results.txt)。

**Rust 代理探针**

```powershell
cargo run --offline --locked --manifest-path docs/reviews/repro-2026-10-19/rust-probe/Cargo.toml --target-dir admin-desktop/src-tauri/target
```

需要已有相应 Cargo 缓存。探针用 `#[path]` 直接引入当前 `workbench.rs`，创建回环上游，比较同一请求直连和经过真实代理后的请求头。它不启动桌面窗口或真实 Java 管理服务，不显示代理令牌。结束时关闭本次监听与线程。最终输出见 [proxy-results.txt](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-19/proxy-results.txt)。

**前端 store 探针**

```powershell
node docs/reviews/repro-2026-10-19/workflow-probe.mjs
```

使用已有 player-ui 的 esbuild 打包真实 store，在测试响应中返回同步 200，确认成功结果返回后是否仍留下 QUEUED。结果见 [workflow-results.txt](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-19/workflow-results.txt)。它与代理探针相互补充，不是实际 WebView 点击测试。

**其他证据**

`java-test-results.json` 为本轮最终 Surefire 汇总；两份 `*-npm-audit.json` 为本轮官方 registry 返回的当前锁文件结果。常规构建、Rust 与发布预演日志保留在仓库 `target/review-2026-10-19-*.log`。生产源码未修改，测试产物和人工目录保留供检查。
