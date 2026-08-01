# DreamingFish Player UI（Tauri 2 + Vue 3）

玩家端新界面。窗口外壳由 Tauri（Rust）提供，界面使用 Vue 3 + TypeScript，
更新引擎仍然复用现有 Java 代码，以 sidecar 进程方式运行。

## 结构

```text
player-ui/
├─ src/                 Vue 界面
│  ├─ components/       标题栏、更新区域、抽屉、文件树、对话框等
│  ├─ lib/              类型、格式化、新闻、Markdown、桥接协议
│  ├─ stores/           player 全局状态
│  └─ styles/           player.css（从 JavaFX 样式移植）
├─ public/              字体、封面、新闻资源
└─ src-tauri/           Rust 外壳（窗口控制 + sidecar 进程管理）
```

## 开发

```powershell
cd player-ui
npm install
npm run dev          # 浏览器预览模式（内置演示数据，不需要 Java/Rust）
npm run tauri dev    # Tauri 窗口开发模式
```

`npm run tauri dev` 需要：

- Rust 工具链（rustup + stable MSVC 工具链，含 Visual Studio Build Tools C++ 工作负载）
- WebView2（Windows 10/11 一般已内置）

开发模式下 sidecar 默认路径不存在，需要先构建 Java 侧车并指定位置：

```powershell
cd ..
.\mvnw.cmd -pl player-app -am package -DskipTests
$env:DFS_JAVA = "C:\Path\To\java.exe"          # 任意 Java 21+
$env:DFS_SIDECAR_JAR = "D:\Desktop\DreamingFish-Updater\player-app\target\player-app-0.1.0-SNAPSHOT.jar"
cd player-ui
npm run tauri dev
```

不带参数启动 Tauri 窗口时，Rust 外壳会以 `--preview` 启动 sidecar，直接展示演示数据。
正式运行时由 Bootstrap Agent 传入 `--bootstrap-port`、`--bootstrap-token`、
`--instance`、`--binding` 等参数，Rust 外壳原样转发给 Java sidecar。

## 桥接协议

sidecar 通过标准输出发送单行 JSON 消息（`progress`、`result`、`mods`、`files`、
`logs`、`history`、`confirm-request`、`countdown` 等），前端通过 Tauri 命令
`send_command` 向 sidecar 标准输入写入命令（`retry`、`toggle-mod`、
`toggle-file`、`confirm`、`close`、`quit` 等）。

## 测试与构建

```powershell
npm test             # Vitest 单元测试（格式化、文件树、Markdown、新闻、状态）
npm run build        # vue-tsc 类型检查 + Vite 构建
npm run tauri build -- --no-bundle
```

完整发行包由仓库根目录的 `packaging/build-distributions.ps1 -TauriPlayer` 生成：
打包脚本会构建 Tauri 窗口、Java sidecar 和私有 Java 21 运行时，
并组装成与旧版相同的 `DreamingFishUpdater/app/<版本>/` 目录结构。
