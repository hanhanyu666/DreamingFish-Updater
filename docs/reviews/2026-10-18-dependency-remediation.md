# 2026-10-18 依赖修复与剩余公告

最终复核执行于 2026-10-19；旧审计与镜像错误结果另行保留。

两套前端使用官方 npm registry 重新审计；管理端原有 2 high、玩家端原有 4 high / 2 moderate，本轮最终均为 0。原镜像的 audit API 曾返回 404，那次错误单独保留，不当作审计成功。原始 JSON 位于工作区 `target/remediation-2026-10-18/npm-*-verified.json`；锁文件和行为回归共同验证版本，不使用未经审查的 `audit fix --force`。

| 依赖链 | 本轮处理 | 验证与影响 |
| --- | --- | --- |
| Vue → server-renderer（两端） | Vue 统一精确固定 3.5.44 | SSR 属性名含 CR 的实际渲染回归；全部页面测试与构建通过 |
| 玩家 Vitest / mocker | 精确固定 Vitest 4.1.12 | 77 项前端测试；这是开发工具入口，不是公开生产服务 |
| nanoid | 锁定兼容链的 3.3.20 | 零长度 / 边界输入在有超时的独立子进程回归 |
| source-map-js | 1.2.2 | 大偏移输入的有界子进程回归 |
| Maven Jackson BOM | 2.18.2 → 2.18.11 | Windows / Linux 全模块回归；不改变签名协议和旧清单读取 |
| 管理桌面 reqwest → rustls | 0.23.45 → 0.23.46 | Rust 网络与 cookie / 代理回归，保留证书及主机验证 |
| tauri-plugin-updater → zip 4.6.1 | 移除不用的 ZIP 默认特性，保留 rustls-tls / system-proxy | 当前更新格式是签名 NSIS EXE；未改变签名密钥和格式，不声称上游 ZIP 已修复 |

公告依据为 [Vue SSR 属性处理](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6)、[Vitest](https://github.com/advisories/GHSA-82fw-gwwq-j7x9)、[nanoid](https://github.com/advisories/GHSA-2v37-7h3g-55p8)、[source-map-js](https://github.com/advisories/GHSA-68fv-2mgg-jv7q)及 OSV / RustSec 的版本范围。部分公告页面的 patched 字段滞后，实际 npm 范围与本地渲染回归一起记录，不能单凭一个页面宣布安全。

`packaging/check-dependency-advisories.py --artifact-root <新构建根> --output <当前仓库target下目录>` 只发送公开的包名 / 版本。最终检查 538 个去重坐标：两份 Cargo.lock 的所有 registry 平台依赖，以及新构建管理 / 玩家 / Bootstrap JAR 内可识别的生产 Maven 坐标。Java / Rust 可修复公告已从本次结果移除，仍有 10 个包、11 个公告标识（glib 的 GHSA 和 RustSec 是同一问题），必须保留剩余状态。

| 剩余包 | 公告 / 触发范围 | 当前边界与阻碍 |
| --- | --- | --- |
| glib 0.18.5 | [RUSTSEC-2024-0429](https://rustsec.org/advisories/RUSTSEC-2024-0429.html)，VariantStrIter 迭代实现不健全；0.20.0 修复 | Tauri Linux GTK 传递依赖；当前正式桌面目标为 Windows，Linux 本轮验证 Java 管理端。升级 glib 需要 GTK / Tauri 上游兼容迁移，不能对当前图谱强塞第二份较新 crate 当作修复 |
| proc-macro-error 1.0.4 | [RUSTSEC-2024-0370](https://rustsec.org/advisories/RUSTSEC-2024-0370.html)，停止维护 | 构建期宏依赖，由上游图谱引入；当前分支没有兼容修复版本 |
| unic-char-range / common / char-property / ucd-version / ucd-ident 0.9.0 | RUSTSEC-2025-0075 / 0080 / 0081 / 0098 / 0100，停止维护 | Tauri URL / Unicode 处理图谱；没有可验证的同链替代，保留上游跟踪 |
| zvariant 5.15.0、zvariant_derive 5.15.0、zbus_names 4.3.4 | RUSTSEC-2026-0370 / 0372 / 0371，停止维护 | Linux D-Bus 图谱；公告没有修复版本，当前 Windows 原生包不包含这条 Linux 运行路径 |

原始 queries / results / details 在 `target/remediation-2026-10-18/dependency-advisories-complete/`。本检查不覆盖 JDK 自身公告、全部 Maven 构建插件、系统 WebView2 / GTK 运行时或供应链真实性证明。最终结论是“npm 已归零、可兼容修复已落实、上游剩余项明确”，不是所有平台所有依赖零风险。
