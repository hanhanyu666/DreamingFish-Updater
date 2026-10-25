import type { DeepReadonly } from "vue";
import { newsDateLabel } from "./news";
import type {
  Branding,
  PlayerCommunityKind,
  PlayerContentPage,
  PlayerEvent,
  PlayerImageSource,
  PlayerLinkTarget,
  PlayerPageLayout,
} from "./types";

/** Older presentations only know announcement pages and Markdown pages. */
export function pageLayout(page: Pick<PlayerContentPage, "layout" | "announcementPage">): PlayerPageLayout {
  if (page.layout === "GALLERY" || page.layout === "BLOCKS"
      || page.layout === "ANNOUNCEMENTS" || page.layout === "MARKDOWN") return page.layout;
  return page.announcementPage ? "ANNOUNCEMENTS" : "MARKDOWN";
}

export function safeHttpUrl(value: string | null | undefined): string {
  if (!value) return "";
  try {
    const uri = new URL(value.trim());
    return uri.protocol === "http:" || uri.protocol === "https:" ? uri.toString() : "";
  } catch {
    return "";
  }
}

export function hasImage(source: PlayerImageSource | null | undefined): boolean {
  return source != null && (Boolean(source.imageFile?.trim()) || safeHttpUrl(source.imageUrl) !== "");
}

// ---- Home carousel ---------------------------------------------------------------------------

export type HomeSlideKind = "WELCOME" | "SPOTLIGHT" | "ARTICLE";

export interface HomeSlide {
  id: string;
  kind: HomeSlideKind;
  tag: string;
  eyebrow: string;
  title: string;
  /** What the switcher under the copy calls this slide. */
  label: string;
  lead: string;
  /** Null shows the project background. */
  image: PlayerImageSource | null;
  action: PlayerLinkTarget | null;
}

const LATEST_ARTICLE_SLIDES = 3;

/**
 * The owner's spotlights; without any, the welcome title followed by the newest announcements,
 * so a project that never configured the carousel still has a lively home page.
 */
export function homeSlides(
  branding: DeepReadonly<Branding>,
  pages: readonly DeepReadonly<PlayerContentPage>[],
): HomeSlide[] {
  const spotlights = (branding.spotlights ?? []).filter((item) => item.title.trim().length > 0);
  if (spotlights.length > 0) {
    return spotlights.map((item) => ({
      id: "spotlight:" + item.id,
      kind: "SPOTLIGHT",
      tag: item.tag.trim(),
      eyebrow: item.eyebrow.trim(),
      title: item.title.trim(),
      label: item.label?.trim() || item.title.trim(),
      lead: item.lead.trim(),
      image: hasImage(item) ? { imageFile: item.imageFile, imageUrl: item.imageUrl } : null,
      action: usableAction(item.action, pages),
    }));
  }
  const welcome: HomeSlide = {
    id: "welcome",
    kind: "WELCOME",
    tag: "",
    eyebrow: branding.welcomeText ?? "",
    title: branding.productName,
    label: "欢迎",
    lead: branding.subtitle,
    image: null,
    action: null,
  };
  const articles = pages
    .filter((page) => pageLayout(page) === "ANNOUNCEMENTS")
    .flatMap((page) => (page.articles ?? []).map((article) => ({ page, article })))
    .filter(({ article }) => article.title.trim().length > 0)
    .sort((left, right) => right.article.publishedOn.localeCompare(left.article.publishedOn))
    .slice(0, LATEST_ARTICLE_SLIDES)
    .map<HomeSlide>(({ page, article }) => ({
      id: "article:" + page.id + ":" + article.id,
      kind: "ARTICLE",
      tag: page.navigationLabel,
      eyebrow: newsDateLabel(article.publishedOn),
      title: article.title,
      label: article.category?.trim() || article.title,
      lead: article.summary,
      image: safeHttpUrl(article.coverUrl) ? { imageUrl: article.coverUrl } : null,
      action: { label: "阅读全文", kind: "ARTICLE", pageId: page.id, articleId: article.id },
    }));
  return [welcome, ...articles];
}

const DEFAULT_ACTION_LABELS: Record<PlayerLinkTarget["kind"], string> = {
  PAGE: "查看详情",
  ARTICLE: "阅读全文",
  URL: "打开链接",
};

/** A button only when it leads somewhere that still exists. */
export function usableAction(
  action: DeepReadonly<PlayerLinkTarget> | null | undefined,
  pages: readonly DeepReadonly<PlayerContentPage>[],
): PlayerLinkTarget | null {
  if (action == null) return null;
  const label = action.label?.trim() || DEFAULT_ACTION_LABELS[action.kind] || "查看详情";
  if (action.kind === "URL") {
    const url = safeHttpUrl(action.url);
    return url ? { label, kind: "URL", url } : null;
  }
  const page = pages.find((candidate) => candidate.id === action.pageId);
  if (page == null) return null;
  if (action.kind === "ARTICLE") {
    if (!(page.articles ?? []).some((article) => article.id === action.articleId)) return null;
    return { label, kind: "ARTICLE", pageId: page.id, articleId: action.articleId };
  }
  return { label, kind: "PAGE", pageId: page.id };
}

// ---- Events --------------------------------------------------------------------------------

/** An event without an end counts as ongoing for this long after it starts. */
const DEFAULT_EVENT_MS = 2 * 60 * 60 * 1000;

export interface UpcomingEvent {
  event: DeepReadonly<PlayerEvent>;
  startsAt: Date;
  endsAt: Date;
  ongoing: boolean;
}

export function nextEvent(
  events: readonly DeepReadonly<PlayerEvent>[] | null | undefined,
  now: number,
): UpcomingEvent | null {
  const candidates = (events ?? [])
    .filter((event) => event.title.trim().length > 0)
    .map((event) => {
      const startsAt = new Date(event.startsAt);
      const end = event.endsAt ? new Date(event.endsAt) : null;
      const endsAt = end != null && !Number.isNaN(end.getTime()) && end.getTime() > startsAt.getTime()
        ? end
        : new Date(startsAt.getTime() + DEFAULT_EVENT_MS);
      return { event, startsAt, endsAt, ongoing: startsAt.getTime() <= now };
    })
    .filter((entry) => !Number.isNaN(entry.startsAt.getTime()) && entry.endsAt.getTime() > now)
    .sort((left, right) => left.startsAt.getTime() - right.startsAt.getTime());
  return candidates[0] ?? null;
}

function pad2(value: number): string {
  return String(value).padStart(2, "0");
}

/** 2 天 06:18:40, or 06:18:40 within a day. */
export function countdownLabel(milliseconds: number): string {
  const total = Math.max(0, Math.floor(milliseconds / 1000));
  const days = Math.floor(total / 86_400);
  const clock = pad2(Math.floor((total % 86_400) / 3600)) + ":" + pad2(Math.floor((total % 3600) / 60))
    + ":" + pad2(total % 60);
  return days > 0 ? days + " 天 " + clock : clock;
}

const WEEKDAYS = "日一二三四五六";

/** 10 月 24 日 周六 20:00, in the player's own time zone. */
export function eventTimeLabel(date: Date): string {
  return (date.getMonth() + 1) + " 月 " + date.getDate() + " 日 周" + WEEKDAYS[date.getDay()]
    + " " + pad2(date.getHours()) + ":" + pad2(date.getMinutes());
}

// ---- Server list text ---------------------------------------------------------------------

export interface MotdSegment {
  text: string;
  color: string | null;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  strike: boolean;
}

const MOTD_COLORS: Record<string, string> = {
  "0": "#000000", "1": "#0000AA", "2": "#00AA00", "3": "#00AAAA",
  "4": "#AA0000", "5": "#AA00AA", "6": "#FFAA00", "7": "#AAAAAA",
  "8": "#555555", "9": "#5555FF", a: "#55FF55", b: "#55FFFF",
  c: "#FF5555", d: "#FF55FF", e: "#FFFF55", f: "#FFFFFF",
};

/** Minecraft's darkest colours vanish on the dark card, so lift them without changing their hue. */
function readableMotdColor(hex: string): string {
  const value = Number.parseInt(hex.slice(1), 16);
  const channels = [(value >> 16) & 255, (value >> 8) & 255, value & 255];
  const luminance = (0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]) / 255;
  if (luminance >= 0.3) return hex;
  const lifted = channels.map((channel) => Math.round(channel + (255 - channel) * 0.45));
  return "#" + lifted.map((channel) => channel.toString(16).padStart(2, "0")).join("").toUpperCase();
}

/** Splits § formatted text into styled runs, one array per line. Obfuscated text is shown plainly. */
export function parseMotd(text: string | null | undefined): MotdSegment[][] {
  const lines: MotdSegment[][] = [[]];
  let style: Omit<MotdSegment, "text"> = { color: null, bold: false, italic: false, underline: false, strike: false };
  let buffer = "";
  const flush = () => {
    if (buffer.length > 0) lines[lines.length - 1].push({ text: buffer, ...style });
    buffer = "";
  };
  const source = (text ?? "").replace(/\r\n?/g, "\n");
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];
    if (char === "\n") {
      flush();
      lines.push([]);
      continue;
    }
    if (char !== "§" || index + 1 >= source.length) {
      buffer += char;
      continue;
    }
    const code = source[index + 1].toLowerCase();
    // §x§R§R§G§G§B§B carries a hex colour, as BungeeCord and Paper send it.
    if (code === "x" && /^(§[0-9a-f]){6}$/i.test(source.slice(index + 2, index + 14))) {
      flush();
      const hex = "#" + source.slice(index + 2, index + 14).replace(/§/g, "").toUpperCase();
      style = { color: readableMotdColor(hex), bold: false, italic: false, underline: false, strike: false };
      index += 13;
      continue;
    }
    index += 1;
    if (MOTD_COLORS[code] != null) {
      flush();
      style = { color: readableMotdColor(MOTD_COLORS[code]), bold: false, italic: false, underline: false, strike: false };
    } else if (code === "r") {
      flush();
      style = { color: null, bold: false, italic: false, underline: false, strike: false };
    } else if (code === "l" || code === "o" || code === "n" || code === "m") {
      flush();
      style = {
        ...style,
        bold: style.bold || code === "l",
        italic: style.italic || code === "o",
        underline: style.underline || code === "n",
        strike: style.strike || code === "m",
      };
    } else if (code !== "k") {
      buffer += "§" + source[index];
    }
  }
  flush();
  return lines.filter((line, index) => line.length > 0 || index === 0).slice(0, 2);
}

export function plainMotd(text: string | null | undefined): string {
  return parseMotd(text).map((line) => line.map((segment) => segment.text).join("")).join(" ").trim();
}

// ---- Community links ----------------------------------------------------------------------

export const COMMUNITY_LABELS: Record<PlayerCommunityKind, string> = {
  QQ: "QQ 群",
  DISCORD: "Discord",
  KOOK: "KOOK",
  BILIBILI: "哔哩哔哩",
  WEBSITE: "官网",
  OTHER: "链接",
};

/** A pixel-art avatar seed: the same name always gets the same colours. */
export function nameHue(name: string): number {
  let hash = 0;
  for (const char of name) hash = (hash * 31 + char.codePointAt(0)!) >>> 0;
  return hash % 360;
}

// ---- News ----------------------------------------------------------------------------------

/** Colour family of an announcement category, matching the design's 活动 / 维护 / 更新 / 规则 chips. */
export function categoryTone(category: string | null | undefined): "gold" | "orange" | "blue" | "violet" | "accent" {
  const name = (category ?? "").trim();
  if (/活动|赛|节/.test(name)) return "gold";
  if (/维护|停服|故障|紧急/.test(name)) return "orange";
  if (/更新|版本|整合包/.test(name)) return "blue";
  if (/规则|公告|须知/.test(name)) return "violet";
  return "accent";
}

/** 约 3 分钟, from the article length; Chinese reads at roughly 400 characters a minute. */
export function readingTime(markdown: string | null | undefined): string {
  const characters = Array.from((markdown ?? "").replace(/[#>*_`\[\]()!-]/g, "").replace(/\s+/g, "")).length;
  return "约 " + Math.max(1, Math.round(characters / 400)) + " 分钟";
}

/** Day and month for the date block of a list row: 22 and 10 月. */
export function dateParts(publishedOn: string): { day: string; month: string } {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(publishedOn.trim());
  if (match == null) return { day: "--", month: "" };
  return { day: match[3], month: Number(match[2]) + " 月" };
}

/** 10 月 22 日, the way the news page writes dates. */
export function chineseDate(publishedOn: string): string {
  const { day, month } = dateParts(publishedOn);
  return month ? month + " " + Number(day) + " 日" : "";
}

/** The project background, which pages without their own image fall back to. */
export function backgroundImage(backgroundUrl: string | null | undefined): string {
  return backgroundUrl ?? `${import.meta.env.BASE_URL}images/hero-default.webp`;
}
