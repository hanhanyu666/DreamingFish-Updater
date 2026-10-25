import { describe, expect, it } from "vitest";
import { countdownLabel, homeSlides, nextEvent, parseMotd, pageLayout, plainMotd, usableAction } from "../showcase";
import { DEFAULT_BRANDING, type Branding, type PlayerContentPage } from "../types";

const announcements: PlayerContentPage = {
  id: "news", navigationLabel: "公告", announcementPage: true, eyebrow: "", title: "公告", lead: "", markdown: "",
  articles: [
    { id: "a1", title: "旧公告", summary: "", publishedOn: "2026-09-01", coverUrl: "", markdown: "" },
    { id: "a2", title: "第二篇", summary: "", publishedOn: "2026-10-01", coverUrl: "https://example.com/c.png", markdown: "" },
    { id: "a3", title: "第三篇", summary: "", publishedOn: "2026-10-10", coverUrl: "javascript:alert(1)", markdown: "" },
    { id: "a4", title: "最新", summary: "摘要", publishedOn: "2026-10-20", coverUrl: "", markdown: "" },
  ],
};
const guide: PlayerContentPage = {
  id: "guide", navigationLabel: "介绍", announcementPage: false, eyebrow: "", title: "介绍", lead: "", markdown: "",
  articles: null, layout: "BLOCKS",
};

describe("MOTD colour codes", () => {
  it("splits colours and styles into runs, two lines at most", () => {
    const lines = parseMotd("§b鲸歌 §7· §l§6第三赛季§r 今晚\n§7第二行\n第三行");
    expect(lines).toHaveLength(2);
    expect(lines[0].map((segment) => segment.text)).toEqual(["鲸歌 ", "· ", "第三赛季", " 今晚"]);
    expect(lines[0][0].color).toBe("#55FFFF");
    expect(lines[0][2]).toMatchObject({ color: "#FFAA00", bold: false });
    expect(lines[0][3].color).toBeNull();
    expect(plainMotd("§b鲸歌 §7· 第三赛季")).toBe("鲸歌 · 第三赛季");
  });

  it("reads §x hex colours and lifts colours too dark for the card", () => {
    const [line] = parseMotd("§x§f§f§8§8§0§0橙色§0黑色");
    expect(line[0]).toMatchObject({ text: "橙色", color: "#FF8800" });
    expect(line[1].text).toBe("黑色");
    expect(line[1].color).not.toBe("#000000");
  });
});

describe("home carousel", () => {
  it("falls back to the welcome slide and the three latest announcements", () => {
    const slides = homeSlides({ ...DEFAULT_BRANDING, productName: "鲸歌", subtitle: "副标题" }, [announcements]);
    expect(slides.map((slide) => slide.id)).toEqual(["welcome", "article:news:a4", "article:news:a3", "article:news:a2"]);
    expect(slides[1].action).toMatchObject({ kind: "ARTICLE", pageId: "news", articleId: "a4" });
    expect(slides[2].image).toBeNull();
    expect(slides[3].image).toEqual({ imageUrl: "https://example.com/c.png" });
  });

  it("shows only titled spotlights and drops buttons that lead nowhere", () => {
    const branding: Branding = {
      ...DEFAULT_BRANDING,
      spotlights: [
        { id: "s1", tag: "新赛季", eyebrow: "", title: "永夜之境", lead: "", action: { label: "", kind: "PAGE", pageId: "guide" } },
        { id: "s2", tag: "", eyebrow: "", title: "  ", lead: "" },
        { id: "s3", tag: "", eyebrow: "", title: "维护", lead: "", action: { label: "看看", kind: "PAGE", pageId: "gone" } },
      ],
    };
    const slides = homeSlides(branding, [announcements, guide]);
    expect(slides.map((slide) => slide.title)).toEqual(["永夜之境", "维护"]);
    expect(slides[0].action).toEqual({ label: "查看详情", kind: "PAGE", pageId: "guide" });
    expect(slides[1].action).toBeNull();
  });
});

describe("buttons and layouts", () => {
  it("keeps only buttons whose page, article or http link exists", () => {
    const pages = [announcements, guide];
    expect(usableAction({ label: "", kind: "ARTICLE", pageId: "news", articleId: "a2" }, pages)?.label).toBe("阅读全文");
    expect(usableAction({ label: "", kind: "ARTICLE", pageId: "news", articleId: "nope" }, pages)).toBeNull();
    expect(usableAction({ label: "官网", kind: "URL", url: "https://example.com" }, pages)?.url).toBe("https://example.com/");
    expect(usableAction({ label: "坏", kind: "URL", url: "file:///C:/x" }, pages)).toBeNull();
  });

  it("reads older pages without a layout from the announcement flag", () => {
    expect(pageLayout(announcements)).toBe("ANNOUNCEMENTS");
    expect(pageLayout({ ...announcements, announcementPage: false })).toBe("MARKDOWN");
    expect(pageLayout(guide)).toBe("BLOCKS");
  });
});

describe("events", () => {
  const now = Date.parse("2026-10-22T12:00:00+08:00");

  it("picks the next event that has not ended, two hours long by default", () => {
    const events = [
      { id: "old", title: "结束了", startsAt: "2026-10-22T08:00:00+08:00" },
      { id: "late", title: "周六", startsAt: "2026-10-24T20:00:00+08:00" },
      { id: "now", title: "进行中", startsAt: "2026-10-22T11:00:00+08:00" },
      { id: "bad", title: "坏时间", startsAt: "下周" },
    ];
    expect(nextEvent(events, now)).toMatchObject({ event: { id: "now" }, ongoing: true });
    expect(nextEvent(events.filter((event) => event.id !== "now"), now)).toMatchObject({ event: { id: "late" }, ongoing: false });
    expect(nextEvent([{ id: "x", title: "", startsAt: "2026-10-24T20:00:00+08:00" }], now)).toBeNull();
  });

  it("counts down in days and a clock", () => {
    expect(countdownLabel(((2 * 24 + 3) * 3600 + 47 * 60 + 51) * 1000)).toBe("2 天 03:47:51");
    expect(countdownLabel(65_000)).toBe("00:01:05");
    expect(countdownLabel(-5)).toBe("00:00:00");
  });
});
