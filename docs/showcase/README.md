# 玩家端展示内容规范（服主自定义规则）

玩家端更新器首页和各个页面上显示的服务器内容，都写在一份**展示内容文件**里（JSON 格式）。你可以在管理端里一项项填写，也可以直接编辑这份文件，或者让 AI 帮你写，写好后在管理端导入、预览、保存。

- 完整示例模板：[`example-whalesong.json`](example-whalesong.json)（虚构的"鲸歌生存服"，和玩家端浏览器预览里看到的内容完全一致）
- 格式校验文件：[`showcase.schema.json`](showcase.schema.json)（JSON Schema。VS Code 等编辑器会根据它自动提示和检查）

## 1. 规则只有一条：只写内容，不写代码

玩家端**不会**显示你写的 HTML、CSS 或脚本。界面上的每个部分都来自玩家端内置的组件，你能决定的是：

1. **内容**：标题、简介、图片、按钮去向等；
2. **用哪种组件**：首页焦点图、服务器卡、活动、10 种内容块、画廊、公告；
3. **组件提供的选项**：列数、色调、图标、按钮；
4. **全局主题**：项目配色、圆角风格、卡片质感。

这样做的好处：无论谁来写、写得多随意，界面都不会错位，也不会出现看不清的配色，更不会有安全问题。写错的地方，管理端导入时会逐条告诉你哪里不对。

## 2. 文件结构

```json
{
  "format": "dreamingfish-showcase",
  "version": 1,

  "productName": "鲸歌生存服",
  "subtitle": "第三赛季 · 永夜之境",
  "welcomeText": "欢迎回来",
  "serverAddress": "play.example.com",
  "addressPlacement": "CARD",
  "brandName": "鲸歌生存服",
  "brandEnglishName": "WHALESONG",
  "brandLogoFile": "logo.png",
  "accentColor": "#2ee8df",
  "secondaryAccentColor": "#b06cff",
  "titleColor": "#fff8dc",
  "topBarColor": "#030708",
  "topBarOpacity": 0.22,
  "cardColor": "#030708",

  "theme": { "corner": "ROUND", "surface": "GLASS" },
  "spotlights": [ ... ],
  "serverCard": { ... },
  "events": [ ... ],
  "contentPages": [ ... ]
}
```

除 `format` 和 `version` 外，每一项都可以省略，省略的部分沿用管理端里已有的设置。背景图和启动音乐不放在这个文件里，仍然在管理端里单独上传。

`brandLogoFile` 是窗口左上角的图标，填上传的图片文件名（见第 6 节），建议用方形图片；不填时显示梦鱼更新器的图标。

`addressPlacement` 决定首页在哪里显示服务器地址：`CARD` 显示在服务器卡里（不填时也是这样），`HERO` 显示在焦点图按钮旁，`HIDDEN` 不在首页显示。无论选哪种，玩家端都会用 `serverAddress` 检测在线人数和延迟。

## 3. 首页

### 3.1 焦点图 `spotlights`（数量不限）

首页左侧轮播，每 7 秒切换一次。不填的话，首页会显示欢迎标题和最新的 3 篇公告。

| 字段 | 说明 |
|---|---|
| `id` | 唯一编号，只用小写字母、数字和连字符 |
| `tag` | 小标签，如"新赛季"，最多 24 字 |
| `label` | 首页底部切换器上的短名字，如"建筑大赛"，最多 24 字。不填时用标题 |
| `eyebrow` | 眉题，如"SEASON 03 · NOW OPEN"，最多 40 字 |
| `title` | 大标题，**必填**，最多 60 字 |
| `lead` | 简介，最多 300 字 |
| `imageFile` / `imageUrl` | 背景图（见第 6 节）。不填时使用项目背景 |
| `action` | 按钮（见第 5 节） |

### 3.2 服务器卡 `serverCard`

| 字段 | 说明 |
|---|---|
| `enabled` | 是否显示服务器卡 |
| `showStatus` | 是否让玩家端检测服务器，显示在线人数、延迟和 MOTD |
| `versionLabel` | 版本标签，如"1.20.1 · Fabric"。不填时用服务器报告的版本 |
| `motd` | 自定义 MOTD，可以用 `§` 颜色码，最多两行。不填时用服务器报告的 MOTD |
| `logoFile` | 服务器图标（上传的文件名）。不填时用服务器图标，再没有就用名称首字 |
| `links` | 社区按钮，最多 4 个 |

社区按钮：`{ "id", "kind", "label", "url" 或 "copyText" }`。`kind` 可选 `QQ`、`DISCORD`、`KOOK`、`BILIBILI`、`WEBSITE`、`OTHER`，决定图标。填 `url` 时点击打开链接；只填 `copyText`（比如 QQ 群号）时点击复制。

### 3.3 活动 `events`（数量不限）

首页只显示**下一场还没结束**的活动，并实时倒计时。

| 字段 | 说明 |
|---|---|
| `id`、`title` | 编号和名称 |
| `startsAt` | 开始时间，带时区，如 `2026-10-24T20:00:00+08:00` |
| `endsAt` | 结束时间，可省略（按开始后 2 小时计） |
| `action` | 点击活动卡时去哪里 |

## 4. 页面 `contentPages`（最多 12 个）

每个页面都会出现在顶部导航里。通用字段：`id`、`navigationLabel`（导航名，最多 24 字）、`eyebrow`、`title`、`lead`、`layout`。

| `layout` | 页面 | 内容写在 |
|---|---|---|
| `ANNOUNCEMENTS` | 公告（左边头条、右边按日期排列） | `articles`：`{ id, title, summary, publishedOn: "2026-10-21", coverUrl, markdown, category, author }`。`category`（如"活动"、"维护"）用来分类筛选，`author` 显示在日期旁边 |
| `MARKDOWN` | 一整篇文章 | `markdown` |
| `GALLERY` | 画廊（拼贴墙、分类筛选、点开看大图） | `gallery`：`{ id, title, author, category, caption, location, featured, imageFile 或 imageUrl }`，最多 60 张。`featured: true` 会在图上标“本周精选” |
| `BLOCKS` | 内容块页面（介绍、规则、团队、问答……） | `blocks`，最多 20 块；顶部大图用页面自己的 `imageFile` 或 `imageUrl`（不填用项目背景），`tags` 是标题下最多 6 个小标签 |

公告页还要写 `"announcementPage": true`，其他页面写 `false`（兼容旧版玩家端）。

### 4.1 内容块 `blocks`

每块都可以写 `id`、`kind`、`title`、`lead`、`navLabel`（页面左侧目录里的名字）、`eyebrow`（标题上方的英文小字，如"FEATURES"，不填按种类显示默认值）、`tone`（色调）。`items` 每项都是 `{ id, title, text, meta, icon, imageFile 或 imageUrl, action }`，不同的块用到其中不同的字段：

| `kind` | 显示成 | 用到的字段 |
|---|---|---|
| `TEXT` | 一段图文 | `markdown`、`actions` |
| `CARDS` | 卡片网格 | `columns`（1–4）；每项 `icon` 或图片、`title`、`text`、`action` |
| `STEPS` | 编号步骤 | 每项 `title`、`text`；`meta` 会显示成一个可以点击复制的框，适合放服务器地址 |
| `LIST` | 编号条目（适合规则） | 每项 `title`、`text`；`meta` 会显示成右侧的醒目标记，如"违反即封禁" |
| `TEAM` | 成员卡 | `columns`；每项 `title`（名字）、`meta`（职务，写"服主"时显示成金色）、`text`（介绍）、可选头像图片，没有图片时按名字生成 Minecraft 风格的像素脸 |
| `FAQ` | 可展开的问答 | 每项 `title`（问题）、`text`（回答） |
| `STATS` | 大数字 | `columns`；每项 `title`（数字，如"120+"）、`text`（说明）、`meta`（补充） |
| `CALLOUT` | 醒目横幅 | `markdown`、`actions`（最多 3 个）、可选背景图 |
| `IMAGE` | 一张宽图 | 图片、`lead` 作为图片说明 |
| `GALLERY` | 小画廊 | `columns`；每项图片、`title`、`meta`（作者） |

- **色调 `tone`**：`DEFAULT`、`ACCENT`（强调色）、`SECONDARY`（辅助强调色）、`WARM`（暖金色）、`MUTED`（低调灰）。所有色调都由项目配色派生，保证文字清楚。
- **图标 `icon`**：`star` `shield` `users` `chat` `globe` `calendar` `pin` `image` `sparkle` `sword` `pickaxe` `heart` `home` `gift` `trophy` `book` `clock` `check` `info` `signal`。
- **Markdown**：支持标题、段落、列表、粗体、斜体、链接、图片、引用和代码。不支持 HTML，写了也会原样显示成文字。

## 5. 按钮 `action`

```json
{ "label": "查看赛季详情", "kind": "ARTICLE", "pageId": "news", "articleId": "season-3" }
{ "label": "阅读新手指南", "kind": "PAGE", "pageId": "guide" }
{ "label": "加入 Discord", "kind": "URL", "url": "https://discord.gg/xxxx" }
```

`PAGE` 和 `ARTICLE` 必须指向文件里确实存在的页面和公告；`URL` 只能用 `http://` 或 `https://` 开头的地址。`label` 最多 24 字，可以不填，玩家端会用默认文字。

## 6. 图片

- `imageFile`：在管理端上传的图片文件名，如 `spotlight-season.webp`。只能用字母、数字、点、下划线、连字符，扩展名为 png、jpg、jpeg、webp、gif，单张不超过 8 MiB。上传的图片会和整合包一起发布，玩家离线也能看到。
- `imageUrl`：公开图片地址（http/https）。玩家网络不通时会看不到。
- 两个都填时用 `imageFile`。
- 尺寸建议：焦点图 1600×1000 以上；画廊 1200 像素宽左右；成员头像为正方形。

## 7. 长度上限

| 内容 | 上限 |
|---|---|
| 按钮文字、标签、导航名 | 24 字 |
| 标题 | 60 字 |
| 眉题、`meta` | 40 字 |
| 简介 `lead`、公告摘要 | 300 字 |
| 条目正文 `text` | 600 字 |
| Markdown | 20000 字 |
| 每块条目 `items` | 24 项 |
| 颜色 | `#rrggbb` |

超出上限时，管理端会拒绝导入并告诉你是哪一项。

## 8. 让 AI 帮你写

把下面这段话连同 [`showcase.schema.json`](showcase.schema.json) 和 [`example-whalesong.json`](example-whalesong.json) 一起发给 AI（ChatGPT、Claude、Kimi 等都可以），再在后面补上你的服务器介绍：

```text
请帮我写一份 Minecraft 服务器的「梦鱼更新器展示内容文件」。
要求：
1. 只输出一个 JSON 对象，不要输出解释文字，也不要用代码块以外的格式。
2. 必须符合我附上的 showcase.schema.json，format 写 "dreamingfish-showcase"，version 写 1。
3. 参考 example-whalesong.json 的结构和写法，但内容全部换成我的服务器。
4. 只能使用规范里列出的组件、字段、图标和色调，不要发明新字段，不要写 HTML、CSS 或脚本。
5. 遵守长度上限：标题不超过 60 字，简介不超过 300 字，按钮文字不超过 24 字。
6. 所有按钮里的 pageId 和 articleId 都必须是文件里真实存在的页面和公告。
7. 图片先用占位文件名（例如 spotlight-1.webp），我之后在管理端上传同名图片。
8. 文案自然、简洁，像服主在跟玩家说话，不要堆砌形容词。

我的服务器情况如下：
（在这里写服务器名称、地址、版本、玩法特色、规则、团队成员、近期活动、社区群号等）
```

更省事的做法：在管理端"外观 → 导入与导出"里点"复制给 AI 的说明"，会把上面这段话、schema 和示例一起复制，粘贴给 AI 后补上服务器介绍即可。

拿到结果后，在管理端"外观 → 导入与导出"里粘贴。如果有不合规的地方，管理端会列出错误，比如 `contentPages[1].blocks[2].kind 不支持 "HERO"`。把错误原文发回给 AI，让它修改即可。
