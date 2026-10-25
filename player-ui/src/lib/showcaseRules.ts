import type {
  Branding,
  PlayerBlock,
  PlayerBlockItem,
  PlayerContentPage,
  PlayerEvent,
  PlayerGalleryItem,
  PlayerLinkTarget,
  PlayerServerCard,
  PlayerSpotlight,
  PlayerTheme,
} from "./types";

/**
 * The owner showcase format (docs/showcase): limits, an import validator that lists every problem
 * with its JSON path, and the player's lenient clean-up. Mirrors protocol ShowcaseRules.java.
 */

export const SHOWCASE_FORMAT = "dreamingfish-showcase";
export const SHOWCASE_VERSION = 1;

export const SHOWCASE_LIMITS = {
  label: 24,
  eyebrow: 40,
  title: 60,
  lead: 300,
  text: 600,
  motd: 200,
  copyText: 60,
  markdown: 20_000,
  url: 2048,
  serverAddress: 255,
  imageFile: 120,
  links: 4,
  pages: 12,
  articles: 100,
  gallery: 60,
  blocks: 20,
  items: 24,
  actions: 3,
  tags: 6,
} as const;

export const BLOCK_KINDS = ["TEXT", "CARDS", "STEPS", "LIST", "TEAM", "FAQ", "STATS", "CALLOUT", "IMAGE", "GALLERY"] as const;
export const BLOCK_TONES = ["DEFAULT", "ACCENT", "SECONDARY", "WARM", "MUTED"] as const;
export const BLOCK_ICONS = [
  "star", "shield", "users", "chat", "globe", "calendar", "pin", "image", "sparkle", "sword",
  "pickaxe", "heart", "home", "gift", "trophy", "book", "clock", "check", "info", "signal",
] as const;
export const PAGE_LAYOUTS = ["MARKDOWN", "ANNOUNCEMENTS", "GALLERY", "BLOCKS"] as const;
export const LINK_KINDS = ["PAGE", "ARTICLE", "URL"] as const;
export const COMMUNITY_KINDS = ["QQ", "DISCORD", "KOOK", "BILIBILI", "WEBSITE", "OTHER"] as const;
export const THEME_CORNERS = ["ROUND", "SOFT", "SQUARE"] as const;
export const THEME_SURFACES = ["GLASS", "SOLID", "CLEAR"] as const;
export const ADDRESS_PLACEMENTS = ["CARD", "HERO", "HIDDEN"] as const;

/** The instructions docs/showcase/README.md gives owners to paste into an AI tool. */
export const SHOWCASE_AI_PROMPT = `请帮我写一份 Minecraft 服务器的「梦鱼更新器展示内容文件」。
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
（在这里写服务器名称、地址、版本、玩法特色、规则、团队成员、近期活动、社区群号等）`;

/** Branding fields an owner may describe in a showcase file; files and release metadata stay out. */
export const SHOWCASE_FIELDS = [
  "productName", "subtitle", "welcomeText", "serverAddress", "addressPlacement", "brandName", "brandEnglishName",
  "brandLogoFile",
  "accentColor", "secondaryAccentColor", "titleColor", "topBarColor", "topBarOpacity", "cardColor",
  "theme", "spotlights", "serverCard", "events", "contentPages",
] as const;

type ShowcaseField = (typeof SHOWCASE_FIELDS)[number];

export type ShowcaseDocument = {
  $schema?: string;
  format: typeof SHOWCASE_FORMAT;
  version: typeof SHOWCASE_VERSION;
} & Partial<Pick<Branding, ShowcaseField>>;

export interface ShowcaseIssue {
  /** JSON path such as contentPages[1].blocks[2].kind; "$" for the whole document. */
  path: string;
  message: string;
}

const ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const IMAGE_FILE = /^[A-Za-z0-9][A-Za-z0-9._-]*\.(png|jpe?g|webp|gif)$/;
const COLOR = /^#[0-9a-fA-F]{6}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;

// ---- Import validation -----------------------------------------------------------------------

type Json = Record<string, unknown>;

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function length(value: string): number {
  return Array.from(value).length;
}

function blank(value: unknown): boolean {
  return typeof value !== "string" || value.trim().length === 0;
}

function join(path: string, key: string): string {
  return path.length === 0 ? key : path + "." + key;
}

class Checker {
  readonly issues: ShowcaseIssue[] = [];

  fail(path: string, message: string): void {
    this.issues.push({ path: path.length === 0 ? "$" : path, message });
  }

  /** An object with only the listed fields; null (and one issue) otherwise. */
  object(value: unknown, path: string, allowed: readonly string[]): Json | null {
    if (!isObject(value)) {
      this.fail(path, "必须是一个对象");
      return null;
    }
    for (const key of Object.keys(value)) {
      if (!allowed.includes(key)) this.fail(join(path, key), "不是可识别的字段");
    }
    return value;
  }

  text(object: Json, key: string, path: string, maximum: number, required = false): void {
    const value = object[key];
    const at = join(path, key);
    if (value === undefined || value === null) {
      if (required) this.fail(at, "不能为空");
      return;
    }
    if (typeof value !== "string") {
      this.fail(at, "必须是文字");
      return;
    }
    if (required && value.trim().length === 0) this.fail(at, "不能为空");
    if (length(value) > maximum) this.fail(at, "不能超过 " + maximum + " 字");
  }

  bool(object: Json, key: string, path: string): void {
    const value = object[key];
    if (value !== undefined && value !== null && typeof value !== "boolean") {
      this.fail(join(path, key), "必须是 true 或 false");
    }
  }

  option(object: Json, key: string, path: string, allowed: readonly string[], required = false): void {
    const value = object[key];
    const at = join(path, key);
    if (value === undefined || value === null) {
      if (required) this.fail(at, "不能为空");
      return;
    }
    if (typeof value !== "string" || !allowed.includes(value)) {
      this.fail(at, "不支持 " + JSON.stringify(value));
    }
  }

  id(object: Json, key: string, path: string, seen?: Set<string>): void {
    const value = object[key];
    const at = join(path, key);
    if (typeof value !== "string" || !ID.test(value)) {
      this.fail(at, "必须是 1–64 位小写字母、数字或连字符，且以字母或数字开头");
      return;
    }
    if (seen != null) {
      if (seen.has(value)) this.fail(at, "标识不能重复");
      seen.add(value);
    }
  }

  color(object: Json, key: string, path: string): void {
    const value = object[key];
    if (value === undefined || value === null) return;
    if (typeof value !== "string" || !COLOR.test(value)) this.fail(join(path, key), "必须是 #rrggbb 格式的颜色");
  }

  url(object: Json, key: string, path: string): void {
    const value = object[key];
    if (value === undefined || value === null || value === "") return;
    const at = join(path, key);
    if (typeof value !== "string" || length(value) > SHOWCASE_LIMITS.url) {
      this.fail(at, "链接地址无效");
      return;
    }
    if (!publicHttpUrl(value)) this.fail(at, "仅支持不含账户信息的 http(s) 公开链接");
  }

  imageFile(object: Json, key: string, path: string): void {
    const value = object[key];
    if (value === undefined || value === null) return;
    if (typeof value !== "string" || value.length > SHOWCASE_LIMITS.imageFile || !IMAGE_FILE.test(value)
        || value.includes("..")) {
      this.fail(join(path, key), "必须是 120 字符以内的 PNG、JPEG、WebP 或 GIF 文件名");
    }
  }

  image(object: Json, path: string): void {
    this.imageFile(object, "imageFile", path);
    this.url(object, "imageUrl", path);
  }

  list(object: Json, key: string, path: string, maximum: number, each: (item: unknown, at: string) => void): void {
    const value = object[key];
    if (value === undefined || value === null) return;
    const at = join(path, key);
    if (!Array.isArray(value)) {
      this.fail(at, "必须是列表");
      return;
    }
    if (value.length > maximum) this.fail(at, "最多允许 " + maximum + " 项");
    value.forEach((item, index) => each(item, at + "[" + index + "]"));
  }
}

function publicHttpUrl(value: string): boolean {
  try {
    const uri = new URL(value);
    return (uri.protocol === "http:" || uri.protocol === "https:")
      && uri.hostname.length > 0 && uri.username === "" && uri.password === "";
  } catch {
    return false;
  }
}

function validTime(value: unknown): number | null {
  if (typeof value !== "string" || !DATE_TIME.test(value)) return null;
  const time = Date.parse(value);
  return Number.isNaN(time) ? null : time;
}

/** Pages by id, so buttons can be checked against what the document actually contains. */
interface PageIndex {
  pages: Map<string, { announcements: boolean; articles: Set<string> }>;
}

function indexPages(document: Json): PageIndex {
  const pages = new Map<string, { announcements: boolean; articles: Set<string> }>();
  const list = Array.isArray(document.contentPages) ? document.contentPages : [];
  for (const page of list) {
    if (!isObject(page) || typeof page.id !== "string") continue;
    const layout = typeof page.layout === "string" ? page.layout : null;
    const announcements = layout == null ? page.announcementPage === true : layout === "ANNOUNCEMENTS";
    const articles = new Set<string>(
      (Array.isArray(page.articles) ? page.articles : [])
        .filter(isObject)
        .map((article) => article.id)
        .filter((id): id is string => typeof id === "string"),
    );
    pages.set(page.id, { announcements, articles });
  }
  return { pages };
}

function checkAction(checker: Checker, value: unknown, path: string, index: PageIndex): void {
  if (value === undefined || value === null) return;
  const action = checker.object(value, path, ["label", "kind", "pageId", "articleId", "url"]);
  if (action == null) return;
  checker.text(action, "label", path, SHOWCASE_LIMITS.label);
  checker.option(action, "kind", path, LINK_KINDS, true);
  if (action.kind === "URL") {
    if (blank(action.url)) checker.fail(join(path, "url"), "请填写 http(s) 链接");
    else checker.url(action, "url", path);
    return;
  }
  if (action.kind !== "PAGE" && action.kind !== "ARTICLE") return;
  const page = typeof action.pageId === "string" ? index.pages.get(action.pageId) : undefined;
  if (page == null) {
    checker.fail(join(path, "pageId"), "指向的页面不存在");
    return;
  }
  if (action.kind === "ARTICLE") {
    if (!page.announcements) checker.fail(join(path, "pageId"), "指向的页面不是公告页");
    else if (typeof action.articleId !== "string" || !page.articles.has(action.articleId)) {
      checker.fail(join(path, "articleId"), "指向的公告不存在");
    }
  }
}

function checkSpotlight(checker: Checker, value: unknown, path: string, ids: Set<string>, index: PageIndex): void {
  const slide = checker.object(value, path, ["id", "tag", "label", "eyebrow", "title", "lead", "imageFile", "imageUrl", "action"]);
  if (slide == null) return;
  checker.id(slide, "id", path, ids);
  checker.text(slide, "tag", path, SHOWCASE_LIMITS.label);
  checker.text(slide, "label", path, SHOWCASE_LIMITS.label);
  checker.text(slide, "eyebrow", path, SHOWCASE_LIMITS.eyebrow);
  checker.text(slide, "title", path, SHOWCASE_LIMITS.title, true);
  checker.text(slide, "lead", path, SHOWCASE_LIMITS.lead);
  checker.image(slide, path);
  checkAction(checker, slide.action, join(path, "action"), index);
}

function checkServerCard(checker: Checker, value: unknown, path: string): void {
  if (value === undefined || value === null) return;
  const card = checker.object(value, path, ["enabled", "showStatus", "versionLabel", "motd", "logoFile", "links"]);
  if (card == null) return;
  checker.bool(card, "enabled", path);
  checker.bool(card, "showStatus", path);
  checker.text(card, "versionLabel", path, SHOWCASE_LIMITS.label);
  checker.text(card, "motd", path, SHOWCASE_LIMITS.motd);
  checker.imageFile(card, "logoFile", path);
  const ids = new Set<string>();
  checker.list(card, "links", path, SHOWCASE_LIMITS.links, (item, at) => {
    const link = checker.object(item, at, ["id", "kind", "label", "url", "copyText"]);
    if (link == null) return;
    checker.id(link, "id", at, ids);
    checker.option(link, "kind", at, COMMUNITY_KINDS, true);
    checker.text(link, "label", at, SHOWCASE_LIMITS.label);
    checker.text(link, "copyText", at, SHOWCASE_LIMITS.copyText);
    checker.url(link, "url", at);
    if (blank(link.url) && blank(link.copyText)) checker.fail(at, "需要提供链接地址或复制内容");
  });
}

function checkEvent(checker: Checker, value: unknown, path: string, ids: Set<string>, index: PageIndex): void {
  const event = checker.object(value, path, ["id", "title", "startsAt", "endsAt", "action"]);
  if (event == null) return;
  checker.id(event, "id", path, ids);
  checker.text(event, "title", path, SHOWCASE_LIMITS.title, true);
  const starts = validTime(event.startsAt);
  if (starts == null) checker.fail(join(path, "startsAt"), "必须是带时区的 ISO 8601 时间，例如 2026-10-24T20:00:00+08:00");
  if (event.endsAt !== undefined && event.endsAt !== null) {
    const ends = validTime(event.endsAt);
    if (ends == null) checker.fail(join(path, "endsAt"), "必须是带时区的 ISO 8601 时间，例如 2026-10-24T22:00:00+08:00");
    else if (starts != null && ends <= starts) checker.fail(join(path, "endsAt"), "必须晚于开始时间");
  }
  checkAction(checker, event.action, join(path, "action"), index);
}

function checkArticle(checker: Checker, value: unknown, path: string, ids: Set<string>): void {
  const article = checker.object(value, path, ["id", "title", "summary", "publishedOn", "coverUrl", "markdown", "category", "author"]);
  if (article == null) return;
  checker.id(article, "id", path, ids);
  checker.text(article, "category", path, SHOWCASE_LIMITS.label);
  checker.text(article, "author", path, SHOWCASE_LIMITS.eyebrow);
  checker.text(article, "title", path, SHOWCASE_LIMITS.title, true);
  checker.text(article, "summary", path, SHOWCASE_LIMITS.lead);
  checker.text(article, "markdown", path, SHOWCASE_LIMITS.markdown);
  checker.url(article, "coverUrl", path);
  const published = article.publishedOn;
  if (typeof published !== "string" || !DATE.test(published) || Number.isNaN(Date.parse(published))) {
    checker.fail(join(path, "publishedOn"), "必须是有效的 YYYY-MM-DD 日期");
  }
}

function checkGalleryItem(checker: Checker, value: unknown, path: string, ids: Set<string>): void {
  const image = checker.object(value, path, ["id", "title", "author", "category", "caption", "location", "featured", "imageFile", "imageUrl"]);
  if (image == null) return;
  checker.bool(image, "featured", path);
  checker.id(image, "id", path, ids);
  checker.text(image, "title", path, SHOWCASE_LIMITS.title, true);
  checker.text(image, "author", path, SHOWCASE_LIMITS.eyebrow);
  checker.text(image, "category", path, SHOWCASE_LIMITS.label);
  checker.text(image, "caption", path, SHOWCASE_LIMITS.lead);
  checker.text(image, "location", path, SHOWCASE_LIMITS.eyebrow);
  checker.image(image, path);
  if (blank(image.imageFile) && blank(image.imageUrl)) checker.fail(path, "需要提供图片文件或公开图片地址");
}

function checkBlock(checker: Checker, value: unknown, path: string, ids: Set<string>, index: PageIndex): void {
  const block = checker.object(value, path, [
    "id", "kind", "title", "lead", "navLabel", "eyebrow", "tone", "columns", "markdown", "imageFile", "imageUrl", "items", "actions",
  ]);
  if (block == null) return;
  checker.id(block, "id", path, ids);
  checker.option(block, "kind", path, BLOCK_KINDS, true);
  checker.option(block, "tone", path, BLOCK_TONES);
  const columns = block.columns;
  if (columns !== undefined && columns !== null
      && (typeof columns !== "number" || !Number.isInteger(columns) || columns < 1 || columns > 4)) {
    checker.fail(join(path, "columns"), "必须是 1–4 之间的整数");
  }
  checker.text(block, "title", path, SHOWCASE_LIMITS.title);
  checker.text(block, "lead", path, SHOWCASE_LIMITS.lead);
  checker.text(block, "navLabel", path, SHOWCASE_LIMITS.label);
  checker.text(block, "eyebrow", path, SHOWCASE_LIMITS.eyebrow);
  checker.text(block, "markdown", path, SHOWCASE_LIMITS.markdown);
  checker.image(block, path);
  const itemIds = new Set<string>();
  checker.list(block, "items", path, SHOWCASE_LIMITS.items, (item, at) => {
    const entry = checker.object(item, at, ["id", "title", "text", "meta", "icon", "imageFile", "imageUrl", "action"]);
    if (entry == null) return;
    checker.id(entry, "id", at, itemIds);
    checker.text(entry, "title", at, SHOWCASE_LIMITS.title, true);
    checker.text(entry, "text", at, SHOWCASE_LIMITS.text);
    checker.text(entry, "meta", at, SHOWCASE_LIMITS.eyebrow);
    checker.option(entry, "icon", at, BLOCK_ICONS);
    checker.image(entry, at);
    checkAction(checker, entry.action, join(at, "action"), index);
  });
  checker.list(block, "actions", path, SHOWCASE_LIMITS.actions, (action, at) => checkAction(checker, action, at, index));
}

function checkPage(checker: Checker, value: unknown, path: string, ids: Set<string>, index: PageIndex): void {
  const page = checker.object(value, path, [
    "id", "navigationLabel", "announcementPage", "layout", "eyebrow", "title", "lead", "markdown", "articles", "gallery", "blocks",
    "imageFile", "imageUrl", "tags",
  ]);
  if (page == null) return;
  checker.id(page, "id", path, ids);
  checker.text(page, "navigationLabel", path, SHOWCASE_LIMITS.label, true);
  checker.bool(page, "announcementPage", path);
  checker.option(page, "layout", path, PAGE_LAYOUTS);
  checker.text(page, "eyebrow", path, SHOWCASE_LIMITS.eyebrow);
  checker.text(page, "title", path, SHOWCASE_LIMITS.title, true);
  checker.text(page, "lead", path, SHOWCASE_LIMITS.lead);
  checker.text(page, "markdown", path, SHOWCASE_LIMITS.markdown);
  checker.image(page, path);
  checker.list(page, "tags", path, SHOWCASE_LIMITS.tags, (tag, at) => {
    if (typeof tag !== "string" || tag.trim().length === 0) checker.fail(at, "必须是不为空的文字");
    else if (length(tag) > SHOWCASE_LIMITS.label) checker.fail(at, "不能超过 " + SHOWCASE_LIMITS.label + " 字");
  });
  const articleIds = new Set<string>();
  checker.list(page, "articles", path, SHOWCASE_LIMITS.articles, (item, at) => checkArticle(checker, item, at, articleIds));
  const galleryIds = new Set<string>();
  checker.list(page, "gallery", path, SHOWCASE_LIMITS.gallery, (item, at) => checkGalleryItem(checker, item, at, galleryIds));
  const blockIds = new Set<string>();
  checker.list(page, "blocks", path, SHOWCASE_LIMITS.blocks, (item, at) => checkBlock(checker, item, at, blockIds, index));
}

/** Every problem in a showcase document, each with its JSON path and a message for the owner. */
export function validateShowcase(input: unknown): ShowcaseIssue[] {
  const checker = new Checker();
  const document = checker.object(input, "", ["$schema", "format", "version", ...SHOWCASE_FIELDS]);
  if (document == null) return checker.issues;
  if (document.format !== SHOWCASE_FORMAT) checker.fail("format", "必须是 \"" + SHOWCASE_FORMAT + "\"");
  if (document.version !== SHOWCASE_VERSION) checker.fail("version", "目前只支持 " + SHOWCASE_VERSION);
  const index = indexPages(document);

  checker.text(document, "productName", "", SHOWCASE_LIMITS.title);
  checker.text(document, "subtitle", "", SHOWCASE_LIMITS.lead);
  checker.text(document, "welcomeText", "", SHOWCASE_LIMITS.label);
  checker.text(document, "serverAddress", "", SHOWCASE_LIMITS.serverAddress);
  checker.option(document, "addressPlacement", "", ADDRESS_PLACEMENTS);
  checker.text(document, "brandName", "", SHOWCASE_LIMITS.label);
  checker.text(document, "brandEnglishName", "", SHOWCASE_LIMITS.label);
  checker.imageFile(document, "brandLogoFile", "");
  for (const key of ["accentColor", "secondaryAccentColor", "titleColor", "topBarColor", "cardColor"]) {
    checker.color(document, key, "");
  }
  const opacity = document.topBarOpacity;
  if (opacity !== undefined && opacity !== null && (typeof opacity !== "number" || opacity < 0 || opacity > 1)) {
    checker.fail("topBarOpacity", "必须是 0–1 之间的数字");
  }
  if (document.theme !== undefined && document.theme !== null) {
    const theme = checker.object(document.theme, "theme", ["corner", "surface"]);
    if (theme != null) {
      checker.option(theme, "corner", "theme", THEME_CORNERS);
      checker.option(theme, "surface", "theme", THEME_SURFACES);
    }
  }
  const slideIds = new Set<string>();
  // Spotlights and events have no count limit; only each entry is checked.
  checker.list(document, "spotlights", "", Number.POSITIVE_INFINITY,
    (item, at) => checkSpotlight(checker, item, at, slideIds, index));
  checkServerCard(checker, document.serverCard, "serverCard");
  const eventIds = new Set<string>();
  checker.list(document, "events", "", Number.POSITIVE_INFINITY, (item, at) => checkEvent(checker, item, at, eventIds, index));
  const pageIds = new Set<string>();
  checker.list(document, "contentPages", "", SHOWCASE_LIMITS.pages, (item, at) => checkPage(checker, item, at, pageIds, index));
  return checker.issues;
}

/** Parses pasted or imported text; a JSON syntax error is reported as one issue at "$". */
export function parseShowcase(text: string): { document: ShowcaseDocument | null; issues: ShowcaseIssue[] } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    return { document: null, issues: [{ path: "$", message: "不是有效的 JSON：" + (error as Error).message }] };
  }
  const issues = validateShowcase(parsed);
  return { document: issues.length === 0 ? (parsed as ShowcaseDocument) : null, issues };
}

/** The owner-editable part of a branding, ready to export or hand to an AI tool. */
export function showcaseFromBranding(branding: Branding): ShowcaseDocument {
  const document: ShowcaseDocument = { format: SHOWCASE_FORMAT, version: SHOWCASE_VERSION };
  const target = document as Record<string, unknown>;
  for (const key of SHOWCASE_FIELDS) {
    const value = branding[key];
    if (value !== undefined && value !== null) target[key] = structuredClone(value);
  }
  return document;
}

/** Applies an imported document; background, music and uploaded image identities are kept. */
export function applyShowcase(branding: Branding, document: ShowcaseDocument): Branding {
  const result: Branding = { ...branding };
  const target = result as unknown as Record<string, unknown>;
  for (const key of SHOWCASE_FIELDS) {
    if (key in document) target[key] = structuredClone((document as Record<string, unknown>)[key]);
  }
  return result;
}

// ---- Player clean-up -------------------------------------------------------------------------

function clip(value: string | null | undefined, maximum: number): string {
  if (value == null) return "";
  const characters = Array.from(value);
  return characters.length > maximum ? characters.slice(0, maximum).join("") : value;
}

function optionalClip(value: string | null | undefined, maximum: number): string | null {
  return value == null ? null : clip(value, maximum);
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | null {
  return typeof value === "string" && (allowed as readonly string[]).includes(value) ? (value as T) : null;
}

function cleanAction(action: PlayerLinkTarget | null | undefined): PlayerLinkTarget | null {
  if (action == null) return null;
  const kind = oneOf(action.kind, LINK_KINDS);
  return kind == null ? null : { ...action, kind, label: clip(action.label, SHOWCASE_LIMITS.label) };
}

function cleanSpotlight(slide: PlayerSpotlight): PlayerSpotlight {
  return {
    ...slide,
    tag: clip(slide.tag, SHOWCASE_LIMITS.label),
    label: optionalClip(slide.label, SHOWCASE_LIMITS.label),
    eyebrow: clip(slide.eyebrow, SHOWCASE_LIMITS.eyebrow),
    title: clip(slide.title, SHOWCASE_LIMITS.title),
    lead: clip(slide.lead, SHOWCASE_LIMITS.lead),
    action: cleanAction(slide.action),
  };
}

function cleanServerCard(card: PlayerServerCard): PlayerServerCard {
  return {
    ...card,
    enabled: card.enabled !== false,
    showStatus: card.showStatus !== false,
    versionLabel: optionalClip(card.versionLabel, SHOWCASE_LIMITS.label),
    motd: optionalClip(card.motd, SHOWCASE_LIMITS.motd),
    links: (card.links ?? [])
      .filter((link) => oneOf(link.kind, COMMUNITY_KINDS) != null)
      .slice(0, SHOWCASE_LIMITS.links)
      .map((link) => ({ ...link, label: clip(link.label, SHOWCASE_LIMITS.label) })),
  };
}

function cleanEvent(event: PlayerEvent): PlayerEvent {
  return { ...event, title: clip(event.title, SHOWCASE_LIMITS.title), action: cleanAction(event.action) };
}

function cleanItem(item: PlayerBlockItem): PlayerBlockItem {
  return {
    ...item,
    title: clip(item.title, SHOWCASE_LIMITS.title),
    text: optionalClip(item.text, SHOWCASE_LIMITS.text),
    meta: optionalClip(item.meta, SHOWCASE_LIMITS.eyebrow),
    icon: oneOf(item.icon, BLOCK_ICONS),
    action: cleanAction(item.action),
  };
}

function cleanBlock(block: PlayerBlock): PlayerBlock {
  const columns = typeof block.columns === "number" && Number.isFinite(block.columns)
    ? Math.min(4, Math.max(1, Math.round(block.columns))) : null;
  return {
    ...block,
    title: optionalClip(block.title, SHOWCASE_LIMITS.title),
    lead: optionalClip(block.lead, SHOWCASE_LIMITS.lead),
    navLabel: optionalClip(block.navLabel, SHOWCASE_LIMITS.label),
    eyebrow: optionalClip(block.eyebrow, SHOWCASE_LIMITS.eyebrow),
    tone: oneOf(block.tone, BLOCK_TONES),
    columns,
    markdown: optionalClip(block.markdown, SHOWCASE_LIMITS.markdown),
    items: (block.items ?? []).slice(0, SHOWCASE_LIMITS.items).map(cleanItem),
    actions: (block.actions ?? [])
      .map(cleanAction)
      .filter((action): action is PlayerLinkTarget => action != null)
      .slice(0, SHOWCASE_LIMITS.actions),
  };
}

function cleanGalleryItem(image: PlayerGalleryItem): PlayerGalleryItem {
  return {
    ...image,
    title: clip(image.title, SHOWCASE_LIMITS.title),
    author: clip(image.author, SHOWCASE_LIMITS.eyebrow),
    category: clip(image.category, SHOWCASE_LIMITS.label),
    caption: optionalClip(image.caption, SHOWCASE_LIMITS.lead),
    location: optionalClip(image.location, SHOWCASE_LIMITS.eyebrow),
    featured: image.featured === true,
  };
}

function cleanPage(page: PlayerContentPage): PlayerContentPage {
  return {
    ...page,
    layout: oneOf(page.layout, PAGE_LAYOUTS),
    tags: page.tags == null ? page.tags : page.tags
      .filter((tag) => typeof tag === "string" && tag.trim().length > 0)
      .slice(0, SHOWCASE_LIMITS.tags)
      .map((tag) => clip(tag, SHOWCASE_LIMITS.label)),
    articles: page.articles == null ? page.articles : page.articles.map((article) => ({
      ...article,
      category: optionalClip(article.category, SHOWCASE_LIMITS.label),
      author: optionalClip(article.author, SHOWCASE_LIMITS.eyebrow),
    })),
    gallery: page.gallery == null ? page.gallery : page.gallery.slice(0, SHOWCASE_LIMITS.gallery).map(cleanGalleryItem),
    blocks: page.blocks == null ? page.blocks : page.blocks
      .filter((block) => oneOf(block.kind, BLOCK_KINDS) != null)
      .slice(0, SHOWCASE_LIMITS.blocks)
      .map(cleanBlock),
  };
}

function cleanTheme(theme: PlayerTheme | null | undefined): PlayerTheme | null {
  if (theme == null) return null;
  return { corner: oneOf(theme.corner, THEME_CORNERS), surface: oneOf(theme.surface, THEME_SURFACES) };
}

/**
 * The player's safety net for showcase data that slipped past the admin checks (older admin builds,
 * hand-edited files): unknown options become defaults, lists and texts are cut to the limits, and
 * slides or events that cannot be shown are dropped. It never throws.
 */
export function normalizeShowcase(branding: Branding): Branding {
  return {
    ...branding,
    spotlights: branding.spotlights == null ? branding.spotlights : branding.spotlights
      .filter((slide) => !blank(slide.title))
      .map(cleanSpotlight),
    serverCard: branding.serverCard == null ? branding.serverCard : cleanServerCard(branding.serverCard),
    events: branding.events == null ? branding.events : branding.events
      .filter((event) => !blank(event.title) && validTime(event.startsAt) != null)
      .map(cleanEvent),
    theme: cleanTheme(branding.theme),
    addressPlacement: oneOf(branding.addressPlacement, ADDRESS_PLACEMENTS),
    contentPages: branding.contentPages == null ? branding.contentPages : branding.contentPages.map(cleanPage),
  };
}
