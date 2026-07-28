# DreamingFish Update System

面向 Minecraft 整合包服务器的自托管更新系统。V1 已具备完整管理 CLI、本机 Web 管理界面、只读 HTTP 文件服务、事务式更新引擎、Java 8 启动 Agent、JavaFX 玩家端和玩家端自更新。

玩家端只负责在 Minecraft 启动前更新整合包、展示服务器信息和日志。它不接管账号、游戏安装或第三方启动器。普通同步下，玩家可在本机豁免不兼容或不需要的单个文件、整个目录或模组，选择不会上传；服主也可按一级目录或单文件启用强制同步，强制目标不接受本地豁免。服主移除已发布文件时，可以选择让玩家删除该文件，或仅放弃管理并保留玩家副本。V1 不包含黑名单和反作弊。

## 组成

- `management-cli`：服主使用的管理端，包含完整 CLI、轻量 Web 管理界面和服务控制，可在无图形环境运行。
- `management-core`：项目、扫描、签名发布、HTTP 服务和加密备份。
- `bootstrap-agent`：Java 8 字节码的启动门，仅等待本地启动许可。
- `player-app`：带 Java 21 运行时的 Windows JavaFX 桌面应用。
- `update-engine`：签名验证、差异下载、事务安装、恢复和离线许可。
- `protocol`：绑定文件、清单、路径安全、SHA-256 和 Ed25519。

## 构建

需要完整 JDK 21。项目自带 Maven Wrapper，依赖缓存限制在项目目录。

```powershell
.\mvnw.cmd test
.\packaging\build-distributions.ps1 -Version 0.1.13 -SkipLinux
.\packaging\smoke-test-distributions.ps1 -Version 0.1.13 -AdminVersion 0.1.13
```

`-AdminOnly` 只生成管理端发行包，`-PlayerOnly` 只生成玩家端发行包，避免两个独立组件被误用同一个版本号。

发行包冒烟测试会重新解压实际 ZIP，在 `target/` 的临时目录中模拟三个整合包版本、制作历史玩家包、校验玩家程序防篡改和启动 HTTP 服务；成功后自动删除临时数据，不会接触现有 Minecraft 实例或管理端数据。

打包脚本生成以下文件，其中 `<版本号>` 由 `build-distributions.ps1 -Version` 决定：

- `dist/dreamingfish-player-windows-x64-<版本号>.zip`
- `dist/dfs-admin-windows-x64-<版本号>.zip`
- `dist/dfs-admin-linux-x64-<版本号>.zip`

Windows 玩家端和两个管理端包都带 Java 21 运行时。首次生成 Linux 包时，脚本会下载固定版本、校验 SHA-256 后再打包 Temurin Linux x64 JRE。

制作可分发玩家实例时，先发布玩家端程序，再让管理端准备实例：

```text
dfs-admin project binding <项目ID> --instance <实例目录> --platform windows-x64 --release <发布ID>
```

这个命令会写入真实项目绑定、保存首个玩家程序的签名清单、补齐所选不可变发布的托管文件、写入签名分发基线，并自动带入项目封面。只导出一个绑定 JSON 不足以通过 Agent 的启动前校验；从管理端初始化到 PCL 相对路径配置的完整顺序见部署文档。

## 文档

- [最简使用教程](./docs/QUICKSTART.md)
- [部署与发布](./docs/DEPLOYMENT.md)
- [V1 完整设计](./docs/V1-DESIGN.md)
- [中文更新日志](./CHANGELOG.md)
- [领域术语](./CONTEXT.md)
- [架构决策](./docs/adr/)

玩家端视觉以“守望梦屿”网页的电脑端封面为准：标准窗口 `1180×680`，最小窗口 `960×560`，不采用手机端排版。
