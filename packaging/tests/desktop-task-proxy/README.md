# 桌面后台任务协议夹具

此二进制直接引用生产 `workbench.rs`，只接受回环上游地址。标准输出给测试父进程返回代理地址和本次随机令牌，标准输入关闭或收到一行后释放代理；它不是发行程序。

先构建当前前端和 Java 管理 JAR，然后在仓库根目录运行：

```powershell
cargo build --locked --manifest-path packaging/tests/desktop-task-proxy/Cargo.toml --target-dir admin-desktop/src-tauri/target
node packaging/tests/desktop-task-contract.mjs --java 'D:\Program Files\Java\jdk-21\bin\java.exe' --proxy admin-desktop/src-tauri/target/debug/task-contract-proxy.exe
```

Node 检查使用新建的人工账号、两个项目和当前真实 store。它验证代理后的异步任务返回 202、完成结果可查询、同步页面状态正确、回执丢失后不重发 POST，以及完成后能切换项目。它不使用真实管理目录，不显示桌面窗口，退出时关闭自己启动的 Java 和代理。

此检查已经接入统一验证入口的 Windows 原生步骤。夹具请求有期限；结果保存在本次 `target/desktop-task-contract-<UUID>` 或指定的新 `--output` 目录中。
