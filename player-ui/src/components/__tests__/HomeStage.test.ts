import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, type Component } from "vue";
import { getBridge } from "../../lib/bridge";
import { showSlide } from "../../lib/homeCarousel";
import { DEFAULT_BRANDING, type Branding, type SidecarCommand } from "../../lib/types";
import { handleSidecarMessage } from "../../stores/player";
import EventCard from "../EventCard.vue";
import HomeHero from "../HomeHero.vue";
import HomeRail from "../HomeRail.vue";
import ServerCard from "../ServerCard.vue";
import TitleBar from "../TitleBar.vue";

function brand(extra: Partial<Branding>): void {
  handleSidecarMessage({
    type: "branding",
    branding: { ...DEFAULT_BRANDING, productName: "鲸歌生存服", subtitle: "第三赛季", ...extra },
  });
}

describe("home stage cards", () => {
  let mountedApp: ReturnType<typeof createApp> | null = null;
  let root: HTMLDivElement | null = null;

  afterEach(() => {
    mountedApp?.unmount();
    root?.remove();
    mountedApp = null;
    root = null;
    handleSidecarMessage({ type: "branding", branding: { ...DEFAULT_BRANDING } });
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  async function mount(component: Component): Promise<HTMLDivElement> {
    root = document.createElement("div");
    document.body.append(root);
    mountedApp = createApp(component);
    mountedApp.mount(root);
    await nextTick();
    return root;
  }

  it("shows the server, its live status and community buttons", async () => {
    const sent: SidecarCommand[] = [];
    vi.spyOn(getBridge(), "sendCommand").mockImplementation((command) => { sent.push(command); });
    const opened = vi.spyOn(getBridge(), "openExternal").mockImplementation(() => undefined);
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    brand({
      serverAddress: "play.example.com",
      serverCard: {
        enabled: true, showStatus: true, versionLabel: "1.20.1 · Fabric", motd: "§b鲸歌 §7· §6第三赛季",
        links: [
          { id: "qq", kind: "QQ", label: "", copyText: "123456" },
          { id: "site", kind: "WEBSITE", label: "官网", url: "https://example.com" },
        ],
      },
    });
    const element = await mount(ServerCard);

    expect(element.querySelector(".server-card-name")?.textContent).toBe("鲸歌生存服");
    expect(element.querySelector(".server-version")?.textContent).toBe("1.20.1 · Fabric");
    expect(element.querySelector(".server-motd")?.textContent).toBe("鲸歌 · 第三赛季");
    expect(element.querySelector(".server-status")).toBeNull();

    handleSidecarMessage({ type: "server-status", status: { state: "ONLINE", onlinePlayers: 37, maxPlayers: 120, latencyMs: 28 } });
    await nextTick();
    expect(element.querySelector(".server-players")?.textContent?.replace(/\s+/g, "")).toBe("37/120人");
    expect(element.querySelector(".server-latency")?.classList.contains("latency-good")).toBe(true);
    // The same figures sit beside the name, for when the rail folds the status row away.
    expect(element.querySelector(".server-inline-players")?.textContent?.replace(/\s+/g, "")).toBe("37/120");
    expect(element.querySelector(".server-inline-latency")?.textContent?.trim()).toBe("28 ms");
    element.querySelector<HTMLButtonElement>(".server-refresh")?.click();
    expect(sent).toContainEqual({ command: "refresh-server-status" });

    const address = element.querySelector<HTMLButtonElement>(".server-address");
    expect(address?.querySelector(".server-address-value")?.textContent).toBe("play.example.com");
    address?.click();
    await vi.waitFor(() => expect(writeText).toHaveBeenCalledWith("play.example.com"));

    const links = [...element.querySelectorAll<HTMLButtonElement>(".server-link")];
    expect(links.map((link) => link.textContent?.trim())).toEqual(["QQ 群", "官网"]);
    links[0].click();
    await vi.waitFor(() => expect(links[0].textContent?.trim()).toBe("已复制"));
    expect(writeText).toHaveBeenCalledWith("123456");
    links[1].click();
    expect(opened).toHaveBeenCalledWith("https://example.com/");
  });

  it("shows the DreamingFish mark until the owner's logo has arrived", async () => {
    vi.spyOn(getBridge(), "presentationImageUrl").mockResolvedValue("blob:owner-logo");
    const element = await mount(TitleBar);
    const mark = () => element.querySelector(".brand-tile img")?.getAttribute("src") ?? "";
    // Vite inlines the small SVG, so the default mark is either a data URI or the asset path.
    expect(mark()).toMatch(/^data:image\/svg\+xml|dreamingfish-mark/);

    brand({ brandLogoFile: "title-logo-test.png" });
    await vi.waitFor(() => expect(mark()).toBe("blob:owner-logo"));
  });

  it("folds the server details only when the cards really do not fit", async () => {
    vi.stubGlobal("ResizeObserver", class { observe(): void {} disconnect(): void {} });
    // Pretend the rail is 600 px tall and its cards need 660 px until the status row and the address fold away.
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(600);
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(function (this: HTMLElement) {
      const folds = (this.dataset.fold ?? "").split(" ");
      return 660 - (folds.includes("status") ? 30 : 0) - (folds.includes("address") ? 40 : 0);
    });
    brand({ serverAddress: "play.example.com" });
    const element = await mount(HomeRail);
    expect(element.querySelector<HTMLElement>(".home-rail")?.dataset.fold).toBe("status address");
  });

  it("counts down to the next event and marks one in progress", async () => {
    const soon = new Date(Date.now() + (2 * 24 + 1) * 3600_000).toISOString();
    brand({ events: [{ id: "build", title: "建筑大赛 · 开赛直播", startsAt: soon }] });
    const element = await mount(EventCard);
    expect(element.querySelector(".event-card-title")?.textContent).toBe("建筑大赛 · 开赛直播");
    expect(element.querySelector(".event-countdown")?.textContent?.trim()).toMatch(/^2 天 \d{2}:\d{2}:\d{2}$/);

    brand({ events: [{ id: "live", title: "直播中", startsAt: new Date(Date.now() - 600_000).toISOString() }] });
    await nextTick();
    expect(element.querySelector(".event-card")?.classList.contains("ongoing")).toBe(true);
    expect(element.querySelector(".event-countdown")?.textContent?.trim()).toBe("进行中");
  });

  it("lets the owner choose where the address shows", async () => {
    const card = { enabled: true, showStatus: false, versionLabel: null, motd: null, links: [] };
    brand({ serverAddress: "play.example.com", serverCard: card });
    const element = await mount({ render: () => h("div", [h(HomeHero), h(ServerCard)]) });
    const shown = () => ({
      hero: element.querySelector(".home-slide.active .home-slide-copy") != null,
      card: element.querySelector(".server-address") != null,
    });
    expect(shown()).toEqual({ hero: false, card: true });

    brand({ serverAddress: "play.example.com", serverCard: card, addressPlacement: "HERO" });
    await nextTick();
    expect(shown()).toEqual({ hero: true, card: false });
    expect(element.querySelector(".server-card"), "the card no longer depends on the address").not.toBeNull();

    brand({ serverAddress: "play.example.com", serverCard: card, addressPlacement: "HIDDEN" });
    await nextTick();
    expect(shown()).toEqual({ hero: false, card: false });

    brand({ serverAddress: "", serverCard: card });
    await nextTick();
    expect(element.querySelector(".server-card .server-card-name")?.textContent).toBe("鲸歌生存服");
  });

  it("puts the spotlight button beside the address copy and switches slides", async () => {
    brand({
      serverAddress: "play.example.com",
      addressPlacement: "HERO",
      contentPages: [{
        id: "guide", navigationLabel: "介绍", announcementPage: false, eyebrow: "", title: "介绍", lead: "",
        markdown: "", articles: null, layout: "BLOCKS", blocks: [],
      }],
      spotlights: [
        { id: "season", tag: "新赛季", eyebrow: "SEASON 03", title: "永夜之境", lead: "今晚开放",
          action: { label: "查看赛季详情", kind: "PAGE", pageId: "guide" } },
        { id: "build", tag: "活动", eyebrow: "", title: "秋日建筑大赛", lead: "" },
      ],
    });
    const element = await mount(HomeHero);

    const active = () => element.querySelector(".home-slide.active");
    expect(active()?.querySelector(".home-slide-title")?.textContent).toBe("永夜之境");
    expect(active()?.querySelector(".home-slide-tag")?.textContent).toBe("新赛季");
    expect(active()?.querySelector(".home-slide-action")?.textContent?.trim()).toBe("查看赛季详情");
    expect(active()?.querySelector(".home-slide-copy")?.textContent?.trim()).toBe("play.example.com");
    expect(active()?.querySelector(".home-slide-copy")?.getAttribute("aria-label")).toBe("复制服务器地址 play.example.com");

    const dots = [...element.querySelectorAll<HTMLButtonElement>(".home-slide-dot")];
    expect(dots.map((dot) => dot.querySelector(".home-slide-dot-label")?.textContent)).toEqual(["永夜之境", "秋日建筑大赛"]);
    dots[1].click();
    await nextTick();
    expect(active()?.querySelector(".home-slide-title")?.textContent).toBe("秋日建筑大赛");
    expect(active()?.querySelector(".home-slide-action")).toBeNull();
    expect(dots[1].getAttribute("aria-current")).toBe("true");
  });

  it("keeps every spotlight and switches to a pager when there are many", async () => {
    brand({
      spotlights: Array.from({ length: 9 }, (_, index) => ({
        id: "slide-" + index, tag: "", eyebrow: "", title: "第 " + (index + 1) + " 张", lead: "",
      })),
    });
    showSlide(0, 9);
    const element = await mount(HomeHero);

    expect(element.querySelectorAll(".home-slide")).toHaveLength(9);
    expect(element.querySelector(".home-slide-dots")).toBeNull();
    const count = () => element.querySelector(".home-slide-count")?.textContent?.replace(/\s+/g, "");
    expect(count()).toBe("01/09");

    const [previous, next] = [...element.querySelectorAll<HTMLButtonElement>(".home-slide-step")];
    previous.click();
    await nextTick();
    expect(count()).toBe("09/09");
    expect(element.querySelector(".home-slide-pager-label")?.textContent).toBe("第 9 张");
    next.click();
    next.click();
    await nextTick();
    expect(count()).toBe("02/09");
    expect(element.querySelector(".home-slide.active .home-slide-title")?.textContent).toBe("第 2 张");
  });
});
