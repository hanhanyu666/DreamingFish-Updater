import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import { DEFAULT_BRANDING, type PlayerContentPage } from "../../lib/types";
import { handleSidecarMessage, showPage } from "../../stores/player";
import ContentPages from "../ContentPages.vue";

function page(extra: Partial<PlayerContentPage>): PlayerContentPage {
  return {
    id: "page", navigationLabel: "页面", announcementPage: false, eyebrow: "", title: "页面", lead: "",
    markdown: "", articles: null, ...extra,
  };
}

describe("owner showcase pages", () => {
  let mountedApp: ReturnType<typeof createApp> | null = null;
  let root: HTMLDivElement | null = null;

  afterEach(() => {
    mountedApp?.unmount();
    root?.remove();
    mountedApp = null;
    root = null;
    showPage("HOME");
    handleSidecarMessage({ type: "branding", branding: { ...DEFAULT_BRANDING } });
  });

  async function open(pages: PlayerContentPage[], id: string): Promise<HTMLDivElement> {
    handleSidecarMessage({ type: "branding", branding: { ...DEFAULT_BRANDING, productName: "鲸歌", contentPages: pages } });
    showPage(`CONTENT:${id}`);
    root = document.createElement("div");
    document.body.append(root);
    mountedApp = createApp(ContentPages);
    mountedApp.mount(root);
    await nextTick();
    return root;
  }

  it("renders content blocks with their navigation and drops what it cannot show", async () => {
    const guide = page({
      id: "guide", title: "服务器介绍", layout: "BLOCKS",
      blocks: [
        { id: "features", kind: "CARDS", title: "玩法特色", navLabel: "特色", columns: 3,
          items: [
            { id: "a", title: "跨服交易", text: "主城交易所", icon: "star" },
            { id: "b", title: "领地", icon: "rocket" as never },
          ] },
        { id: "numbers", kind: "STATS", title: "数据", items: [{ id: "n", title: "120", text: "最多在线" }] },
        { id: "hero", kind: "HERO" as never, title: "不支持" },
        { id: "faq", kind: "FAQ", title: "常见问答", items: [{ id: "q", title: "怎么进服？", text: "复制地址" }] },
        { id: "join", kind: "CALLOUT", title: "准备好了吗", tone: "ACCENT",
          actions: [
            { label: "去看看", kind: "PAGE", pageId: "missing" },
            { label: "官网", kind: "URL", url: "https://example.com" },
          ] },
      ],
    });
    const element = await open([guide], "guide");

    expect(element.querySelector(".page-title")?.textContent).toBe("服务器介绍");
    const sections = [...element.querySelectorAll(".showcase-block")];
    expect(sections.map((section) => section.id)).toEqual(["block-features", "block-numbers", "block-faq", "block-join"]);
    expect([...element.querySelectorAll(".showcase-nav-item")].map((item) => item.textContent)).toEqual(["01特色", "02数据", "03常见问答", "04准备好了吗"]);

    const cards = element.querySelectorAll(".kind-cards .block-card");
    expect(cards).toHaveLength(2);
    expect(cards[0].querySelector(".block-card-icon")).not.toBeNull();
    expect(cards[1].querySelector(".block-card-icon")).toBeNull();
    expect(element.querySelector<HTMLElement>(".kind-cards")?.style.getPropertyValue("--columns")).toBe("3");
    expect(element.querySelector(".block-stat-value")?.textContent).toBe("120");
    expect(element.querySelector(".block-faq-question")?.textContent).toBe("怎么进服？");
    expect(element.querySelector(".block-faq-answer")?.textContent).toBe("复制地址");
    expect(element.querySelector(".kind-cards .eyebrow")?.textContent).toBe("01 · FEATURES");
    expect(element.querySelector(".kind-callout")?.classList.contains("tone-accent")).toBe(true);
    expect([...element.querySelectorAll(".kind-callout .block-action")].map((button) => button.textContent)).toEqual(["官网"]);
  });

  it("filters the gallery by category and opens the lightbox", async () => {
    const gallery = page({
      id: "gallery", title: "玩家画廊", layout: "GALLERY",
      gallery: [
        { id: "a", title: "海港", author: "Steve", category: "建筑", imageUrl: "https://example.com/a.png", location: "X 120 Z -40" },
        { id: "b", title: "日落", author: "Alex", category: "风景", imageUrl: "https://example.com/b.png" },
        { id: "c", title: "灯塔", author: "Steve", category: "建筑", imageUrl: "https://example.com/c.png" },
        { id: "d", title: "没有图片", author: "", category: "建筑" },
      ],
    });
    const element = await open([gallery], "gallery");

    const filters = [...element.querySelectorAll<HTMLButtonElement>(".gallery-filter")];
    expect(filters.map((button) => button.textContent?.trim())).toEqual(["全部3", "建筑2", "风景1"]);
    expect(element.querySelectorAll(".gallery-tile")).toHaveLength(3);
    filters[1].click();
    await nextTick();
    const dimmed = [...element.querySelectorAll(".gallery-tile.dimmed .gallery-tile-title")].map((title) => title.textContent);
    expect(dimmed).toEqual(["日落"]);
    expect(element.querySelector(".gallery-tile-author")?.textContent).toBe("by Steve");

    element.querySelector<HTMLButtonElement>(".gallery-tile")?.click();
    await nextTick();
    expect(element.querySelector(".lightbox-title")?.textContent).toBe("海港");
    expect(element.querySelector(".lightbox-location")?.textContent).toContain("X 120 Z -40");
    expect(element.querySelector(".lightbox-count")?.textContent).toBe("01 / 03");
    expect(element.querySelector(".lightbox-meta")?.textContent).toBe("by Steve · 建筑");
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight" }));
    await nextTick();
    expect(element.querySelector(".lightbox-title")?.textContent).toBe("日落");
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await nextTick();
    expect(element.querySelector(".lightbox")).toBeNull();
  });
});
