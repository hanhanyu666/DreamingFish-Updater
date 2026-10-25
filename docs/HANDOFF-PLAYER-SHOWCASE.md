# 玩家端新版界面：交接文档

本文档交接"玩家端更新器新版界面"这项工作。前端（`player-ui`、`admin-desktop` 的界面部分）由 Claude 负责，后端（Java 协议与服务、玩家端引擎、Tauri Rust 命令）交给另一位开发者完成。

- 设计稿画布：<https://claude.ai/artifact/LcDWLweLoPtEZy15MZNU2M>（首页启动舞台、公告、服务器介绍、画廊、更新与本地管理、更新区方案）
- 用语遵循 `CONTEXT.md`：玩家端程序叫"玩家端更新器"，不要叫"启动器"。
- 本文所有改动**都还没有提交**。提交时按 `AGENTS.md` 的标题格式，玩家端和管理端版本号需要一起决定。

---

## 1. 目标

1. 首页改成"启动舞台"：全屏轮播（服主配置的焦点图）、右侧服务器卡（Logo、MOTD、版本、在线人数、延迟、地址、社区链接）、下一场活动倒计时、右下角更新区胶囊。
2. 服主可以新增画廊页、内容块页（服务器介绍、规则、团队、问答等），公告页改成杂志式排版。
3. "更新与本地管理"抽屉改成带左侧分类栏的面板（原有功能一项都不能少）。
4. 给服主更大的自定义权限，但必须在规则内：所有展示内容都用一套固定的 JSON 格式描述，玩家端只用内置组件渲染。服主可以手写，也可以让 AI 按规则生成。规则文档、JSON Schema 和一份完整示例模板放在 `docs/showcase/`（见第 6 节）。
5. 玩家端更新器的本职（ADR 0003：专注更新）不变。展示内容加载失败时绝不能影响更新和启动。

---

## 2. 进度总览

| 部分 | 状态 |
|---|---|
| 更新区胶囊游水线 + 梦鱼小鱼 | ✅ 已完成，测试和构建都通过，浏览器里逐个状态看过 |
| 新数据格式（TypeScript 类型） | ✅ 已定稿（见第 4 节），后端按这个实现 |
| 玩家端数据层（store、bridge、示例数据） | ✅ 已完成，可以编译 |
| 首页启动舞台：轮播、服务器卡、活动卡、标题栏玩家信息、音乐卡移到标题栏、主题变量 | ✅ 已完成，浏览器里看过，78 个测试通过 |
| 画廊页、内容块页 | ✅ 已完成（`GalleryPage.vue`、`BlocksPage.vue`、`ImageLightbox.vue`，`ContentPages.vue` 按布局分发） |
| 公告杂志排版 | ✅ 已完成（`NewsPage.vue`：头条加网格；文章页右侧是目录和更多公告） |
| 首页细节对齐设计稿（右栏 372 宽、颗粒质感、标题栏标识块、像素头像、音量条） | ✅ 已完成 |
| 展示内容规范文档：`docs/showcase/README.md`、`showcase.schema.json`、`example-whalesong.json` | ✅ 已完成 |
| 规则校验模块 `showcaseRules.ts`（导入校验、玩家端兜底清理、给 AI 的提示词） | ✅ 已完成（第 12 节） |
| 更新与本地管理面板改版（左侧分类栏） | ✅ 已完成（第 12 节） |
| 首页按设计稿逐项对齐（外框、暗角、标题栏、文案、切换器、312 宽右栏、毛玻璃卡片、胶囊展开态、衬线标题） | ✅ 已完成（第 12 节） |
| 管理端编辑界面（首页焦点与活动、服务器卡片、主题、画廊页、内容块页、展示图片、导入导出 JSON） | ✅ 已完成，类型检查、构建和单元测试通过；**尚未连真实管理服务联调**（第 12 节） |
| 后端（第 5 节全部） | ✅ 已实现并通过自动化验证，接口补充和结果见第 11 节 |
| 第四轮：所有页面逐张对照设计稿重做（公告、介绍、画廊、面板各分类、确认框、标题栏、像素字体） | ✅ 前端已完成（第 13 节） |
| 第四轮新增展示字段的后端支持 | ✅ 已完成协议、校验、页面封面发布与终端字段保留；自动化联调和验证见第 14 节 |
| 第五轮：更新卡片各状态与更新信息、全部文字改用鸿蒙字体、去掉标题栏和页头的分隔线、样式文件拆分 | ✅ 前端已完成，只改前端，后端无需跟进（第 15 节） |
| 版本号改为玩家 0.3.0 / 管理 0.3.0，并部署到服主的服务器 | ✅ 已部署并发布玩家端（第 16 节）；**未提交、未发 GitHub Release** |
| 焦点图和活动不限数量（前后端规则、Schema、文档、编辑器） | ✅ 代码已改、测试通过；**服务器上还是部署时的版本，尚未包含这一项**（第 16.4 节） |
| 改动页显示玩家端设计改动；服务器卡片不再依赖地址；服主自选首页地址位置 | ✅ 代码已改、测试通过；**未部署**，玩家端要用高于 0.3.0 的版本发布（第 17 节） |
| 左上角图标可换；右侧卡片按实际高度收起；卡片随窗口变宽（最宽 420px）；MOTD 换行 | ✅ 代码已改、测试通过；**未部署**（第 18 节） |
| 版本号改为玩家 0.3.1 / 管理 0.3.1（包含第 16.4、17、18 节） | ✅ 已部署到服主的服务器并发布玩家端 0.3.1；**未提交、未发 GitHub Release**（第 18.6、18.7 节） |
| 启动音乐每次打开会从头重播三次 | ✅ 已修复，并发布为玩家端 0.3.2，管理端仍是 0.3.1（第 19 节） |
| 提交、推送并发布 GitHub Release v0.3.1 | ✅ 提交 `f628eba`，标签 `v0.3.1`，Release 已发布并标为最新（第 20 节） |

---

## 3. 已经改过的文件（前端）

### 3.1 更新区：胶囊游水线（已完成）

| 文件 | 改动 |
|---|---|
| `player-ui/src/components/UpdateArea.vue` | 重写成胶囊。收起时只有一行状态加一条水线，小鱼的位置就是进度，终点是一颗星星。去掉了"检查 / 下载 / 安装 / 校验"步骤条。完成且有本地变化时，或者出错时，自动展开；玩家手动收起或展开后，到下一次状态变化前以玩家的选择为准。展开后有：完整的当前说明、本次更新摘要（悬停显示文件明细，点击进入本次更新）、备份/保留/自选模组提示、查看备份与恢复、出错时的打开目录 / 重试 / 仍然启动、底部三个入口（更新记录、运行记录、本地文件）。"保持打开"按钮移进胶囊，带剩余秒数和缩短的倒计时线。点了之后说明里会加一句"窗口会保持打开"。 |
| `player-ui/src/components/MoonFish.vue` | 新增。管理端同款月眠鱼（轮廓来自 `admin-desktop/src/brand-logo.ts`），五种表情：`swim` 更新中，闭眼游动吐泡泡；`happy` 完成，笑眯眯带星星；`worried` 出错，睁眼冒汗；`sleep` 离线，睡着冒 Zz；`wow` 被点击（"摸鱼"），跳一下冒爱心。 |
| `player-ui/src/styles/player.css` | 旧的更新区样式（`.update-area` 到 `.archive-button:hover`）整段换成胶囊、水线、小鱼动画的样式。删掉了只被旧更新区使用的 `.action-row` 和 `.error-status`。宽度从 430 改为 420，所有说明文字改为换行完整显示，不再用省略号截断。 |
| `player-ui/src/App.vue` | 顶部那条启动提示（`launch-notice-layer`）只在胶囊看不到时显示，也就是不在首页或抽屉打开时，避免首页出现两个倒计时。 |
| `player-ui/src/components/__tests__/UpdateArea.test.ts` | 按新结构重写：检查中小鱼等待、完成后到达终点、下载进度、出错时的动作、展开收起、点小鱼。 |

### 3.2 新界面的数据层（已完成）

| 文件 | 改动 |
|---|---|
| `player-ui/src/lib/types.ts` | 新增第 4 节的全部类型；`SidecarMessage` 加 `server-status`；`SidecarCommand` 加 `refresh-server-status`；`AdminPreviewPayload` 加 `images`；`DEFAULT_BRANDING` 补上新字段的空值。 |
| `player-ui/src/lib/showcase.ts` | 新增。页面布局判断 `pageLayout`、首页轮播 `homeSlides`（没有配置焦点图时，用"欢迎标题 + 最新 3 篇公告"）、按钮目标检查 `usableAction`、下一场活动 `nextEvent`、倒计时与日期文字、MOTD 的 § 颜色码解析 `parseMotd`（支持 §x 十六进制颜色，太暗的颜色会自动提亮）、社区链接名称。 |
| `player-ui/src/lib/homeCarousel.ts` | 新增。轮播的共享状态（当前第几张、进度），背景层和文案层共用。 |
| `player-ui/src/lib/clipboard.ts` | 新增。复制文字，旧版 WebView 不支持时退回选区复制（原先在 `App.vue` 里）。 |
| `player-ui/src/lib/icons.ts` | 加了日历、信号、群组、聊天、地球、电视、链接、外链、关闭、定位、图片等图标。 |
| `player-ui/src/lib/previewShowcase.ts` | 新增。浏览器预览用的完整示例内容（鲸歌生存服）：4 张焦点图、服务器卡、活动、公告页、内容块页、画廊页、模拟的在线状态。示例图片由默认背景裁切调色而来，运行时在浏览器里生成，不额外打包图片。 |
| `player-ui/src/lib/bridge.ts` | 接口加 `presentationImageUrl(fileName)`：Tauri 端调用新命令 `read_presentation_image`，浏览器模拟端返回示例图片。模拟端改用示例内容，并模拟 `server-status`（先 CHECKING，0.7 秒后 ONLINE）以及 `refresh-server-status` 命令。 |
| `player-ui/src/stores/player.ts` | 状态加 `serverStatus`、`presentationImages`；处理 `server-status` 消息；`displayBranding` 透传 `spotlights` / `serverCard` / `events` / `theme`；新增 `imageUrl(source)`（上传文件按需读取并缓存，失败时用公开地址）、`followLink(target)`（打开页面、公告或外链，阅读内容时保持窗口打开）、`refreshServerStatus()`；管理端预览会合并 `payload.images`，并清空在线状态。 |
| `player-ui/src/components/HomeBackdrop.vue` | 新增。首页背景层：有图片的焦点图交叉淡入淡出；没有图片的焦点图透出项目背景。 |
| `player-ui/src/components/HomeHero.vue` | 新增。首页左侧文案：标签、眉题、标题、简介、按钮，以及底部带进度的切换器。每 7 秒切换，鼠标悬停或键盘聚焦时暂停，离开首页时暂停，欢迎页沿用原有的大标题样式。 |

### 3.3 首页启动舞台（已完成）

| 文件 | 改动 |
|---|---|
| `player-ui/src/components/ServerCard.vue` | 新增。Logo（上传文件，其次 Ping 得到的 favicon，都没有时用项目名首字）、名称与版本标签、彩色 MOTD（最多两行）、在线状态行（在线人数、延迟信号、刷新按钮；收到第一条 `server-status` 前不显示；管理端预览里显示提示文字）、地址复制、社区按钮（有地址就打开，没有就复制并提示"已复制"）。没有地址、链接和状态时整张卡不显示。 |
| `player-ui/src/components/EventCard.vue` | 新增。下一场活动或正在进行的活动，带开始时间和每秒刷新的倒计时；有按钮时整张卡可以点击。 |
| `player-ui/src/App.vue` | 去掉旧的 `identity-pane`、`player-identity`（含地址复制）、`home-latest-news`，以及它们的窗口高度监听；加入 `HomeBackdrop`、`HomeHero`；右侧新增 `.home-rail`，依次是服务器卡、活动卡、更新区胶囊。 |
| `player-ui/src/components/TitleBar.vue` | 右侧加当前玩家信息（首字头像加名字）。有音乐时留出 `.title-music-slot`，给音乐卡腾位置。 |
| `player-ui/src/stores/player.ts` | `setBranding` 会把 `theme` 转成 `--dfs-card-radius`、`--dfs-card-alpha`、`--dfs-card-blur` 三个 CSS 变量。 |
| `player-ui/src/styles/player.css` | 删掉旧首页的身份区、玩家卡、最新公告卡样式（保留欢迎大标题的三个样式）；新增首页舞台、服务器卡、活动卡、标题栏玩家信息样式；音乐卡在所有页面都放在窗口按钮左边，改成胶囊形；更新区从绝对定位改为贴在 `.home-rail` 底部。胶囊展开时，服务器卡只保留标题和在线状态，活动卡隐藏；窗口高度不超过 640 时，服务器卡整张隐藏。 |
| `player-ui/src/components/__tests__/TitleBar.test.ts` | 音乐卡位置的断言改成新规则。 |
| `player-ui/src/components/TitleBar.vue`（第二轮） | 品牌名前加标识块（`brandName` 首字），玩家头像改成按名字生成的 5×5 像素头像。 |
| `player-ui/src/components/MusicPlayer.vue` | 收起时的音符图标换成 4 根音量条，播放时跳动。 |
| `player-ui/src/lib/pixelFace.ts` | 新增。按名字生成固定的像素头像，标题栏和团队成员卡共用。 |
| `player-ui/src/styles/player.css`（第二轮） | 右栏改为 372 宽；`.image-shade` 加上颗粒质感和顶部暗带；新增 `.brand-tile`、`.music-player-eq`。 |

### 3.4 页面（已完成）

| 文件 | 改动 |
|---|---|
| `player-ui/src/components/BlocksPage.vue` | 新增。内容块页：页头、吸顶的页面内导航（跟随滚动高亮），以及 10 种内容块：TEXT、CARDS、STEPS、LIST、TEAM、FAQ、STATS、CALLOUT、IMAGE、GALLERY。色调、列数、图标都按规范兜底：不认识的值退回默认，条目最多取 24 项，按钮最多 3 个，指向不存在页面的按钮直接隐藏。 |
| `player-ui/src/components/GalleryPage.vue` | 新增。画廊页：分类筛选，7 格一循环的拼贴网格，点击打开大图。 |
| `player-ui/src/components/ImageLightbox.vue` | 新增。大图浏览：上一张、下一张、Esc 关闭，按键只在打开时监听；显示作者、坐标和说明。 |
| `player-ui/src/components/NewsPage.vue` | 改成杂志式排版：最新一篇做成带封面的头条，其余是网格卡片；文章页右侧有目录（取 Markdown 的二、三级标题）和"更多公告"，窗口宽度不超过 1040 时右栏隐藏。 |
| `player-ui/src/components/ContentPages.vue` | 按 `pageLayout(page)` 分发到公告页、画廊页、内容块页或 Markdown 页。 |
| `player-ui/src/lib/icons.ts`（第二轮） | 补齐规范里的内容块图标：star、shield、sparkle、sword、pickaxe、heart、home、gift、trophy、book。 |
| `player-ui/src/styles/player.css`（第三轮） | 末尾（`prefers-reduced-motion` 之前）新增内容块页、画廊、大图浏览、杂志公告页的样式。 |

### 3.5 规范文档与设计稿

| 文件 | 说明 |
|---|---|
| `docs/showcase/README.md` | 给服主看的规则说明，含给 AI 的提示词。 |
| `docs/showcase/showcase.schema.json` | JSON Schema，字段、枚举、长度上限都以它为准。 |
| `docs/showcase/example-whalesong.json` | 完整示例模板，由 `previewShowcase.ts` 导出，活动时间改成固定日期，图片改成占位文件名。 |
| `docs/design/player-showcase/` | 最开始的设计稿源文件，详见第 10 节。 |

### 3.6 当前验证状态

以最新一轮为准，见第 12.4 节。

---

## 4. 数据格式（前后端契约）

所有新字段都**可选**。旧的展示数据没有这些字段时，玩家端照旧显示。字段名与 `player-ui/src/lib/types.ts` 完全一致，Java 记录请保持同名，JSON 编解码按名字对应。

### 4.1 `Branding` 新增字段

```ts
spotlights?: PlayerSpotlight[]   // 首页焦点图，最多 6 张
serverCard?: PlayerServerCard    // 首页服务器卡
events?: PlayerEvent[]           // 活动，最多 10 个，玩家端只显示下一场没结束的
theme?: PlayerTheme              // 圆角与卡片质感
```

### 4.2 共用结构

```ts
// 服主图片：上传文件（随发布下发，玩家端本地读取）或公开 http(s) 地址；两者都填时用文件
interface PlayerImageSource { imageFile?: string | null; imageUrl?: string | null }

// 按钮去向：服主的某个页面、某篇公告，或外部链接
interface PlayerLinkTarget {
  label: string;                         // 按钮文字，空时玩家端用默认文字
  kind: "PAGE" | "ARTICLE" | "URL";
  pageId?: string | null;                // PAGE / ARTICLE
  articleId?: string | null;             // ARTICLE
  url?: string | null;                   // URL，只接受 http(s)
}
```

### 4.3 首页

```ts
interface PlayerSpotlight extends PlayerImageSource {
  id: string;
  tag: string;        // 小标签，如“新赛季”
  eyebrow: string;    // 眉题，如“SEASON 03 · NOW OPEN”
  title: string;      // 必填，空标题的焦点图不显示
  lead: string;
  action?: PlayerLinkTarget | null;
}

interface PlayerServerCard {
  enabled: boolean;
  showStatus: boolean;            // 是否让玩家端 Ping 服务器
  versionLabel?: string | null;   // 覆盖 Ping 得到的版本，如“1.20.1 · Fabric”
  motd?: string | null;           // 覆盖 Ping 得到的 MOTD，允许 § 颜色码
  logoFile?: string | null;       // 覆盖 Ping 得到的服务器图标（上传文件名）
  links?: PlayerCommunityLink[] | null;   // 最多 4 个
}

interface PlayerCommunityLink {
  id: string;
  kind: "QQ" | "DISCORD" | "KOOK" | "BILIBILI" | "WEBSITE" | "OTHER";
  label: string;
  url?: string | null;        // 有地址就打开
  copyText?: string | null;   // 没有地址时点击复制，比如 QQ 群号
}

interface PlayerEvent {
  id: string;
  title: string;
  startsAt: string;           // ISO 8601 带时区，如 2026-10-24T20:00:00+08:00
  endsAt?: string | null;     // 没填时按开始后 2 小时算结束
  action?: PlayerLinkTarget | null;
}

interface PlayerTheme {
  corner?: "ROUND" | "SOFT" | "SQUARE" | null;   // 默认 ROUND
  surface?: "GLASS" | "SOLID" | "CLEAR" | null;  // 默认 GLASS
}
```

### 4.4 内容页（`contentPages[]` 新增字段）

```ts
layout?: "MARKDOWN" | "ANNOUNCEMENTS" | "GALLERY" | "BLOCKS" | null
// 缺省时：announcementPage 为 true 是 ANNOUNCEMENTS，否则是 MARKDOWN（兼容旧数据）
gallery?: PlayerGalleryItem[] | null   // GALLERY 页，最多 60 张
blocks?: PlayerBlock[] | null          // BLOCKS 页，最多 20 块

interface PlayerGalleryItem extends PlayerImageSource {
  id: string; title: string; author: string; category: string;
  caption?: string | null;
  location?: string | null;   // 坐标或地点
}

interface PlayerBlock extends PlayerImageSource {
  id: string;
  kind: "TEXT" | "CARDS" | "STEPS" | "LIST" | "TEAM" | "FAQ" | "STATS" | "CALLOUT" | "IMAGE" | "GALLERY";
  title?: string | null;
  lead?: string | null;
  navLabel?: string | null;   // 页面内导航上的名字，空时用 title
  tone?: "DEFAULT" | "ACCENT" | "SECONDARY" | "WARM" | "MUTED" | null;
  columns?: number | null;    // 1–4，CARDS / TEAM / STATS / GALLERY 用
  markdown?: string | null;   // TEXT / CALLOUT 用
  items?: PlayerBlockItem[] | null;       // 最多 24 项
  actions?: PlayerLinkTarget[] | null;    // 最多 3 个
}

interface PlayerBlockItem extends PlayerImageSource {
  id: string;
  title: string;
  text?: string | null;
  meta?: string | null;      // TEAM 是职务，STATS 是补充说明，GALLERY 是作者
  icon?: PlayerIconName | null;
  action?: PlayerLinkTarget | null;
}
// PlayerIconName：star shield users chat globe calendar pin image sparkle sword pickaxe heart
//                 home gift trophy book clock check info signal
```

每种内容块用到的字段：

| kind | 显示 | 用到的字段 |
|---|---|---|
| TEXT | 一段 Markdown | title、lead、markdown、actions |
| CARDS | 卡片网格 | title、lead、columns、items（icon 或图片、title、text、action） |
| STEPS | 编号步骤 | title、lead、items（title、text） |
| LIST | 编号条目，适合规则 | title、lead、items（title、text） |
| TEAM | 成员卡，没有图片时按名字生成像素头像 | title、columns、items（title 是名字，meta 是职务，text 是介绍，可选图片） |
| FAQ | 可展开的问答 | title、items（title 是问题，text 是回答） |
| STATS | 大数字 | title、columns、items（title 是数字，text 是说明，meta 是补充） |
| CALLOUT | 醒目横幅加按钮 | title、markdown、actions、可选背景图 |
| IMAGE | 一张宽图加说明 | 图片、title、lead |
| GALLERY | 小画廊，点开看大图 | title、columns、items（图片、title、meta） |

### 4.5 文字长度上限

玩家端会按这些上限截断，管理端和后端应当在保存时直接拒绝超长内容，并给出中文提示。

| 内容 | 上限 |
|---|---|
| 按钮文字、标签、导航名 | 24 字 |
| 标题 | 60 字 |
| 眉题、meta | 40 字 |
| 简介 lead | 300 字 |
| 条目正文 text | 600 字 |
| Markdown | 20000 字 |
| 链接地址 | 2048 字符 |
| 上传文件名 | 120 字符，`^[A-Za-z0-9][A-Za-z0-9._-]*\.(png|jpe?g|webp|gif)$` |
| 颜色 | `#rrggbb` |

### 4.6 玩家端引擎 ↔ 界面

新消息（引擎 → 界面）：

```json
{ "type": "server-status", "status": {
  "state": "ONLINE",
  "onlinePlayers": 37, "maxPlayers": 120, "latencyMs": 28,
  "motd": "§b鲸歌 §7· §6第三赛季\n§7第二行",
  "version": "1.20.1",
  "favicon": "data:image/png;base64,...",
  "checkedAt": "2026-10-22T12:00:00Z" } }
```

- `state` 取 `CHECKING`、`ONLINE` 或 `OFFLINE`。CHECKING 和 OFFLINE 时其他字段可以省略。
- 界面在收到第一条 `server-status` 之前不显示在线状态行。旧版引擎永远不发这条消息，界面也能正常显示。

新命令（界面 → 引擎）：`{ "command": "refresh-server-status" }`，玩家点击刷新按钮时发送。

新 Tauri 命令：`read_presentation_image(fileName: String) -> Result<Option<String>, String>`，返回 `data:image/...;base64,...`。

后端新增的资源元数据（不属于服主导入的展示 JSON）：`Branding.presentationImages?: { fileName: string; sha256: string; size: number }[]`。
管理项目信息的 `branding.presentationImages` 和顶层 `presentationImages` 列出全部已上传图片；签名发布只保留实际被引用的图片；独立的 `PlayerPresentation` 始终清空这份清单，玩家合并时只采用发布里的文件身份。

图片由 `player-app` 的独立后台任务下载到实例内 `DreamingFishUpdater/presentation-images/`，按发布清单校验大小与 SHA-256。下载不占用更新线程，失败静默跳过；完成后发送 `{ "type": "presentation-images-updated" }`，界面清除图片读取失败缓存并重新读取。Rust 读取命令也兼容 `.dreamingfish-bootstrap/presentation-images/`。

管理端预览消息 `dfs-admin-preview` 新增 `images: { [fileName]: blobUrl }`，让预览能显示刚上传、还没发布的图片。

---

## 5. 后端任务清单

### 5.1 协议（`protocol`）

- [x] 新增记录：`PlayerSpotlight`、`PlayerLinkTarget`、`PlayerServerCard`、`PlayerCommunityLink`、`PlayerEvent`、`PlayerTheme`、`PlayerGalleryItem`、`PlayerBlock`、`PlayerBlockItem`。
- [x] `Branding` 加 `spotlights`、`serverCard`、`events`、`theme`。保留现有的兼容构造函数，新增字段在旧构造函数里传 `null`；`withCoverObject`、`withMusicTracks` 要带上新字段。
- [x] `PlayerContentPage` 加 `layout`、`gallery`、`blocks`。
- [x] `PlayerPresentation` 的构造逻辑会逐个字段复制 `Branding`，要加上新字段。`coverObject`、`musicTracks` 仍然置空，图片文件同理（见 5.3）。
- [x] `ManifestValidator`：按 4.5 的上限和枚举校验；链接只接受 http(s)；`PAGE` 和 `ARTICLE` 要指向存在的页面和文章。
- [x] 新字段的往返和越界测试放在 `ShowcaseContractTest`；原 `JsonCodecTest` / `ManifestValidatorTest` 也已回归。

### 5.2 管理服务（`management-core`、`management-cli`）

- [x] 管理端保存外观时（`saveProject` 提交的 `branding`），要接受并保存新字段，校验失败时返回中文错误。相关位置：`ProjectSettingsSnapshot`、`AdminWebServer` 的项目保存接口。
- [x] 展示图片上传，做法参照背景图和音乐：
  - `POST /api/projects/{id}/presentation-images/upload?fileName=...`，请求体是图片二进制；
  - `GET /api/projects/{id}/presentation-images/{fileName}`，管理端预览用；
  - `DELETE /api/projects/{id}/presentation-images/{fileName}`；
  - 项目信息里列出已上传的图片（文件名、大小、sha256）。
  - 限制：png、jpg、webp、gif，单张最大 8 MiB，最多 100 张。
- [x] 发布时把被引用的图片作为发布对象打包，做法参照 `PublishService` 处理 `musicTracks` 的方式（`verifyMusicObjects`）。规则："检查并发布后生效"，和背景、音乐一致；文字内容仍然保存后下次启动就生效。
- [x] 静态分发（`StaticDistributionService`）和备份（`BackupService`）要把这些图片一起带上。

### 5.3 玩家端引擎（`player-app`）与 Tauri 命令（`player-ui/src-tauri`）

- [x] `PlayerController.mergePresentation` 要带上新字段：文字来自 presentation；图片清单来自 release，和 `musicTracks` 一样。
- [x] 图片下载到 `DreamingFishUpdater/presentation-images/`，参照音乐的存放方式（`.dreamingfish-bootstrap/music`、`DreamingFishUpdater/music`）。
- [x] Rust 命令 `read_presentation_image`：照着 `read_music_track_from_root` 写，包括同样的路径安全检查和 `require_player`。允许 png/jpg/jpeg/webp/gif，最大 8 MiB，按扩展名返回正确的 MIME，并在 `invoke_handler` 里注册。
- [x] 服务器状态（Server List Ping）：
  - 仅当 `serverAddress` 非空且 `serverCard.showStatus` 不为 false 时执行；
  - 解析地址的 host:port，默认端口 25565，没写端口时先查 SRV 记录 `_minecraft._tcp.<host>`；
  - 握手包（next state = 1）加状态请求，读取 JSON：`players.online/max`、`version.name`、`description`、`favicon`。`description` 可能是纯文本，也可能是聊天组件，统一转成带 § 颜色码的旧格式文本（含 `extra`、颜色名映射，hex 颜色用 `§x§R§R§G§G§B§B`）；
  - 用 Ping 包（0x01）或请求往返时间计算延迟；
  - 单次超时 3 秒；开窗时先发 `CHECKING`，结果出来后发 `ONLINE` 或 `OFFLINE`；窗口打开期间每 30 秒刷新一次；收到 `refresh-server-status` 立即刷新；
  - 在后台线程执行，任何异常都只能变成 `OFFLINE`，绝不能影响更新和启动许可。
- [x] `PlayerControllerTest` 覆盖新字段合并；`MinecraftServerStatusClientTest` / `PlayerServerStatusMonitorTest` 覆盖 Ping 解析、超时与后台隔离，`PlayerPresentationImagesTest` 覆盖图片下载和完整性。

### 5.4 Java 侧可以参考的前端实现

- MOTD 解析的期望效果见 `player-ui/src/lib/showcase.ts` 的 `parseMotd`。
- 完整示例数据见 `player-ui/src/lib/previewShowcase.ts`，后端测试可以直接拿来当样例。

---

## 6. 展示内容规范（服主自定义的规则）

**原则：** 服主只能提供数据，不能提供代码。玩家端不渲染服主写的 HTML、CSS 或脚本。服主能控制的是：

1. 内容本身；
2. 用哪种内置组件（焦点图、服务器卡、活动、10 种内容块、画廊、公告）；
3. 组件提供的选项：列数、色调（只能从项目配色里派生）、图标（固定图标集）、按钮去向；
4. 全局主题：项目配色（已有）加上 `theme.corner`、`theme.surface`。

这样无论是手写还是 AI 生成，界面都不会走样，也不会带来安全问题：玩家端 WebView 可以调用 Tauri 命令，绝不能执行服主提供的脚本。

**交付物：**

- ✅ `docs/showcase/README.md`：给服主看的规则说明，含每种组件的字段表、上限，以及一段可以直接复制给 AI 的提示词；
- ✅ `docs/showcase/showcase.schema.json`：JSON Schema，AI 工具和后端校验都以它为准；
- ✅ `docs/showcase/example-whalesong.json`：按当前示例做好的完整模板（内容与 `previewShowcase.ts` 一致，活动时间写成固定日期）；
- ⬜ `player-ui/src/lib/showcaseRules.ts`（未写）：上限常量、`normalizeShowcase`（玩家端兜底清理）、`validateShowcase`（返回带 JSON 路径的中文错误，管理端导入时用）；
- 文档格式是一个扁平 JSON，字段名与 `Branding` 相同：`{"format":"dreamingfish-showcase","version":1, "productName":…, "spotlights":[…], "serverCard":{…}, "events":[…], "theme":{…}, "contentPages":[…]}`。不包含 `coverObject` 和 `musicTracks`，背景和音乐仍然单独上传。

---

## 7. 剩余工作

第 13.3 节的后端字段已补齐，结果见第 14 节。剩余的是人工验收和发布：

1. **管理端人工联调**：后端接口自动化联调已覆盖新增字段保存与重新读取；还需在桌面版和网页版里把第 12.3 节和第 13.2 节的新分组、新字段都试一遍：上传和删除展示图片、编辑焦点图和内容块、导入 JSON、保存后检查发布。预览里应该能马上看到刚上传、还没发布的图片。
2. **真实服务器 Ping**：GPT 只用本地模拟服务测过（第 11 节），还要连一台真实的 Minecraft 服务器，确认在线人数、延迟、MOTD 和图标显示正常。
3. **发布**：决定玩家端和管理端的版本号，按 `AGENTS.md` 的标题格式提交，再按 `docs/RELEASING.md` 发布。这次玩家端需要服主重新发布。

## 8. 怎么跑起来看

- 玩家端浏览器预览：`npm --prefix player-ui run dev -- --port 4393 --host 127.0.0.1`。1420 端口在这台 Windows 上被系统保留，打开后自动播放示例：检查 → 下载 → 完成 → 15 秒倒计时。
- 想看某个具体状态，可以在浏览器控制台注入。开发服务器热更新过之后，页面实际加载的 store 地址会带 `?t=` 时间戳，直接 `import('/src/stores/player.ts')` 拿到的是另一份实例、改了不生效，要先找到页面用的那一份：
  ```js
  const url = performance.getEntriesByType('resource').map((e) => e.name).filter((n) => n.includes('/src/stores/player.ts')).pop();
  const m = await import(url);
  m.showError('下载没有完成', '连接超时', true);
  m.handleSidecarMessage({ type: 'unverified-offline' });
  ```
- 管理端预览加载的是 `player-ui/dist`，所以改完玩家端要先构建一次，管理端的预览才会更新。
- 测试：`cd player-ui && npx vitest run`；类型检查：`npx vue-tsc --noEmit`。

## 9. 注意事项

- CSP（`player-ui/src-tauri/tauri.conf.json`）的 `img-src` 允许 `data:`、`blob:`、`https:`、`http:`，展示图片可以用 data URL 或公开地址，不需要改 CSP。
- 展示内容出错（图片读不到、字段不合法、Ping 失败）都必须静默降级，不能阻塞更新、不能弹错误框。
- `player-ui` 工作区里的部分文件现在是 LF 换行（git 会按 `core.autocrlf` 统一处理，不影响提交内容）。

## 10. 设计稿

最开始给出的设计都在这里，开发时以它为视觉标准：

- 在线画布（可交互，带 Tweaks 切换状态）：<https://claude.ai/artifact/LcDWLweLoPtEZy15MZNU2M>
- 源文件副本：`docs/design/player-showcase/`。`*.dc.html` 是画布格式，需要画布运行时才能渲染，直接用浏览器打开只能看源码；排版和样式都写在行内 `style` 里，可以直接对照取值。`canvas.json` 记录了每张画板的标题和位置。

| 画板 | 内容 |
|---|---|
| `Main.dc.html` | 首页启动舞台：四张焦点图轮播（Ken Burns 缓慢推近）、左侧文案和切换器、右侧服务器卡（Logo、版本标签、彩色 MOTD、在线人数与延迟、地址复制、QQ 群 / Discord / 官网）、下一场活动倒计时、右下角更新区。Tweaks 里可以切换更新状态和更新区方案 |
| `HomeReady.dc.html` / `HomeError.dc.html` | 首页在更新完成（倒计时关闭）和更新失败时的样子 |
| `News.dc.html` | 公告：分类筛选、头条大卡、列表；文章页带目录和相关文章 |
| `Guide.dc.html` | 服务器介绍：页头、吸顶目录、特色卡片、加入三步、规则、团队像素头像、可展开的问答 |
| `Gallery.dc.html` | 画廊：拼贴网格、分类筛选、大图浏览（上一张、下一张、坐标） |
| `Details.dc.html` | 更新与本地管理面板：左侧两组分类栏、本次更新（7 种文件操作）、更新记录、运行记录、可选内容、文件管理范围、模组启停、自选模组、备份与恢复，以及各种确认框 |
| `ManageFiles.dc.html` / `ManageMods.dc.html` / `ManageBackups.dc.html` | 同一个面板直接打开在文件管理（铺满内容区）、模组启停、备份与恢复 |
| `DockCapsuleSwim.dc.html` | 最终选定的更新区：胶囊游水线（已在玩家端实现） |
| `DockBubble` / `DockSwim` / `DockTank` / `DockCapsule` / `DockPixel` | 更新区的另外五种备选方案：梦泡图标、小鱼游水线、鱼缸水位、灵动胶囊、像素经验条 |
| `FishMoods.dc.html`、`MoonFish.dc.html` | 梦鱼小鱼的五种表情和应用图标 |
| `UpdateDock.dc.html` | 更新区组件本身，被首页和各方案画板引用 |

设计稿里的主要取值：

- 底色 `#070A12`，正文 `#F3EFE6`，标题奶白 `#FFF6E0`；
- 强调色默认 `#2EE8DF`（玩家端用服主的 `accentColor`）；金色 `#FFD27A` 用于活动和倒计时，橙色 `#FF9A62` 用于出错，在线绿 `#7CF0A8`；
- 卡片 `rgba(9,13,22,.6)` 加 18px 毛玻璃、1px `rgba(255,255,255,.1)` 描边、18px 圆角；
- 画面叠一层很淡的颗粒噪点，左侧和底部有暗角，保证文字清楚；
- 设计稿标题用 Noto Serif SC 900。玩家端的 CSP 不允许加载在线字体，中文衬线字体打包又太大，所以实现里用已打包的 HarmonyOS Sans SC Bold。

---

## 11. 后端接手结果（2026-10-23）

第 5 节已完成，仍未提交、未修改程序版本、未发布 GitHub Release。前端剩余工作继续以第 7 节为准。

### 已接通的链路

- 协议：新增九种展示记录及 `PlayerPresentationImage` 文件身份记录，兼容旧构造函数和旧页面；`ShowcaseRules.java` 校验长度、数量、枚举、时间、按钮目标及图片路径。内容块列数拒绝小数截断和字符串强转。
- 管理服务：项目保存保留新字段；图片上传、读取、删除和清单接口可用，单张 8 MiB、项目 100 张，检查文件名和图片签名。仍在展示内容中使用的图片不能直接删除。
- 发布与分发：只有被引用且哈希、大小正确的图片进入签名发布；草稿上传不会改写旧发布。静态导出、加密备份恢复、内容对象统计与保留、分发基线检查已接入图片。
- 玩家端：文字来自独立展示数据，图片身份来自可信发布；图片在后台下载和校验，通过第 4.6 节的缓存刷新消息重新读取。HTTP 客户端仅在真正下载时创建，预览和普通初始化不会提前初始化网络下载器。
- 服务器状态：独立线程执行查询，包含 DNS/SRV、连接和响应读取的总超时为 3 秒；支持显式端口、IPv6、MOTD 组件和颜色、30 秒刷新、手动刷新、过期结果丢弃。错误只变为离线状态。发行运行时已加入 `java.naming` / `jdk.naming.dns`。
- 兼容修正：终端修改基础外观时保留所有展示字段，编辑画廊或内容块页面头部时保留原内容；玩家界面优先使用显式 `layout`，不再因缺少旧 `announcementPage` 标记而丢失公告内容。

### 验证结果

- Java 测试报告合计 321 项通过：protocol 48、update-engine 61、management-core 113、management-cli 46、player-app 53。管理核心完整回归通过，后续修改补跑受影响测试；管理接口和玩家端完成全部测试。
- 玩家端界面 18 个测试文件、80 项测试通过；管理端前端 52 项测试通过。
- Rust 玩家端 13 项测试通过，Tauri `custom-protocol` 编译检查通过。
- 玩家端、管理端 Web、管理端桌面前端均完成类型检查和构建；三个构建资源标识一致。
- Maven 管理端、玩家端及依赖模块打包完成；`check-artifact-identity.py` 确认管理端、玩家端和启动引导器 JAR 的构建身份一致。
- 用发行脚本中的玩家端模块集生成精简 Java 运行时，确认 DNS 模块存在并可执行。
- Ping 使用本地模拟 Minecraft TCP 服务和可注入的 SRV 解析做自动化测试；尚未连接真实 Minecraft 服务器，也未做新管理编辑器的桌面联调。

新增测试主要位于 `ShowcaseContractTest`、`PresentationImagesTest`、`MinecraftServerStatusClientTest`、`PlayerServerStatusMonitorTest` 和 `PlayerPresentationImagesTest`。Web 图片接口的保存与删除流程在 `AdminWebServerTest` 中覆盖，终端保留展示配置的行为在 `ManagementCliTest` 中覆盖。

---

## 12. 第三轮前端（2026-10-23，GPT 完成后端之后）

### 12.1 更新与本地管理面板

| 文件 | 改动 |
|---|---|
| `player-ui/src/components/DetailsDrawer.vue` | 重写成设计稿 `Details.dc.html` 的布局：左边是分类栏，分“更新”（本次更新、更新记录、运行记录）和“本地管理”（可选内容、文件管理、模组启停、自选模组、备份与恢复）两组，每项带图标和角标，运行记录有错误时角标变红；底部是“打开游戏目录”。右边是页头（标题、说明、页面操作按钮、铺满、关闭）加内容。store 的 `DrawerMode` / `localMode` 没变，更新区的快捷入口照常能用。本次更新页改成版本号加彩色计数、更新说明卡、文件变化列表；更新记录改成时间线。 |
| `LocalFileTree.vue`、`BackupList.vue`、`OptionalGroups.vue` | 原来写在各自顶部的说明文字和按钮（恢复全部管理、恢复整合包默认、打开备份文件夹）移到面板页头，功能和确认框都保留。 |
| `player-ui/src/lib/icons.ts` | 加 document、terminal、sliders、cube、userPlus、expand、collapse、arrowRight。 |
| `player-ui/src/styles/player.css` | 面板宽 `min(860px, 100% - 300px)`，浮在内容区上（上 60、右 14、下 14），18px 圆角毛玻璃；窗口宽度不超过 1040 时分类栏收窄到 164。删掉旧的 `.drawer-tabs`、`.local-mode-bar`、`.backup-tools` 样式。启动提示条在面板打开时右移到面板左侧。 |
| 测试 | `DetailsDrawer.test.ts` 按分类栏重写并新增两项；`AppLifecycle.test.ts` 跟着新的提示条位置改。 |

### 12.2 首页对齐设计稿

服主反馈“不如一开始的设计好看”，所以这一轮把首页按 `Main.dc.html` 的数值逐项改了一遍：

- **外框**：窗口圆角 29 → 14，去掉青紫色发光描边和 `liquid-glass-wash` 叠色，只留 1px 浅描边。
- **暗角**：`.image-shade` 换成设计稿的四层渐变（左侧、底部、标题栏下方 130px、右栏后 440px）加颗粒。
- **标题栏**：透明，服主的顶栏颜色变成向下淡出；品牌名和英文名上下两行；导航 13.5px，当前项下方 2px 强调色短线；窗口按钮 46×52 平铺；玩家胶囊和音乐胶囊统一为 32px 高的半透明胶囊（音乐卡位置改为 `top: 10px; right: 146px`）。
- **左侧文案**：贴在切换器上方（底部对齐）；标签是实心强调色；标题 64px；按钮 42px 高、10px 圆角，主按钮带箭头，旁边新增“复制服务器地址”；切换器改成 118px 宽的细进度线加标题。欢迎页和焦点图共用同一套结构。
- **右栏**：宽 312，上 70、右 28、下 24；胶囊展开时整栏加宽到 372，说明文字能完整显示。首页不再显示右下角的版本号（它和胶囊重叠）。
- **毛玻璃卡片**：服务器卡、活动卡、更新胶囊统一为 `rgba(卡片色, .6)` 加 18px 模糊、1px `rgba(255,255,255,.1)` 描边、`0 20px 60px` 阴影，去掉原来的白色渐变高光。主题默认值跟着改：ROUND 18px，GLASS 透明度 0.6。
- **胶囊展开态**：按 `UpdateDock.dc.html` 精简，只剩一行说明、两条同样式的信息行和底部三个入口。第一条是“版本 · 安装 3 · 删除 1 · 备份 1”，悬停可看文件明细，点击打开本次更新；第二条是“备份 1 个文件 · 保留 1 个配置 · 2 个自选模组”，点击打开备份或自选模组。出错时换成“打开目录 / 仍然启动 / 重试”三个等宽按钮，重试为主按钮。原来的摘要卡、计数标签块、金色提示框和备份按钮都已合并到这两条里。
- **衬线标题**：服主要求标题用偏宋的字体。现在用 Noto Serif SC 900（SIL OFL 许可），只打包 woff2 分片，放在 `player-ui/src/assets/fonts/noto-serif-sc-900/`，共 101 个分片、约 4.7 MB，许可证在同目录 `LICENSE.txt`。`src/styles/title-serif.css` 按 unicode-range 声明，窗口只加载用到的字。用在首页大标题、标题栏标识块、服务器 Logo 首字、页面标题、内容块标题、公告头条和大图标题上（CSS 变量 `--dfs-title-font`）；正文仍然用 HarmonyOS Sans。

### 12.3 规则校验与管理端编辑

| 文件 | 说明 |
|---|---|
| `player-ui/src/lib/showcaseRules.ts` | 新增。`SHOWCASE_LIMITS` 等常量；`validateShowcase` 逐项列出全部问题，每条都带 JSON 路径，规则与 `ShowcaseRules.java` 一致，另外还检查不认识的字段、页面 id 重复和颜色格式；`parseShowcase` 把 JSON 语法错误也变成中文提示；`showcaseFromBranding` / `applyShowcase` 负责导出和导入，导入时背景、音乐、图片身份保持不变；`normalizeShowcase` 是玩家端兜底，在 `displayBranding` 里调用：不认识的选项退回默认，超长的列表和文字截断，没有标题的焦点图和时间无效的活动直接丢弃；`SHOWCASE_AI_PROMPT` 与 README 第 8 节相同，有测试保证两边不走样。 |
| `admin-desktop/src/workbench/showcase.ts` | 新增。转出上面的规则，加上选项的中文名、每种内容块显示哪些字段、id 生成、排序、`datetime-local` 与带时区时间的互转、给 AI 的说明拼接。 |
| `ShowcaseEditor.vue` | 新增四个分组：**首页焦点与活动**（焦点图增删排序、背景图、按钮；活动按本机时区编辑）、**服务器卡片**（显示开关、在线状态开关、版本文字、MOTD、Logo、最多 4 个社区按钮）、**展示图片**（多选上传，文件名自动改成合规名字，缩略图、大小、移除）、**导入与导出**（实时检查当前内容、复制 JSON、粘贴或选文件导入并列出错误、复制给 AI 的说明）。给 AI 的说明里会附上 schema 和完整示例。 |
| `PageBodyEditor.vue` | 新增。画廊页的图片编辑，内容块页的块和条目编辑（按块的种类只显示会用到的字段，最多 3 个按钮）。 |
| `ImageField.vue`、`LinkField.vue` | 新增。选择已上传的图片或填公开地址；按钮去向可以是页面、公告或链接。导入文件里引用的、还没上传的图片会显示为“尚未上传”。 |
| `AppearanceView.vue` | 接入以上编辑器；“外观与背景”加圆角和质感选项；“公告与页面”可以新建图文页、公告页、内容块页、画廊页，并加了眉题；已上传图片读成 blob 地址，放进预览消息的 `images` 字段；分组多了以后，侧边栏收起时改用下拉框选择。 |
| `presentation.ts` | 外观分组从 4 个增加到 8 个：标题与品牌、首页焦点与活动、服务器卡片、外观与背景、公告与页面、展示图片、启动音乐、导入与导出。 |
| `vite.config.ts` | 开发服务器 `fs.allow: ['..']`，以便读取 `player-ui` 和 `docs/showcase`。 |
| `workbench.css` | 列表编辑、条目卡、图片网格、问题列表的样式。 |

### 12.4 验证结果（本轮结束时）

- 玩家端：`vue-tsc` 通过；`vitest` 22 个文件、102 个测试全部通过。新增的测试文件有 `showcase.test.ts`（MOTD、轮播回退、按钮、活动）、`showcaseRules.test.ts`（示例和预览内容能通过校验、错误路径、按字符计长度、导入只覆盖展示字段、兜底清理、README 提示词一致）、`HomeStage.test.ts`（服务器卡、活动卡、首页文案和切换器）、`ShowcasePages.test.ts`（内容块页、画廊筛选和大图浏览）。
- 管理端：`vue-tsc` 通过；`node --test` 60 个测试全部通过，其中新增 `tests/showcase.test.mjs` 8 个；`npm run build` 和 `npm run build:web` 都构建成功。
- 浏览器预览里看过：1180×680 和 960×560 下的首页（胶囊收起和展开）、公告页、服务器介绍页、更新与本地管理面板（各分类）。960×560 下文案和右栏没有重叠，面板宽 660，分类栏收窄到 164。
- 没有做：管理端连真实管理服务联调；Java 测试本轮没有重跑（本轮没有改 Java 代码）。

---

## 13. 第四轮：完全按设计稿（2026-10-23）

服主要求“完全按照设计稿”，这一轮把 `docs/design/player-showcase/` 里每张画板的行内数值逐项对照了一遍，所有不一致的地方都改了。唯一保留的差异是导航最后的“关于更新器”：服主确认保留它。

### 13.1 改了什么

| 范围 | 改动 |
|---|---|
| 字体 | 新增像素字体 Silkscreen（`player-ui/src/assets/fonts/silkscreen/`，约 12 KB，SIL OFL，许可证在同目录），样式在 `src/styles/pixel-font.css`，CSS 变量是 `--dfs-pixel-font`。用在英文品牌名、眉题、切换器序号、目录编号、步骤序号、规则编号、大图计数这些地方。标题仍然用 Noto Serif SC 900，正文用 HarmonyOS Sans。默认标题色改成设计稿的 `#FFF6E0`。 |
| 标题栏 `TitleBar.vue` | 导航第一项改叫“首页”；窗口按钮换成设计稿的 12px 线框图标，关闭按钮悬停变 `#C4302B`。首页右侧是音乐胶囊加玩家胶囊（音乐在左）；其他页面换成更新状态胶囊，显示更新中、已就绪、倒计时、窗口保持打开、出错、离线，点击回到首页，倒计时中点击还会保持窗口打开。页面滚动到页头以下时，标题栏变成半透明实色加模糊。 |
| 音乐 `MusicPlayer.vue` | 从绝对定位改为标题栏里的胶囊：音量条加曲名点开播放列表面板，右侧图标直接播放或暂停。面板从胶囊下方展开。 |
| 玩家头像 `PixelFace.vue`、`pixelFace.ts` | 改成 8×8 的 Minecraft 风格像素脸（头发、肤色、眼睛、鼻子、嘴），按名字固定生成。标题栏和团队卡共用。 |
| 内容页背景 | `App.vue` 新增 `.content-backdrop`：项目背景模糊 46px、压暗到 0.34，叠一层深色和颗粒，和设计稿一致。原来的半透明渐变底和右下角版本号都去掉了。只在首页抽屉打开时还显示顶部的启动提示条，其他页面由标题栏胶囊代替。 |
| 首页 | 更新区完成后的标题改成“已更新到 r13”；标题行只留标题和百分比，下载时说明行显示字节数，正在处理的文件放进悬停提示；完成时说明行写“窗口保持打开”；活动卡去掉箭头；切换器显示焦点图的新字段 `label`（短名字，留空用标题）。 |
| 公告 `NewsPage.vue` | 按 `News.dc.html` 重做。列表页：眉题、30px 衬线标题，右上角是带数量的分类筛选；左边是头条大卡，有 300px 封面、“最新”和分类角标、日期·作者·阅读时长、27px 衬线标题和“阅读全文 →”；右边是日期块列表，带分类色标、标题、两行摘要。文章页：返回按钮、220px 封面、分类·日期·作者·阅读时长、34px 衬线标题、正文；右侧是 CONTENTS 目录（二级标题，像素编号）和“接着看”（带缩略图）。分类颜色：活动金、维护橙、更新蓝、规则紫，其他用强调色。 |
| 正文排版 `markdown.ts` 和样式 | 所有 Markdown 正文统一成设计稿的文章排版：段落 15px、行高 1.9；二级标题 21px 衬线；无序列表用 6px 强调色方块；引用显示成带信息图标的强调色提示框，第一行粗体当作提示框标题；图片下方显示说明文字（取图片的替代文字）。 |
| 介绍 `BlocksPage.vue` | 按 `Guide.dc.html` 重做。页头 312px 高，用页面的新字段 `imageFile`/`imageUrl` 作封面，没有就用项目背景；左下是眉题、44px 衬线标题、简介和新字段 `tags` 的标签；右下是地址卡和“复制地址”按钮。正文左侧 168px 吸顶目录，带像素编号和当前项高亮；每节有“01 · FEATURES”格式的眉题（新字段 `eyebrow`，不填按种类给默认英文）。卡片改成图标在左、文字在右的两列，日历、礼物、奖杯、爱心图标用金色；步骤用 22px 像素序号，条目的 `meta` 显示成可复制的地址框；规则改成带圆圈编号的列表，`meta` 显示成右侧橙色标记（如“违反即封禁”）；团队四列，像素脸加职务，职务含“服主”时为金色；问答改成加号旋转的折叠，默认展开第一条。统计、横幅、图片、小画廊也统一到这套样式。 |
| 画廊 `GalleryPage.vue`、`ImageLightbox.vue` | 按 `Gallery.dc.html` 重做。7 格拼贴（左上大图、右侧竖图、中间小图、底部横条），超过 7 张时按同样的格子往下排，一屏 3 行；筛选不再隐藏不匹配的图，而是压暗变灰；新字段 `featured` 显示“本周精选”金色角标；图上写“by 作者”。大图浏览：860×440 的大图，下方是衬线标题、by 作者 · 分类、说明，右侧是坐标标签和“01 / 07”像素计数，两侧 46px 圆形翻页按钮，右上角关闭。 |
| 面板各分类 | 运行记录：每天一行汇总（日期、细线、共 N 条、提醒 N、错误 N），时间只显示到秒，错误行淡红底，异常详情改成文字链接加深色代码块。模组和自选模组：两字母色块（必需金、可选内容蓝、整合包强调色、玩家紫）、名称加版本、来源和路径、锁定说明带锁图标，右侧是启用说明加 40×22 拨动开关。文件管理：搜索框带放大镜，右侧图例（随整合包更新、自行管理、服主锁定），文件夹金色图标、文件图标，104px 的“管理”勾选框（勾、半选、锁三种状态）。可选内容：两列卡片加拨动开关。备份：金色图标块、打开和删除（红色描边）按钮，文件行缩进，“放回游戏”是强调色按钮；没有备份时显示虚线框。本次更新：更新说明里以“新增、更新、修复、调整、移除、删除”开头的行，会显示成带色标签的列表。 |
| 确认框 `ConfirmDialog.vue` | 420px 卡片，左侧按语气着色的图标块（提示、提醒、危险），标题、说明，“取消”加按语气着色的主按钮，背景压暗并轻微模糊。 |
| 滚动条 | 全局改成 8px 宽、`rgba(255,255,255,.14)` 的细条。 |
| 示例内容 | `previewShowcase.ts` 的公告、介绍、画廊都改成设计稿原文，填满了新字段。`docs/showcase/example-whalesong.json` 从预览内容重新导出：在设计稿的 5 节之外，额外放了统计和横幅两种块，方便服主参考。 |

### 13.2 管理端

- 焦点图：新增“切换器名字”（`label`）。
- 公告：新增“分类”和“作者”。
- 画廊图片：新增“本周精选”开关。
- 内容块页：新增“页面顶部”，可以设置顶部大图和标签（用逗号分开）；每个内容块新增“英文小字”（`eyebrow`）。
- 新字段都已写进 `docs/showcase/showcase.schema.json`、`docs/showcase/README.md` 和 `player-ui/src/lib/showcaseRules.ts`（校验和兜底清理）。

### 13.3 后端字段（GPT 已完成，见第 14 节）

都是**可选**字段，旧数据没有它们时照旧显示。字段名与 `player-ui/src/lib/types.ts` 一致，限制与 `showcaseRules.ts` 一致：

| 记录 | 新字段 | 类型与限制 |
|---|---|---|
| `PlayerSpotlight` | `label` | String，≤ 24 字 |
| `PlayerNewsArticle` | `category` | String，≤ 24 字 |
| `PlayerNewsArticle` | `author` | String，≤ 40 字 |
| `PlayerGalleryItem` | `featured` | Boolean |
| `PlayerBlock` | `eyebrow` | String，≤ 40 字 |
| `PlayerContentPage` | `imageFile`、`imageUrl` | 和其他图片一样：文件名规则、公开 http(s) 地址 |
| `PlayerContentPage` | `tags` | List<String>，最多 6 个，每个不为空且 ≤ 24 字 |

后端已完成以下工作：

1. `protocol` 的五种记录已补齐八个可选字段，保留现有兼容构造函数（新字段传 `null`）；`PlayerPresentation` 沿用完整嵌套记录复制，保留这些字段。
2. `ShowcaseRules.validate` 已按上表校验；`referencedImageFiles` 包含页面的 `imageFile`，介绍页封面会随签名发布和静态分发提供。
3. `management-cli` 终端编辑页面和公告时保留这些字段，并以显式 `layout` 决定默认页面类型，避免缺少旧公告标记时意外切换类型。
4. `ShowcaseContractTest` 已扩展为 12 项，显式断言完整示例中的新增字段确实保留，并覆盖越界、旧构造函数和旧 JSON。管理 HTTP 接口、终端编辑及封面发布另有集成测试。

管理端保存和重新读取时会保留全部新增字段。切换器短名、公告分类和作者、介绍页标签、画廊精选标记、内容块眉题保存后按既有展示数据刷新流程生效；上传的介绍页封面仍须检查并发布后下发。

### 13.4 验证结果

- 玩家端：`vue-tsc` 通过；`vitest` 22 个文件、102 个测试全部通过；`npm run build` 成功。
- 管理端：`vue-tsc` 通过；`node --test` 60 个测试全部通过；`npm run build`、`npm run build:web` 都成功。
- Java：`ShowcaseContractTest` 用新的示例模板跑过，7 项全部通过。
- 浏览器预览（1180×680）里逐页看过：首页（标题栏、像素脸、切换器短标签、更新区）、公告列表和文章页、介绍页、画廊页、面板的运行记录、模组启停、文件管理、备份与恢复，以及确认框。
- 没做：音乐胶囊没有在浏览器里实际看过（预览数据里没有音乐），只有单元测试覆盖；管理端新字段还没连真实服务联调。

---

## 14. 第四轮后端接手结果（2026-10-23）

第 13.3 节已完成，未修改程序版本、未提交、未发布。

- 新字段全部进入 Java 协议记录：`PlayerSpotlight.label`、`PlayerNewsArticle.category/author`、`PlayerGalleryItem.featured`、`PlayerBlock.eyebrow`、`PlayerContentPage.imageFile/imageUrl/tags`。旧构造函数与不含新字段的 JSON 继续可用，显式 `featured: false` 也会保留。
- 长度按 Unicode 字符计数，与前端一致。标签最多六个、每个不为空且最多 24 字；页面封面复用其他图片的文件名、URL 和长度规则。公告元数据在旧 `newsArticles` 和没有 `layout` 的旧公告页中也会校验。错误包含具体 JSON 路径。
- 页面封面加入发布图片引用集合；仅引用于页头的图片也会进入发布和静态导出，仍被页面引用的图片不能删除，缺少上传文件时会阻止签名发布。
- 终端编辑介绍页、画廊页和公告时会保留封面、标签、块眉题、精选标记和公告分类/作者。玩家端合并独立展示数据时保留这些新字段，同时继续只从可信发布读取图片身份。

本轮验证：

- Java 共 115 项通过：协议模块完整 53 项；图片、发布、静态分发、备份相关 27 项；管理 HTTP 接口与终端工作流 25 项；玩家控制器与展示数据获取 10 项。
- `ShowcaseContractTest` 12 项通过，包含完整示例的实际字段值断言、Unicode 边界、空标签与数量上限、危险封面地址、旧数据兼容。
- HTTP 接口测试实际保存完整示例后重新 GET，并验证后续只改简介仍保留新字段；无效标签返回错误且不改变原草稿。终端测试通过实际输入流程修改介绍页、画廊页和公告后读取数据库确认字段保留。
- 玩家端界面 102 项、管理端前端 60 项测试通过；玩家端、管理端 Web、管理端桌面前端的类型检查和构建通过。
- Maven 管理端、玩家端及依赖模块打包成功；Tauri `custom-protocol` 编译检查通过；三份 JAR 的构建身份校验通过。

本轮完成了新增字段的后端 HTTP 自动化联调；桌面与网页版的人工点击验收、真实 Minecraft 服务器 Ping 仍按第 7 节跟进。

---

## 15. 第五轮：更新卡片与整体样式整理（2026-10-24）

服主对第四轮的反馈：界面还是有点乱；原来的毛玻璃卡片好看；全部用鸿蒙字体；其他页面不要大改，重点做更新卡片和更新信息的展示；标题栏和内容之间、页头和正文之间不要有分隔线，用渐变过渡。这一轮只改玩家端前端，数据格式和后端都没有变化。

### 15.1 更新卡片（`UpdateArea.vue`）

右侧仍是三张分开的毛玻璃卡片：服务器卡在上，活动卡在中间，更新卡在底部。更新卡固定三行：状态标题（完成是勾、出错是感叹号、离线是离线图标，进行中是转动的圆弧）、小鱼游水线、一行“正在发生什么”。各状态：

| 状态 | 标题行右侧 | 第三行 | 其他 |
|---|---|---|---|
| 检查更新 | — | 正在连接更新服务 | 小鱼在起点附近张望 |
| 下载中 | 百分比 | 已下载 / 总大小 | 正在处理的文件放在悬停提示里 |
| 完成 / 已是最新 | 展开、收起按钮 | 游戏启动情况，右侧“保持打开”按钮带倒计时圆环 | 展开时显示更新信息（见下） |
| 出错 | “运行记录 ›” | 错误原因（最多 3 行，可选中复制），下面一行“Minecraft 启动已暂停” | 打开目录、仍然启动、重试（主按钮） |
| 离线启动 | 展开、收起按钮 | 离线原因 | 水线换成金色 |

**更新信息**（卡片展开后，嵌在卡片里的浅色面板）：

- 标题“本次更新 / 当前版本 / 离线版本”，后面是版本号和发布时间。
- 服主更新说明的前 3 行。以“新增、更新、修复、调整、移除、删除”开头（或写成【修复】）的行带彩色标签，其他说明用小方块列表显示。
- 改动计数：安装、删除、备份、保留、放弃管理，每个数字都能点，打开对应的详情页（备份打开“备份与恢复”）。
- 底部“查看全部改动”（说明超过 3 行时显示为“查看完整说明与改动”），右侧是更新记录、运行记录、本地文件三个小图标。
- 有更新、有备份或检测到自选模组时，卡片默认展开；玩家手动收起或展开后，在状态变化之前都按玩家的选择显示。收起时，只用一行提示保留了哪些文件，这一行只会在分隔点处换行。
- 窗口高度不超过 720 时，展开后服务器卡会隐藏 MOTD 和社区链接；不超过 640 时，活动卡也会隐藏，保证更新卡完整显示。

更新说明的解析移到了新文件 `player-ui/src/lib/changelog.ts`，卡片和详情页的“本次更新”共用同一套规则。

### 15.2 全局样式

- **字体**：全部文字改用鸿蒙字体：常规字重用于正文，粗体用于标签、按钮和数字。新增 `player-ui/src/assets/fonts/HarmonyOS_Sans_SC_Regular.woff2`，和管理端用的是同一个文件，许可证复制到同目录的 `HarmonyOS_Sans_SC_LICENSE.txt`。大标题仍然用 Noto Serif SC 900。第四轮加入的像素字体 Silkscreen 已删除，包括 `pixel-font.css` 和 `assets/fonts/silkscreen/`。
- **毛玻璃卡片**：沿用第四轮的配方，用服主设置的卡片颜色、透明度和模糊度，加 1px 浅色描边和柔和阴影，写成 CSS 变量 `--glass`、`--glass-filter`、`--glass-border`、`--glass-shadow`。首页三张卡片、介绍页的特色卡片和团队卡片都用这一套。
- **去掉分隔线**：
  - 标题栏原来那条线，来源是背景渐变平铺到了透明的下边框里，现在改成标题栏底色在下方 40px 内渐隐。
  - 页面滚动后，标题栏变成深色渐变，同样渐隐，不加线。
  - 介绍页的页头大图和遮罩都向下多延伸 120px，再渐隐进正文。
  - 窗口按钮左边的竖线也去掉了。
- **样式文件拆分**：`player.css` 原来有 6000 多行，层层覆盖，已经删除，拆成 5 个文件，由 `main.ts` 依次引入：

  | 文件 | 内容 |
  |---|---|
  | `base.css` | 字体、颜色变量、按钮、开关、复选框、搜索框、滚动条 |
  | `shell.css` | 窗口背景、标题栏、音乐、启动提示、确认框 |
  | `home.css` | 首页主视觉、右侧卡片、更新卡、小鱼动画 |
  | `pages.css` | 公告、正文排版、介绍、画廊、大图浏览、关于 |
  | `drawer.css` | 更新与本地管理面板 |

  类名和组件结构基本沿用第四轮，页面布局没有改。

### 15.3 顺手修掉的问题

- **加粗失效**：以中文标点结尾的加粗原来不会生效，比如 `**永夜维度：**从主城进入` 会原样露出星号。原因是 CommonMark 的规则只在左右边界符合条件时才把 `**` 当成加粗。`markdown.ts` 新增了一个行内扩展，只要是成对的 `**…**` 就按加粗处理，代码片段里的星号不受影响。
- **图片替代文字**：图片框里的替代文字原来总是显示，现在放在图片后面，只有图片加载失败时才露出来。
- **大图浏览**：改到标题栏下方，关闭按钮不再和窗口按钮重叠。
- **零散修正**：无序列表的小方块改为和第一行文字居中；启动提示的文字可以换行；文件树“管理”那一列加宽到 108px，不再折行。
- **首页细节**：主视觉的“复制服务器地址”按钮直接显示地址本身，所以服务器卡不再重复显示地址。音乐面板的播放和循环按钮改成线条图标。“关于”页加上了小鱼图标。
- **示例数据**：`bridge.ts` 预览数据里的更新说明改成 4 行带标签的写法，方便看展开效果。

### 15.4 补充：标题栏小卡片与音乐窗口

服主看过之后要求：玩家信息也做成小卡片，音乐窗口补上。

- 标题栏右侧的玩家信息、音乐、更新状态，现在是同一种毛玻璃小卡片，高 32px，用和首页卡片相同的颜色、透明度和模糊度。
- 音乐小卡片在**所有页面**都显示：首页放在玩家卡片前面，其他页面放在更新状态前面。播放音乐时，看公告也能随时暂停。
- 点开音乐小卡片是一个毛玻璃窗口：
  - 顶部是正在播放或已暂停，以及曲名；
  - 中间是曲目列表（有两首以上才显示），当前曲目带跳动的音量条，点其他曲目就切换，再点一次当前曲目就是暂停或继续；
  - 底部是播放 / 暂停和循环两个按钮。
  - 点窗口外面或按 Esc 会关闭。
  - 原来的下拉选择框去掉了。
- 浏览器预览和管理端预览本身没有音频文件，所以预览数据加了 3 首示例曲目（只有名字）。在预览里点播放只切换显示状态，正式程序里照常播放。
- **毛玻璃没有生效**：首页卡片和标题栏小卡片的背景模糊以前其实没起作用，因为入场动画结束后一直保持着最终状态，浏览器会把它当成“仍在动画中”的图层，里面的卡片只能模糊一个空图层。现在入场动画（`.reveal`、`.page-reveal`、整个窗口的淡入）改成只在开始前保持起始状态，结束后不再保持，卡片能真正模糊后面的背景图。

### 15.5 验证结果

- 玩家端：`vue-tsc` 通过；`vitest` 23 个文件、107 个测试全部通过（更新卡测试按新结构重写，新增更新说明解析、中文加粗、音乐曲目列表与关闭方式的测试）；`npm run build` 成功。
- 用本机无界面 Edge 按 1180×680 截图逐页看过：
  - 首页更新卡的 7 种状态；
  - 公告列表和文章页、介绍页（顶部和滚动后）、画廊和大图浏览、关于页；
  - 详情面板的本次更新、运行记录、文件管理、模组启停，以及确认框。
- 960×560 最小窗口下看过首页，更新卡展开后能完整放下。
- 没做：管理端没有改动，所以没有重跑管理端测试。打包后的 Tauri 窗口里还没有实际看过，所有截图都来自浏览器预览。

---

## 16. 第六轮：部署到服主的服务器，焦点图和活动不限数量（2026-10-24）

服主想先在自己的服务器上看看新版更新器，然后再继续改代码。服主确认版本号用 0.3.0，并已向玩家确认没有人装过 10 月 3 日那次 0.3.0（见 16.2）。按服主要求**只部署、不提交**，也没有发 GitHub Release。

### 16.1 版本号

玩家端从 0.2.2、管理端从 0.2.8 都改成 **0.3.0**，代码、构建参数、测试断言、锁文件和发行包模板里的版本号已同步：

- 管理 CLI 的 `ManagementCli.VERSION` 和 `--version` 文字；
- 桌面端：`package.json`、`Cargo.toml`、`Cargo.lock`、`tauri.conf.json`、`main.rs`、`service.rs`（桌面端会核对本机管理核心的版本）、`desktop-chrome.js`、`Launcher.vue`、工作区 `App.vue` 的两处版本文字；
- 玩家端：`PlayerRuntime.VERSION`、`player-ui` 的 `package.json`、锁文件、`Cargo.toml`、`Cargo.lock`、`tauri.conf.json`、关于页；
- `packaging/` 下的构建、冒烟测试、审计脚本默认值，以及发行包 README 模板。

`README.md`、`docs/CAPABILITIES.md` 等使用说明和 `CHANGELOG.md` **还没改**。正式发布前要按 `docs/RELEASING.md` 补上，并写 `docs/releases/v0.3.0.md`。

构建脚本 `packaging/build-distributions.ps1` 顺手修了两个问题：

- 删掉了检查 `player-ui/src/App.vue` 里 `Updater {{ "版本" }}` 的那一项。这个文字在第四轮已经随右下角的版本号一起去掉，现在只有关于页显示版本，脚本原本就检查关于页。
- 第 454 行复制 `java.exe` 时，原来用的是 `-JdkHome` 参数本身，没传这个参数时它是空的，脚本会在打包管理端时出错；改成用脚本自己找到的 JDK 路径 `$resolvedJdk`。

注意：构建脚本开始时会清空 `dist/`，原来放在那里的 0.2.x 安装包已经被清掉了，需要时可以从对应版本重新构建。

### 16.2 部署前要注意的

- 服务器上的网页管理和对外下载服务可能是同一个进程，也可能分开运行。部署前先确认实际怎么运行，停服务时两个都要处理。
- 新版网页管理**不会在启动时自动打开下载服务**。原来靠网页里“启动下载服务”打开的，升级后要用 `dfs-admin serve` 单独启动，或者再到网页里打开一次。`serve` 会登记控制通道，之后可以在网页概览里停止和重启。
- 玩家端有防回退保护：不接受更旧的版本，同一版本号内容变了也不接受。服务器上如果曾经发布过某个版本号又改回去，再次使用这个版本号前，要先确认没有玩家装过旧的那份。这一轮服主已经确认过。

### 16.3 部署步骤

1. 本地运行 `packaging/build-distributions.ps1`，Maven 全部测试通过，得到 `dfs-admin-linux-x64-0.3.0.zip` 和 `dreamingfish-player-windows-x64-0.3.0.zip`。
2. 上传到服务器，核对校验值，在服务器上运行新程序的 `--version`。
3. 停止网页管理和下载服务。等进程真正退出后再替换文件。
4. 备份旧的 `app/`、`runtime/`、`support/`、`dfs-admin`、设置和网页账户文件，以及 `data/` 下的数据库、密钥、清单、玩家端程序、预览和源目录归档。`data/objects` 是按内容寻址的只读文件，`deployments/` 可以重新生成，这两个可以不复制。
5. 替换程序和运行环境，`data/`、设置、账户、`deployments/` 原地保留。
6. 按原来的方式启动网页管理和下载服务。
7. 发布玩家端：`./dfs-admin player publish <项目> --platform windows-x64 --version 0.3.0 --source <解压目录> --yes`。
8. 通过玩家实际访问的地址核对：整合包清单和部署前一样，玩家端清单是新版本。
9. 删掉上传目录里解压出来的临时文件，玩家端文件已经导入对象库。

**如需回退**：停止服务，用备份的程序和 `data/management.db` 换回去；在 `player-programs/<项目>/windows-x64/` 里把 `latest` 改回旧版本，并移走新版本目录。已经升级的玩家不会退回旧版，因为防回退保护不接受更旧的版本。然后按原方式重新启动。

### 16.4 焦点图和活动不限数量（部署之后改的代码，服务器上还没有）

- **前端规则** `player-ui/src/lib/showcaseRules.ts`：
  - `SHOWCASE_LIMITS` 去掉了 `spotlights` 和 `events`；
  - 校验时不再检查这两类的数量；
  - 玩家端的兜底清理也不再截断。
- **后端规则** `protocol/.../ShowcaseRules.java`：删掉 `count(spotlights, 6)` 和 `count(events, 10)` 两行。这是这一轮唯一改动的后端代码，GPT 接手时请知悉。
  - `ShowcaseContractTest` 原来测“超过 6 张焦点图会被拒绝”，现在改成 `acceptsAnyNumberOfSpotlightsAndEvents`：在示例上再加 30 张焦点图和 30 个活动，必须能通过校验并全部保留。
- **Schema 和文档**：`docs/showcase/showcase.schema.json` 去掉了这两个列表的 `maxItems`；`docs/showcase/README.md` 的标题改成“数量不限”。
- **管理端** `ShowcaseEditor.vue`：去掉“最多 N 张”的说明和添加按钮的上限。
- **首页切换器** `HomeHero.vue`：焦点图超过 6 张时，改用紧凑翻页器，包括左右箭头、“03 / 12”、一条进度线和当前焦点图的名字。6 张及以下仍是每张一条带名字的进度线。
- **验证**：
  - 玩家端：`vitest` 108 个测试全部通过，新增翻页器测试；`vue-tsc` 通过。
  - 管理端：`vue-tsc` 通过，60 个测试全部通过。
  - Java：`protocol` 模块测试全部通过，`ShowcaseContractTest` 13 项。
  - 浏览器预览里看过 12 张焦点图时的翻页器。

要让服务器也用上这一项，需要重新构建并部署管理端：保存时由服务器上的后端校验。玩家端也要重新构建，提高版本号再发布：玩家端的兜底清理原来会截掉第 7 张起的焦点图。

---

## 17. 第七轮：改动页显示设计改动，服务器地址位置由服主选择（2026-10-24）

服主在服务器上的新版管理端里试用后提了三个问题。代码已改、测试通过，**还没有部署**，也没有提交。

### 17.1 加了服务器卡片，预览里却没有

原因：服务器卡片原来只在“有服务器地址、社区按钮或在线状态”时才显示。服主的项目没填 Minecraft 服务器地址，预览里也没有在线状态，所以卡片整个不显示。

现在 `ServerCard.vue` 的规则是：

- 服主添加过卡片（`serverCard` 不为空）且没关掉，就一直显示，和地址无关；
- 没添加过卡片的老项目，仍然只在有地址或在线状态时显示，和以前一样；
- 预览里没填地址时，卡片提示“填写 Minecraft 服务器地址后，这里会显示在线人数和延迟”。

### 17.2 首页服务器地址的位置由服主选择

新增 `Branding.addressPlacement`，取值：

| 值 | 效果 |
|---|---|
| `CARD`（不填时也是这样） | 地址显示在服务器卡片里，整行点击复制 |
| `HERO` | 地址显示在每张焦点图的按钮旁（第五轮的做法） |
| `HIDDEN` | 首页不显示地址 |

无论选哪种，玩家端都照常用 `serverAddress` 检测在线人数和延迟。

- **协议**：`Branding` 末尾加了这个字段，并保留旧的 22 个参数的构造方法，调用方不用改。`PlayerPresentation`、`PlayerController.mergePresentation`、管理网页的保存接口、终端的 `project configure` 和交互式配置都会带上它。`ShowcaseRules` 只接受上面三个值。
  - 旧玩家端的 JSON 解析会忽略不认识的字段，所以旧版收到这个字段不会出错，只是按自己的方式显示。
- **玩家端**：`types.ts`、`showcaseRules.ts`（导入校验和兜底清理）、`stores/player.ts` 的 `displayBranding`、`HomeHero.vue`（只在 `HERO` 时显示复制按钮）、`ServerCard.vue`（新的 `.server-address` 一行）、`home.css`。
- **管理端**：“标题与品牌”里服务器地址下面加了“首页显示地址：服务器卡片 / 焦点图旁 / 不显示”，下方说明会随选择变化；选了服务器卡片但卡片已关闭时会提醒。“服务器卡片”分组里注明地址在“标题与品牌”里设置。
- **展示内容文件**：`showcase.schema.json` 和 `docs/showcase/README.md` 加了 `addressPlacement`。
- 操作记录里会显示“首页地址位置”的变化。

### 17.3 改动与发布页看不到玩家端设计的改动

原因：检查改动时只比较整合包文件，玩家端设计（标题、焦点图、服务器卡片、配色、页面、背景、展示图片、音乐）完全不在比较范围内，所以一直显示“与当前发布一致”。

- 新增 `management-core/.../PresentationChange.java`：把当前设计和最新发布版本里的设计逐项比较，按管理端的设计分组列出改了什么。
  - 空文字和空列表视为“未设置”；地址位置不填等于 `CARD`；展示图片只比较发布时会带上的那些（和 `PublishService.publishedImages` 的选择一致）。
  - 每项带 `live`：文字、焦点图、活动、服务器卡片、配色、页面保存后就通过 presentation 对玩家生效（`live=true`）；背景图片、展示图片、启动音乐只随发布版本给玩家（`live=false`）。
- `PublishPreview` 末尾加了 `presentationChanges`；以前保存的预览没有这个字段时按空列表处理。`ScanService` 生成预览时填入，网页接口 `previewView`、终端的 `project scan`、`project publish` 和交互式发布都会显示。
- 管理端 `ChangesView.vue`：在文件改动后面列出“玩家端设计 · 分组”，写明改了哪些项，标“已生效”或“待发布”，点“查看”跳到对应的设计分组。顶部多一个“玩家端设计”计数，底部状态栏和“待发布”状态也把它算进去。

### 17.4 管理桌面和网页端

两者用的是同一份工作区代码（`admin-desktop/src/workbench`，网页版由 `vite.web.config.ts` 打包）。但桌面端自带一份界面：连接服务器时显示的是桌面程序里打包的界面，不是服务器上的。所以桌面端要装同一版本，才和服务器上的网页管理一致。第六轮只部署了服务器上的终端管理端，没有构建桌面安装包。

### 17.5 验证

- 玩家端：`vitest` 109 个测试全部通过（新增地址位置测试），`vue-tsc` 通过。浏览器里看过地址在卡片里、在焦点图旁，以及加了卡片但没填地址三种情况。
- 管理端：`vue-tsc` 通过，60 个测试全部通过。改动页的新列表没有在浏览器里看过，需要接上后端才能看。
- Java：protocol 54 个、management-core 121 个、management-cli 48 个、player-app 53 个测试全部通过。新增 `PresentationChangeTest`（含扫描、发布后清空的完整流程），`ShowcaseContractTest` 增加错误的 `addressPlacement` 会被拒绝，`PlayerControllerTest` 检查合并时带上地址位置。

- 本机 C 盘只剩约 4 GB，`PlayerPackageServiceTest` 要求临时目录所在磁盘留出约 4.8 GB，会报“磁盘可用空间不足”。把 `TMP`、`TEMP` 指到 D 盘（例如 `management-core/target/test-tmp`）再跑即可。
- 改了玩家端或工作区代码后，management-cli 的构建会检查打包进去的界面是否过期，要按顺序重新构建：`npm --prefix player-ui run build`、`npm --prefix admin-desktop run build:web`、`npm --prefix admin-desktop run build`。这次已重新构建，三份界面的构建标识一致。

### 17.6 部署时注意

- 服务器上的玩家端已经是 0.3.0，同一版本号不能重复发布，所以这批改动要用更高的玩家端版本（例如 0.3.1）发布。
- 管理端也要重新部署，服务器上的后端才会认得 `addressPlacement`，并在改动页列出设计改动。

---

## 18. 第八轮：左上角图标、右侧卡片的收起与宽度、MOTD 换行（2026-10-25）

服主看了自己服务器上的新版玩家端后提的。代码已改、测试通过，**还没有部署**，也没有提交。

### 18.1 左上角图标

- 原来左上角是品牌名第一个字的方块。现在默认显示梦鱼更新器的图标：`player-ui/src/assets/dreamingfish-mark.svg`，复制自 `admin-desktop/src-tauri/icons/icon.svg`，改图标时两份要一起换。
- 新增 `Branding.brandLogoFile`，填“展示图片”里上传的文件名，放在 `addressPlacement` 后面。
  - 第 17 节新加的 23 个参数的构造方法没有发布过，所以直接改成了 24 个参数；旧的 22 个参数的兼容构造方法会把这两个新字段都设为空。
  - `ShowcaseRules` 会校验文件名，并把它算进 `referencedImageFiles`：发布时这张图会随版本一起给玩家。
  - 网页保存接口里，这个字段没传时保留原值，传空字符串表示清除。管理端保存时会把空值转成 `""`。
- **玩家端**：`TitleBar.vue` 在图片同步到本地后显示服主的图标，在那之前（包括还没发布时）显示梦鱼图标。
- **管理端**：“标题与品牌 → 窗口品牌”下面加了“左上角图标”选择框，空选项是“使用梦鱼更新器图标”。`ImageField.vue` 新增 `emptyLabel` 参数。
- 改动页把它列在“标题与品牌 · 左上角图标”（已生效），对应图片列在“展示图片”（待发布）。操作记录里也会显示。
- 展示内容文件的 Schema 和 README 都加了 `brandLogoFile`。

### 18.2 右侧卡片按实际高度收起

原来用 CSS 按窗口高度判断：高度不到 720 时，只要更新详情展开就隐藏 MOTD 和社区按钮，不管实际放不放得下。服主的窗口正好不到 720，所以服务器卡片一直被压缩。

- 新增 `components/HomeRail.vue`（包住服务器卡片、活动卡片、更新卡片）和 `lib/railFit.ts`。
  - 用 `ResizeObserver` 和 `MutationObserver` 监视卡片的实际大小。放不下时按顺序一项项收起，收到放得下为止，更新卡片始终完整显示：
    1. `status`：在线人数和延迟并到服务器卡片的标题行（`.server-inline-status`），原来的状态行隐藏；
    2. `address`：隐藏地址行；
    3. `event`：隐藏活动卡片；
    4. `links`：隐藏社区按钮；
    5. `motd`：隐藏 MOTD；
    6. `server`：隐藏整张服务器卡片。
  - 顺序是服主定的：先让服务器卡片自己变紧凑，尽量把活动卡片留住。
  - 每次测量都会逐步试一遍，期间会强制重新计算布局，但不会重绘，所以不会闪。
  - 收起状态写在 `.home-rail` 的 `data-fold` 上，例如 `data-fold="event links"`，由 `home.css` 里的选择器隐藏对应内容。
- 删掉了原来按窗口高度（720 和 640）隐藏内容的规则；矮窗口只保留缩小间距的样式。
- 实测（预览数据，更新详情展开）：
  - 1180×680：人数和延迟并到标题行，地址隐藏；活动卡片、社区按钮和 MOTD 都保留；
  - 1040×600：在上面的基础上再隐藏活动卡片；
  - 960×560：只剩更新卡片；
  - 1440×900 以上：什么都不收起；
  - 收起更新详情后：全部显示。

### 18.3 卡片宽度

- `home.css` 在 `.canvas` 上定义 `--rail-width: clamp(320px, 34%, 420px)`，右侧卡片和左侧焦点图的宽度都用它计算。
- 服主先要求最宽接近窗口一半，试过后觉得太宽，改成最宽 420px：原来 320px 和试过的 519px 之间。
- 960 宽时约 326px，1180 宽时约 401px，1236 宽以上都是 420px。

### 18.4 MOTD 换行

- `.server-motd` 不再只显示一行加省略号：会换行，最多 3 行，超出的部分在鼠标悬停提示里能看到完整内容。
- 服务器卡片顶部的图标和标题改成顶端对齐。

### 18.5 验证

- 玩家端：`vitest` 114 个测试全部通过，`vue-tsc` 通过。
  - 新增 `railFit.test.ts`（收起顺序）；
  - `HomeStage.test.ts` 新增两项：用模拟的布局高度检查收起结果；左上角在图片到达前显示梦鱼图标，到达后显示服主图标。
- 管理端：`vue-tsc` 通过，60 个测试全部通过。
- Java：`PresentationChangeTest` 新增左上角图标；`ShowcaseContractTest` 新增错误文件名会被拒绝；`PlayerControllerTest` 检查合并时带上图标。
- 已重新构建 player-ui、网页管理和桌面端的界面，三者的构建标识一致。
- 截图看过 960×560、1180×680（更新详情展开和收起）、1440×900、1600×900、1920×1080，以及长 MOTD 的情况。

### 18.6 版本号 0.3.1

服主定为玩家 0.3.1 / 管理 0.3.1。用和第 16.1 节同一份清单改了 28 个文件：管理 CLI、桌面端、玩家端、打包脚本默认值、冒烟测试和发行包 README 模板。

- 测试里还有一些 `0.3.0`，它们是“最低玩家端版本”的测试数据，和程序版本无关，没有改。
- `CHANGELOG.md`、`README.md` 和 `docs/releases/` 还没写 0.3.0 和 0.3.1 的说明。
- 桌面安装包还没构建：桌面构建会签名，生成指向 GitHub Release `v0.3.1` 的自动更新清单，并从 CHANGELOG 读取这个版本的更新说明，属于正式发布流程。

### 18.7 部署到服主的服务器（0.3.1）

服主要求部署：更新管理端，并发布玩家端 0.3.1。步骤和第 16.3 节一样，另外注意：

- 本地构建时，Maven 全部测试通过：6 个模块共 351 个，跳过 1 个。
- 服务器上没有 `unzip` 时，可以用 `python3 -m zipfile -e` 解压。这种方式不保留可执行权限，要给 `dfs-admin` 和 `runtime/bin/*` 补上。
- 停服务时按监听端口找进程，并等端口释放、进程真正退出。终端会话里退出的 Java 进程可能残留成僵尸进程，只看进程号是否存在会误判。
- 玩家端 ZIP 里，作为 `--source` 的是 `DreamingFishUpdater/app/<版本>/` 这个目录：共 106 个文件，`DreamingFishUpdater.exe` 在根目录。
- 核对结果：整合包清单和 presentation 与部署前逐字节相同；玩家端清单是 0.3.1，106 个文件，最低 Bootstrap 0.1.2；服务器上网页管理加载的界面资源和本地 `web-dist` 的文件名一致。

已有项目的设计数据里没有 `addressPlacement`，所以 0.3.1 玩家端会把服务器地址放进服务器卡片；0.3.0 是放在焦点图旁。服主想换位置，可以在“标题与品牌”里选。

---

## 19. 启动音乐重播三次（2026-10-25）

### 19.1 启动音乐每次打开会从头播三次

原因：玩家端程序每次启动会把品牌信息发给界面三次，都经过 `PlayerController.applyActiveBranding()`：

1. 启动时，用本地缓存的 presentation；
2. 从服务器取到最新 presentation 后；
3. 整合包更新完成、拿到发布版本的品牌信息后。

而 `stores/player.ts` 的 `setBranding()` 只要播放列表不为空，每次都会先 `stopStartupMusic()` 再 `initializeStartupMusic()`，所以音乐会被从头重播三次。这个问题和第五轮以后的界面改动无关，以前的版本也有。

修复：

- `setBranding()` 先比较新旧播放列表（每首的 `id` 和 `fileName`），只有列表真的变了才停止并重新播放；
- 列表没变时只调用 `initializeStartupMusic()`。它在已经有音乐播放时什么都不做；开头因为文件还没下载而没能播放时，这时可以补上。

测试：`startupMusicReadiness.test.ts` 新增一项，同一个播放列表发三次，只创建一个音频、只播放一次；列表变化时会换成新的音乐。改回旧逻辑时这项测试会失败。

这个修复在玩家端里，服务器上的玩家端已经是 0.3.1，所以用 0.3.2 发布（见 19.3）。

### 19.2 服务器上的旧上传文件

服务器上历次部署留下的安装包和解压目录，已经由服主自己清理，这些都可以从源码重新构建。

### 19.3 发布玩家端 0.3.2

- 只改了玩家端的版本号，用 `bump_032_player` 同一份清单改了 11 个文件，管理端仍是 0.3.1。
- 用 `packaging/build-distributions.ps1 -PlayerOnly` 构建，protocol 54 个、update-engine 61 个、player-app 53 个测试全部通过。这个参数只替换玩家端的输出，`dist/` 里管理端 0.3.1 的包不受影响。
  - 得到 `dreamingfish-player-windows-x64-0.3.2.zip`，sha256 `7bc9580c…fe37`。
- 上传到服务器，核对校验值后解压。
- 发布命令：`./dfs-admin player publish <项目> --platform windows-x64 --version 0.3.2 --source <解压目录>/DreamingFishUpdater/app/0.3.2 --yes`。
  - `latest` 现在是 0.3.2，以前的版本都还在。
  - 管理端没有重启，下载服务没有中断。
- 通过玩家实际访问的地址核对：玩家端清单是 0.3.2，106 个文件；整合包清单和 presentation 与发布前逐字节相同。
- 删除了上传目录里解压出来的文件，只保留 ZIP。
- 回退：把 `player-programs/<项目>/windows-x64/latest` 改回 `0.3.1`，并把 `versions/0.3.2` 移走。已经升到 0.3.2 的玩家不会退回去。

---

## 20. 提交与 GitHub Release v0.3.1（2026-10-25）

- 提交 `f628eba`：`[玩家 - 0.3.2][管理 - 0.3.1] 全新玩家端首页，服主可自定义展示内容`，共 284 个文件。带注释的标签 `v0.3.1`，已推送到 `origin/main`。
- **没有提交**：`output/`、`tmp/`（使用手册 PDF 的产物）。本文件当时含有服主服务器的运行细节，没有随这次提交；删掉这些细节后再加入仓库。
- 文档：
  - `CHANGELOG.md` 新增 `[0.3.1]`。桌面构建会取它的第一段，作为程序内更新提示。
  - 发布说明是 `docs/releases/v0.3.1.md`，写的是 0.3 大版本的主要更新，按服主要求不单独写 0.3.2 的音乐修复。
  - 文中两张图是服主提供的示例截图，放在 `docs/releases/images/`，通过标签的 raw 地址显示。
  - `README.md`、`docs/CAPABILITIES.md`、`docs/ADMIN-DESKTOP.md` 的当前版本说明也一起更新了。
- 构建：
  - 先跑 `build-distributions.ps1`（完整模式，含 Maven 测试），再跑 `build-admin-desktop.ps1 -SkipBuildCore`；后者用默认位置的签名私钥签名，生成 `latest.json`。
  - 然后 `audit-distributions.py --include-desktop` 通过，没有问题；`smoke-test-distributions.ps1` 也通过。
- 发布：
  - 先跑 `publish-release.ps1 -Tag v0.3.1 -NotesFile docs/releases/v0.3.1.md -DryRun`，再正式发布；
  - 附件 7 个：桌面安装版、桌面便携版、Windows 和 Linux 终端版、玩家端 0.3.2、`latest.json`、`SHA256SUMS.txt`；
  - 校验值和本地 `dist/` 一致，`latest.json` 指向 v0.3.1 的安装包，脚本已确认能下载。
- 服主服务器上的管理端 0.3.1，是在音乐修复和文档更新之前构建的，和 Release 里的管理端功能相同。差别只在管理网页预览里内置的玩家端界面，不影响玩家。

