<div align="center">

# DreamingFish Updater

### 安全可靠，自主可控，易于部署的 Minecraft 整合包更新系统

玩家是来玩服务器的，不是来玩资源管理器的。<br>
让玩家照常点击启动，让模组、配置与更新器自己安全到达最新状态。

[![Latest Release](https://img.shields.io/github/v/release/hanhanyu666/DreamingFish-Updater?style=flat-square&label=Release)](https://github.com/hanhanyu666/DreamingFish-Updater/releases/latest)
[![License](https://img.shields.io/github/license/hanhanyu666/DreamingFish-Updater?style=flat-square)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square)](https://adoptium.net/)
[![Player](https://img.shields.io/badge/玩家端-Windows-2EE8DF?style=flat-square)](#下载)
[![Admin](https://img.shields.io/badge/管理端-Windows%20%7C%20Linux-B06CFF?style=flat-square)](#下载)

[下载最新版](#下载) · [开始部署](./docs/QUICKSTART.md) · [完整文档](#文档) · [更新日志](./CHANGELOG.md)

</div>

---

DreamingFish Updater 是一套面向 Minecraft 整合包服务器的自托管更新系统。服主维护一份标准整合包目录，在管理端预览差异并发布；玩家照常使用 PCL、HMCL 等启动器，游戏启动前由玩家端检查并安全更新，完成后继续进入 Minecraft。

它不接管账号、不替换启动器，也不会把玩家电脑粗暴地覆盖成服务器目录。普通内容尊重玩家自己的选择，真正必须一致的内容再由服主明确设置为强制同步。

## 下载

当前版本：**玩家端 0.1.28 / 管理端 0.1.18**。两个组件独立更新，因此版本号不要求一致。所有发行包都自带精简运行环境，使用者不需要另外安装 Java。

| 发行包 | 适用场景 | 下载 |
| --- | --- | --- |
| 玩家端 0.1.28 · Windows x64 | 随整合包交给玩家，负责启动前检查、更新与本地管理 | [下载 ZIP](https://github.com/hanhanyu666/DreamingFish-Updater/releases/latest/download/dreamingfish-player-windows-x64-0.1.28.zip) |
| 管理端 0.1.18 · Windows x64 | 在 Windows 电脑上制作和发布整合包 | [下载 ZIP](https://github.com/hanhanyu666/DreamingFish-Updater/releases/latest/download/dfs-admin-windows-x64-0.1.18.zip) |
| 管理端 0.1.18 · Linux x64 | 部署到 Minecraft 服务器或 VPS | [下载 ZIP](https://github.com/hanhanyu666/DreamingFish-Updater/releases/latest/download/dfs-admin-linux-x64-0.1.18.zip) |

第一次使用建议从[《下载与首次配置》](./docs/QUICKSTART.md)开始，不确定的设置通常直接按回车使用默认值即可。

## 它解决什么问题

传统整合包更新往往意味着重新压缩、重新上传、让玩家手动覆盖，或者要求玩家按顺序安装一串补丁。DreamingFish Updater 把这件事拆成一条更清楚的流程：

1. 服主只维护自己希望发布的文件。
2. 管理端扫描并列出新增、修改和移除，确认以后生成一份带签名的完整目标状态。
3. 玩家启动时只下载自己电脑上真正变化的文件。
4. 下载内容先校验、暂存和备份，全部确认无误以后才替换实例。
5. 更新成功后继续启动 Minecraft；中断或失败时先恢复，不留下半套客户端。

每个发布都是完整目标状态，不是只能接在上一个版本后面的补丁。因此玩家可以从任何带签名基线的正式整合包直接更新到最新版，不必按顺序补齐中间版本。

## 核心能力

| 能力 | 实际效果 |
| --- | --- |
| 差异更新 | 只下载新增或发生变化的对象，大文件支持断点续传和并行下载。 |
| 安全发布 | 项目清单使用 Ed25519 签名，文件使用 SHA-256 与大小校验，公共文件源无法伪造可信版本。 |
| 事务安装 | 先备份、再暂存、最后原子替换；异常退出后读取事务日志恢复原状态。 |
| Web 管理端 | 文件上传、目录树管理、差异预览、发布、回滚、玩家程序和首次部署包都能在网页中处理。 |
| 玩家本地控制 | 普通同步下，玩家可让文件、目录或模组不再受服务器管理，选择只保存在自己的电脑上。 |
| 玩家端自更新 | 更新器程序与整合包内容使用两条独立发布线，程序升级无需重新分发整个客户端。 |
| 个性化界面 | 每个项目都能配置服务器名称、封面、标题、副标题、颜色、公告、新闻、规则和玩法页面。 |
| 离线启动 | 服务器临时离线时，可以复用最近一次已经验证的完整安装；安全错误不会被伪装成普通断网。 |

## 两种同步策略

| 默认同步 | 强制同步 |
| --- | --- |
| 适合大多数模组和配置 | 适合核心模组、关键配置和必须一致的内容 |
| 服务器发布的文件正常更新 | 指定目录或文件必须和服务器保持一致 |
| 玩家额外添加的内容不会被顺手删除 | 范围内冲突内容会先归档，再安全移出实例 |
| 玩家可以在本机调整管理范围或停用非强制模组 | 玩家不能在本机豁免强制目标 |

服主从标准目录移除旧文件时，还可以逐项决定：让玩家端删除，或者只放弃管理并保留玩家已有副本。

## 玩家看到什么

玩家仍然从自己熟悉的 Minecraft 启动器启动游戏。Agent 会拉起 DreamingFish 玩家端，玩家能清楚看到本次检查、下载进度、更新记录和启动状态；检查完成后，Minecraft 照常继续启动。

玩家端同时提供：

- 本次更新、更新记录与运行记录；
- 按目录或单文件管理本地内容；
- 服务器模组与玩家自选模组的独立启停；
- 服主自定义的公告、活动、玩法、规则和世界观页面；
- 与服务器风格一致的名称、封面、强调色和首页介绍。

普通同步下的本地选择不会上传到服务器。

## 管理端工作流

管理端既能在 Windows 上运行，也能部署到无图形界面的 Linux 服务器。日常操作推荐使用 Web 管理页面，交互终端提供相同的核心能力。

1. 下载并解压管理端，完成三步首次使用引导。
2. 创建整合包项目，填写要管理的目录和玩家能够访问的更新地址。
3. 在“管理文件”中上传或导入 `mods`、`config` 等内容。
4. 设置需要保持完全一致的强制同步一级目录或单文件。
5. 扫描变更，确认新增、修改、移除与删除策略，再创建发布。
6. 发布玩家端程序，并生成首次部署包或直接准备完整实例。
7. 按教程把 Agent 接入 PCL 或 HMCL，实际检查一次启动命令。
8. 玩家以后照常启动，客户端会自动检查并更新。

远程 Linux 管理端默认只在 `127.0.0.1:18080` 提供 Web 页面，可通过 SSH 隧道安全访问：

```bash
ssh -N -L 18080:127.0.0.1:18080 用户名@您的服务器地址
```

保持隧道运行，再在自己电脑打开 `http://127.0.0.1:18080/`。如果需要公网 Web 管理，请先注册管理账户，并使用 Caddy 或 Nginx 配置 HTTPS 反向代理。

## 启动器与加载器

- PCL 与 HMCL 均可接入；PCL 优先使用版本目录参数，两者也可以使用版本 JSON 的 `arguments.jvm` 方式。
- Forge、NeoForge 与 Fabric 模组均可读取元数据，用来识别组件并保留玩家选择。
- DreamingFish Updater 不接管正版登录、游戏下载、Java 选择或实例隔离，这些仍由原启动器负责。

启动器参数容易因为实例隔离方式不同而填错，分发前请按[部署文档](./docs/DEPLOYMENT.md)检查最终 Java 启动命令，不要只看设置框里的原始文字。

## 安全边界

- 发布清单经过 Ed25519 签名，玩家实例预先绑定项目公钥。
- 每个文件对象都校验 SHA-256、声明大小与续传响应范围。
- 路径会拒绝绝对路径、`..`、Windows 保留名、大小写冲突和符号链接穿越。
- 同一实例只允许一个更新任务；Minecraft 使用实例时不提交更新或恢复事务。
- 公共文件服务可以使用 HTTP，因为内容真实性由签名与哈希独立保证；正式公网部署仍建议使用 HTTPS 保护访问地址和网络隐私。
- Web 管理页面包含管理能力，不应直接以无密码的 HTTP 暴露到公网。

## 项目组成

| 模块 | 用途 |
| --- | --- |
| `management-cli` | 交互终端、Web 管理界面、账户与服务控制 |
| `management-core` | 项目、扫描、对象库、签名发布、回滚、备份与玩家实例 |
| `bootstrap-agent` | Java 8 字节码的启动引导器，拉起玩家端并等待启动许可 |
| `player-ui` | Tauri + Vue 玩家窗口、个性化页面与本地管理界面 |
| `player-app` | Java 21 更新侧车、日志、本地状态与启动许可控制 |
| `update-engine` | 清单验证、差异下载、事务安装、恢复与玩家端自更新 |
| `protocol` | 项目绑定、发布清单、路径安全、SHA-256 与 Ed25519 |

## 从源码构建

开发环境需要完整 JDK 21、Node.js、Rust 与 Tauri 的 Windows 构建依赖。仓库自带 Maven Wrapper，Maven 缓存默认限制在项目目录。

```powershell
.\mvnw.cmd test

Set-Location .\player-ui
npm test -- --run
cargo test --manifest-path .\src-tauri\Cargo.toml
Set-Location ..

.\packaging\build-distributions.ps1 `
  -Version 0.1.28 `
  -AdminVersion 0.1.18 `
  -TauriPlayer `
  -SkipTests

.\packaging\smoke-test-distributions.ps1 `
  -Version 0.1.28 `
  -AdminVersion 0.1.18
```

发行包烟雾测试会重新解压真实 ZIP，模拟历史发布、签名验证、篡改拒绝、首次部署包与 HTTP 下载服务；测试数据位于 `target/`，成功后自动清理，不会接触现有 Minecraft 实例。

## 文档

- [产品与能力概览](./docs/V1-DESIGN.md)
- [最简使用教程](./docs/QUICKSTART.md)
- [部署、发布与启动器接入](./docs/DEPLOYMENT.md)
- [中文更新日志](./CHANGELOG.md)
- [项目术语](./CONTEXT.md)
- [架构决策记录](./docs/adr/)

## 适用范围

DreamingFish Updater 专注于整合包内容分发、启动前验证和玩家本地管理。它不是 Minecraft 启动器、账号系统、模组平台、黑名单或反作弊程序。

## 开源许可

本项目使用 [GNU Affero General Public License v3.0](./LICENSE)。如果你修改后通过网络向他人提供服务，请同时遵守 AGPL-3.0 对应源代码公开义务。
