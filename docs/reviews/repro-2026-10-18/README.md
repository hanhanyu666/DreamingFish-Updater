# 2026 年 10 月 18 日审查验证材料

对应[本次完整审查报告](D:/Desktop/DreamingFish-Updater/docs/reviews/2026-10-18-comprehensive-review.md)，代码基线 `b8793bbae74ffd57be176bfd153cac0e38bc472c`。这里的缺陷探针用于展示当时错误行为，修复之后应调整为期望正确结果的回归测试，不能把“缺陷依旧复现”视作产品测试通过。

探针只在仓库 `target/` 下创建新的人工数据目录，使用本机回环地址，不连接真实管理项目。它们保留生成的数据供检查。两轮资源测量合计至少需要约 1.2 GiB 额外空间，桌面验收另复制一套构建产物。

**已有测试与构建。** 本次在 JDK 21.0.9、Node 24.12.0、Cargo 1.97.1 下执行了 Java verify、两端前端测试与构建、两端 Rust 测试。执行顺序应先生成两端前端资源，再执行 Maven，最后构建桌面 release；脚本依赖仓库已有 Node、Maven 和 Rust 依赖。

Java 汇总见 [java-test-results.json](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/java-test-results.json)。完整构建日志保留在仓库 `target/review-2026-10-18-*.log`，没有复制进报告目录。

**重新验证旧问题。** 运行仓库已有隔离探针，它们会编译并调用当前 class 和当前前端 store：

```powershell
.\docs\reviews\repro-2026-10-14\run-probes.ps1 -JdkHome 'D:\Program Files\Java\jdk-21'
```

需先执行 Maven `verify`，使当前生产类、测试夹具与 Surefire classpath 可用。本次输出保存于 [previous-probes.txt](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/previous-probes.txt)。旧脚本仍把临时数据放进以 10 月 14 日命名的 target 子目录；本次结果是重新运行所得，未覆盖旧报告的观察文件。

**迁移与便携探针。** `rust-probe` 用 `#[path]` 直接引入当前生产 `profiles.rs`、`migrate.rs`，不复制或修改业务实现。传入的目录必须是仓库下全新绝对路径：

```powershell
$probeRoot = Join-Path (Get-Location) ('target\review-2026-10-18\migration-' + [guid]::NewGuid())
cargo run --offline --locked --manifest-path docs/reviews/repro-2026-10-18/rust-probe/Cargo.toml --target-dir admin-desktop/src-tauri/target -- $probeRoot
```

四个场景分别是配置保存失败、当前配置损坏但保留有效 previous、旧 home 被文件锁占用、迁移后整个目录换位置。结果见 [migration-results.txt](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/migration-results.txt)。关于 `data_place()` 最终选择哪一个根目录、连接入口如何使用 home 的判断，还结合了报告引用的生产调用代码。

**管理核心资源测量。** 需要 Python 3、psutil 和当前 `management-cli/target/dfs-admin.jar`。默认 JDK 路径为 `D:\Program Files\Java\jdk-21`，可在本次命令环境中设置 `REVIEW_JDK`。

```powershell
python .\docs\reviews\repro-2026-10-18\resource-probe.py
$env:REVIEW_JAVA_FLAGS = '-Xms64m -Xmx256m'
python .\docs\reviews\repro-2026-10-18\resource-probe.py
Remove-Item Env:REVIEW_JAVA_FLAGS
```

每次生成新的 256.125 MiB 源文件与管理目录。脚本启动隐藏 Java 子进程，结束时用 stdin 关闭自己启动的服务，必要时只终止自己的子进程。结果见 [resources-default.json](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/resources-default.json) 与 [resources-256m.json](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/resources-256m.json)。峰值每 20 毫秒采样；I/O 为进程计数，不是物理磁盘计量。

**隐藏桌面资源测量。** 先运行当前桌面 release 构建：

```powershell
cargo build --release --locked --features custom-protocol --manifest-path admin-desktop/src-tauri/Cargo.toml
python .\docs\reviews\repro-2026-10-18\desktop-resource-probe.py
```

还需已有的 `admin-desktop/src-tauri/resources/core/runtime` 精简 Java。探针把新 EXE、新管理 JAR、Bootstrap JAR 与该运行时复制到新的 target 目录，使用 `--background-check --desktop-home`，不显示或聚焦窗口。60 秒超时只终止此次启动且已记录身份的子进程；已打开的另一份桌面程序可能因单实例机制让此次测试无法开始，应将缺失结果报告为未完成。

本次沙箱内检查超时，沙箱外同一检查成功。成功输出见 [desktop-resources.json](D:/Desktop/DreamingFish-Updater/docs/reviews/repro-2026-10-18/desktop-resources.json)。其中工作集为多个进程相加，包含共享页面重复计数；private bytes 为私有提交内存，均不能当作长期空闲物理内存占用。

**依赖核对。** 两份 `*-npm-audit.json` 是当日使用官方 npm registry 对当前锁文件的查询结果。未执行 audit fix，未改版本。漏洞计数包含依赖传播，独立公告与当前入口可达性需参照报告解释。
