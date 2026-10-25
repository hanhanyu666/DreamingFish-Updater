import type { Branding, PlayerContentPage, ServerStatus } from "./types";

/**
 * Sample owner content for the browser preview, so every part of the showcase can be looked at
 * without a management service. Images are crops of the bundled background.
 */

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

function heroUrl(): string {
  const base = import.meta.env.BASE_URL ?? "/";
  return new URL(base + "images/hero-default.webp", window.location.href).toString();
}

function isoDate(offsetDays: number): string {
  return new Date(Date.now() - offsetDays * DAY).toISOString().slice(0, 10);
}

function previewPages(): PlayerContentPage[] {
  const cover = heroUrl();
  return [
    {
      id: "news",
      navigationLabel: "公告",
      announcementPage: true,
      layout: "ANNOUNCEMENTS",
      eyebrow: "SERVER NEWS",
      title: "服务器公告",
      lead: "赛季、活动与维护消息都会先发在这里。",
      markdown: "",
      articles: [
        {
          id: "season-3",
          category: "活动",
          author: "鲸歌运营组",
          title: "第三赛季「永夜之境」今晚开放",
          summary: "新维度、深海神殿与跨服交易所上线。老玩家背包保留，第二赛季的领地会保留到 11 月 30 日。",
          publishedOn: isoDate(1),
          coverUrl: cover,
          markdown: [
            "第三赛季「永夜之境」将于今晚 20:00 开放。这一季我们把重心放在探索和合作上：新维度不卖装备、不设排行榜，奖励只有外观和纪念品。",
            "> **开服信息**\n> 今晚 20:00 开放。整合包版本 1.20.1-r13，更新器会在启动时自动下载，不需要手动安装。",
            "## 这一季有什么",
            "- **永夜维度：**从主城中央的传送门进入，地表永远停在黄昏，越往深处越暗。\n- **深海神殿：**每周刷新一次的合作副本，最多 4 人组队。\n- **跨服交易所：**在主城银行挂单，下线以后也能成交。",
            `![永夜维度的入口，就在新主城中央广场](${cover})`,
            "## 旧存档怎么处理",
            "背包、末影箱与经济余额全部保留。第二赛季的领地在主世界保留到 11 月 30 日，之后整体迁移到「旧城」存档，可以随时传送回去参观。",
            "## 开服当晚",
            "20:00 在主城广场集合，管理组会带大家第一次进入永夜维度。全程大约一小时，参加的玩家会拿到第三赛季纪念披风。",
          ].join("\n\n"),
        },
        {
          id: "build-contest",
          category: "活动",
          author: "Kirin_Ra",
          title: "秋日建筑大赛开始报名",
          summary: "本期主题「湖畔小镇」。10 月 24 日晚开赛直播，31 日截止登记坐标，前三名作品永久保留在新主城。",
          publishedOn: isoDate(3),
          coverUrl: cover,
          markdown: [
            "一年一度的秋日建筑大赛又来了。这次的主题是「湖畔小镇」，单人或两人组队都可以，建筑区在新主城东侧湖边，已经提前清好了地。",
            "> **参赛方式**\n> 10 月 31 日 23:59 前，在主城报名处的告示牌写下你的地块编号和队友名字即可。",
            "## 评分看什么",
            "- **贴合主题：**像不像一座有人生活的湖边小镇。\n- **细节：**室内、灯光、花园和路，都算分。\n- **和邻居的关系：**能和旁边的作品连成一条街会加分。",
            "24 日晚 20:00 会在主城广场开赛直播，评委组现场讲一遍规则，也会展示去年的获奖作品。",
          ].join("\n\n"),
        },
        {
          id: "maintenance",
          category: "维护",
          author: "MossyStone",
          title: "10 月 29 日凌晨例行维护",
          summary: "03:00–05:00 停服更新服务端，期间无法进入服务器。背包与领地数据不受影响。",
          publishedOn: isoDate(4),
          coverUrl: cover,
          markdown: "> **维护时间**\n> 10 月 29 日（周四）03:00–05:00，预计提前结束。\n\n这次维护会更新服务端核心和两个性能相关的插件，不需要更新整合包。维护期间服务器无法进入，背包、领地与经济数据不受影响。\n\n维护结束后会在 QQ 群和这里同时通知。",
        },
        {
          id: "r12",
          category: "更新",
          author: "鲸歌运营组",
          title: "整合包 r12：性能优化与可选光影",
          summary: "光影包改为可选内容，低配电脑可以在「本地内容」里关掉，下次启动生效。",
          publishedOn: isoDate(7),
          coverUrl: cover,
          markdown: "r12 主要是性能优化。我们换掉了两个容易卡顿的模组，并把光影包改成了可选内容。\n\n## 改了什么\n\n- **可选光影：**默认开启。电脑带不动的话，在更新器「本地内容 › 可选内容」里关掉，下次启动就不会再下载。\n- **性能：**Sodium 升级到 0.5.11，大型红石区域帧数明显更稳。\n- **移除：**旧版小地图，改用 Xaero 小地图，原路径点会自动迁移。",
        },
        {
          id: "rules",
          category: "规则",
          author: "阿鲸",
          title: "服务器规则更新",
          summary: "补充了领地借用与红石机器的条款，进入服务器前请花一分钟看完。",
          publishedOn: isoDate(15),
          coverUrl: cover,
          markdown: "规则一共只有五条，这次补充的是两条大家问得最多的。完整版本在「服务器介绍 › 服务器规则」。\n\n## 新增的两条\n\n- **领地借用：**把领地借给朋友前，记得在领地设置里添加对方，否则对方拆东西会被系统拦下。\n- **红石机器：**刷怪塔与大型农场请建在野外区域，主城内每个区块最多 64 个漏斗。",
        },
      ],
    },
    {
      id: "guide",
      navigationLabel: "服务器介绍",
      announcementPage: false,
      layout: "BLOCKS",
      eyebrow: "SERVER GUIDE",
      title: "欢迎来到鲸歌",
      lead: "一个慢节奏的纯净生存服：不卖装备、不刷屏，周末一起搞点大工程。第一次来的话，花三分钟看完这一页。",
      markdown: "",
      articles: null,
      imageFile: "preview-guide.webp",
      tags: ["Java 1.20.1 · Fabric", "正版验证", "每周六活动夜", "第三赛季进行中"],
      blocks: [
        {
          id: "features",
          kind: "CARDS",
          title: "玩法特色",
          eyebrow: "FEATURES",
          columns: 2,
          items: [
            { id: "f1", icon: "shield", title: "领地保护", text: "用金锄头圈地，添加好友就能一起建。领地里的方块、箱子和动物都受保护。" },
            { id: "f2", icon: "users", title: "玩家经济", text: "没有官方商店，物价全由玩家在交易所里决定。下线以后挂单照样成交。" },
            { id: "f3", icon: "sword", title: "赛季副本", text: "每个赛季开放一个新维度和合作副本，奖励只有外观，不影响平衡。" },
            { id: "f4", icon: "calendar", title: "周六活动夜", text: "建筑赛、跑酷、烟花大会轮流来，每周六 20:00 在主城广场集合。" },
          ],
        },
        {
          id: "join",
          kind: "STEPS",
          title: "三步进服",
          eyebrow: "HOW TO JOIN",
          items: [
            { id: "s1", title: "等更新器跑完", text: "用你常用的启动器打开游戏，这个窗口会自己检查并补齐整合包。" },
            { id: "s2", title: "添加服务器", text: "在「多人游戏」里添加下面这个地址。", meta: "play.example.com" },
            { id: "s3", title: "找向导领礼包", text: "出生点右手边的向导会带你走一遍主城，并送一套新手工具。" },
          ],
        },
        {
          id: "rules",
          kind: "LIST",
          title: "服务器规则",
          eyebrow: "RULES",
          lead: "只有五条，进服即视为同意。",
          items: [
            { id: "r1", title: "不使用外挂与透视材质", text: "包括 X 光材质、自动钓鱼、飞行等任何客户端作弊。", meta: "违反即封禁" },
            { id: "r2", title: "不破坏别人的建筑", text: "没被添加为领地成员时，任何拆改都算破坏，记得先打招呼。" },
            { id: "r3", title: "红石机器建在野外", text: "主城每个区块最多 64 个漏斗，刷怪塔与大型农场请建在野外区域。" },
            { id: "r4", title: "交易讲诚信", text: "交易所之外的私下交易请截图留证，出现纠纷以截图为准。" },
            { id: "r5", title: "友善聊天", text: "不刷屏、不人身攻击，不在公屏发广告。" },
          ],
        },
        {
          id: "team",
          kind: "TEAM",
          title: "管理团队",
          eyebrow: "TEAM",
          columns: 4,
          items: [
            { id: "t1", title: "阿鲸", meta: "服主", text: "服务器运营与赛季规划，规则的最终解释人。" },
            { id: "t2", title: "Kirin_Ra", meta: "建筑组", text: "新主城建设，建筑大赛的评审。" },
            { id: "t3", title: "小满", meta: "活动策划", text: "每周六活动夜和节日活动。" },
            { id: "t4", title: "MossyStone", meta: "技术维护", text: "服务端、插件与整合包的更新。" },
          ],
        },
        {
          id: "faq",
          kind: "FAQ",
          title: "常见问题",
          eyebrow: "FAQ",
          items: [
            { id: "q1", title: "更新一直卡在下载怎么办？", text: "先点「重试」，更新器会从断点继续下载。还是不行的话，点「运行记录」截个图发到 QQ 群，管理组会帮你看。" },
            { id: "q2", title: "我自己加的模组会被删掉吗？", text: "不会。你自己放进 mods 文件夹的模组会被保留，并标记为「自选模组」。只有在服务器禁用名单里的模组，进服时才会被拦下。" },
            { id: "q3", title: "电脑配置低，能玩吗？", text: "能。在更新器的「本地内容 › 可选内容」里关掉光影包和高清材质，下次启动时就不会再下载它们。" },
            { id: "q4", title: "一定要换启动器吗？", text: "不用。继续用你常用的启动器打开游戏就行，更新器会在游戏启动前自动运行，更新完会自己关掉。" },
          ],
        },
      ],
    },
    {
      id: "gallery",
      navigationLabel: "画廊",
      announcementPage: false,
      layout: "GALLERY",
      eyebrow: "PLAYER GALLERY",
      title: "玩家作品",
      lead: "每周由建筑组挑选，点开看大图和坐标",
      markdown: "",
      articles: null,
      gallery: [
        { id: "g1", title: "湖畔小镇全景", author: "鲸歌建筑组", category: "建筑", featured: true, caption: "第三赛季新主城的第一张全景，石桥连接着居住区和码头。", location: "X 0 · Z 0", imageFile: "preview-gallery-1.webp" },
        { id: "g2", title: "石拱桥", author: "Kirin_Ra", category: "建筑", caption: "用石砖和圆石墙一块块垒出来的三孔拱桥，桥下能过船。", location: "X 212 · Z -86", imageFile: "preview-gallery-2.webp" },
        { id: "g3", title: "山顶瞭望台", author: "阿柚", category: "风景", caption: "从这里能看到整个新手村，傍晚的时候最好看。", location: "X -140 · Z -512", imageFile: "preview-gallery-3.webp" },
        { id: "g4", title: "秋收麦田", author: "小满", category: "风景", caption: "麦田旁边的木栅栏，是全服玩家一起补完的。", location: "X 96 · Z 140", imageFile: "preview-gallery-4.webp" },
        { id: "g5", title: "日出时的湖面", author: "阿柚", category: "风景", caption: "早上六点上线的人才看得到的颜色。", location: "X -320 · Z 64", imageFile: "preview-gallery-5.webp" },
        { id: "g6", title: "灯笼小径", author: "MossyStone", category: "建筑", caption: "夜里沿着灯笼一直走，就能回到出生点。", location: "X 248 · Z 188", imageFile: "preview-gallery-6.webp" },
        { id: "g7", title: "夜里的小屋", author: "Kirin_Ra", category: "建筑", caption: "主城第一栋双层木屋，屋顶花了整整两个周末。", location: "X 305 · Z -12", imageFile: "preview-gallery-7.webp" },
      ],
    },
  ];
}

export function previewBranding(): Branding {
  return {
    productName: "鲸歌生存服",
    subtitle: "第三赛季 · 永夜之境",
    serverAddress: "play.example.com",
    coverObject: null,
    accentColor: "#2ee8df",
    secondaryAccentColor: "#b06cff",
    welcomeText: "欢迎回来",
    brandName: "鲸歌生存服",
    brandEnglishName: "WHALESONG",
    newsArticles: null,
    customPage: null,
    contentPages: previewPages(),
    // Names only: the preview has no audio files, so play and pause just show the state.
    musicTracks: [
      { id: "whalefall", title: "鲸落 · 夜曲", fileName: "whalefall.ogg" },
      { id: "tide", title: "潮汐", fileName: "tide.ogg" },
      { id: "lantern", title: "灯笼小径", fileName: "lantern-path.ogg" },
    ],
    theme: { corner: "ROUND", surface: "GLASS" },
    spotlights: [
      {
        id: "season", tag: "新赛季", eyebrow: "SEASON 03 · NOW OPEN", title: "永夜之境", label: "赛季开放",
        lead: "第三赛季今晚 20:00 开放。深海神殿、全新附魔台与跨服交易所同步上线，老玩家背包全部保留。",
        imageFile: "preview-spotlight-1.webp",
        action: { label: "查看赛季详情", kind: "ARTICLE", pageId: "news", articleId: "season-3" },
      },
      {
        id: "contest", tag: "活动", eyebrow: "BUILD CONTEST", title: "秋日建筑大赛", label: "建筑大赛",
        lead: "本期主题「湖畔小镇」。10 月 31 日前在主城报名处登记坐标，前三名作品会永久保留在新主城。",
        imageFile: "preview-spotlight-2.webp",
        action: { label: "了解参赛规则", kind: "ARTICLE", pageId: "news", articleId: "build-contest" },
      },
      {
        id: "newcomer", tag: "新手", eyebrow: "FIRST STEPS", title: "第一次来鲸歌？", label: "新手指南",
        lead: "三分钟看懂领地、传送与经济。出生点的向导会带你走完第一天，记得先领新手工具包。",
        imageFile: "preview-spotlight-3.webp",
        action: { label: "阅读新手指南", kind: "PAGE", pageId: "guide" },
      },
      {
        id: "maintenance", tag: "维护", eyebrow: "MAINTENANCE", title: "周四凌晨维护", label: "例行维护",
        lead: "03:00–05:00 停服更新服务端，期间无法进入。背包与领地数据不受影响。",
        imageFile: "preview-spotlight-4.webp",
        action: { label: "查看维护公告", kind: "ARTICLE", pageId: "news", articleId: "maintenance" },
      },
    ],
    serverCard: {
      enabled: true,
      showStatus: true,
      versionLabel: "1.20.1 · Fabric",
      motd: null,
      logoFile: null,
      links: [
        { id: "qq", kind: "QQ", label: "QQ 群", copyText: "123456789" },
        { id: "discord", kind: "DISCORD", label: "Discord", url: "https://discord.com/" },
        { id: "site", kind: "WEBSITE", label: "官网", url: "https://example.com/" },
      ],
    },
    events: [
      {
        id: "contest-live",
        title: "建筑大赛 · 开赛直播",
        startsAt: new Date(Date.now() + 2 * DAY + 6 * HOUR + 18 * 60 * 1000).toISOString(),
        action: { label: "活动详情", kind: "ARTICLE", pageId: "news", articleId: "build-contest" },
      },
    ],
  };
}

export function previewServerStatus(): ServerStatus {
  return {
    state: "ONLINE",
    onlinePlayers: 33 + Math.floor(Math.random() * 9),
    maxPlayers: 120,
    latencyMs: 24 + Math.floor(Math.random() * 12),
    motd: "§b鲸歌 §7· §6第三赛季 永夜之境§r §f今晚开放\n§7白天建造，夜里出海",
    version: "1.20.1",
    favicon: null,
    checkedAt: new Date().toISOString(),
  };
}

/** Crops of the bundled background, one per sample file name, drawn once and cached. */
const CROPS: Record<string, { x: number; y: number; w: number; h: number; filter: string; width: number }> = {
  "preview-spotlight-1.webp": { x: 0, y: 0, w: 1, h: 1, filter: "brightness(.62) saturate(.75) hue-rotate(185deg) contrast(1.1)", width: 1600 },
  "preview-spotlight-2.webp": { x: 0.42, y: 0.28, w: 0.58, h: 0.58, filter: "saturate(1.15) sepia(.18) contrast(1.04)", width: 1600 },
  "preview-spotlight-3.webp": { x: 0.18, y: 0.4, w: 0.5, h: 0.5, filter: "saturate(1.05)", width: 1600 },
  "preview-spotlight-4.webp": { x: 0, y: 0.18, w: 0.62, h: 0.62, filter: "saturate(.85) brightness(.88) hue-rotate(-12deg)", width: 1600 },
  "preview-guide.webp": { x: 0.15, y: 0.2, w: 0.74, h: 0.6, filter: "saturate(1.05)", width: 1600 },
  "preview-gallery-1.webp": { x: 0.45, y: 0.35, w: 0.42, h: 0.42, filter: "saturate(1.1)", width: 900 },
  "preview-gallery-2.webp": { x: 0, y: 0.45, w: 0.5, h: 0.42, filter: "sepia(.35) saturate(1.3) hue-rotate(-18deg)", width: 900 },
  "preview-gallery-3.webp": { x: 0.62, y: 0.3, w: 0.36, h: 0.5, filter: "saturate(1.05) brightness(1.02)", width: 900 },
  "preview-gallery-4.webp": { x: 0.25, y: 0.5, w: 0.45, h: 0.38, filter: "brightness(.55) saturate(.7) hue-rotate(200deg)", width: 900 },
  "preview-gallery-5.webp": { x: 0.05, y: 0.62, w: 0.4, h: 0.32, filter: "saturate(1.25) contrast(1.08)", width: 900 },
  "preview-gallery-6.webp": { x: 0.3, y: 0.05, w: 0.42, h: 0.42, filter: "saturate(.95)", width: 900 },
  "preview-gallery-7.webp": { x: 0.55, y: 0.1, w: 0.45, h: 0.45, filter: "brightness(.92) saturate(.55) contrast(.92)", width: 900 },
};

const cropped = new Map<string, Promise<string | null>>();

export function mockPresentationImage(fileName: string): Promise<string | null> {
  const crop = CROPS[fileName];
  if (crop == null || typeof document === "undefined") return Promise.resolve(null);
  let pending = cropped.get(fileName);
  if (pending == null) {
    pending = new Promise<string | null>((resolve) => {
      const image = new Image();
      image.onload = () => {
        try {
          const sw = image.naturalWidth * crop.w;
          const sh = image.naturalHeight * crop.h;
          const canvas = document.createElement("canvas");
          canvas.width = crop.width;
          canvas.height = Math.round(crop.width * (sh / sw));
          const context = canvas.getContext("2d");
          if (context == null) return resolve(null);
          context.filter = crop.filter;
          context.drawImage(image, image.naturalWidth * crop.x, image.naturalHeight * crop.y, sw, sh,
            0, 0, canvas.width, canvas.height);
          resolve(canvas.toDataURL("image/webp", 0.86));
        } catch {
          resolve(null);
        }
      };
      image.onerror = () => resolve(null);
      image.src = heroUrl();
    });
    cropped.set(fileName, pending);
  }
  return pending;
}
