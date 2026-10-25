import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import MusicPlayer from "../MusicPlayer.vue";
import { handleSidecarMessage, showPage, stopStartupMusic } from "../../stores/player";

describe("MusicPlayer", () => {
  let app: ReturnType<typeof createApp> | null = null;
  let root: HTMLDivElement | null = null;

  afterEach(() => {
    app?.unmount();
    root?.remove();
    app = null;
    root = null;
    stopStartupMusic();
    handleSidecarMessage({
      type: "branding",
      branding: {
        productName: "测试整合包",
        subtitle: "测试",
        serverAddress: "",
        coverObject: null,
        accentColor: "#2ee8df",
        secondaryAccentColor: "#b06cff",
        brandName: "测试服",
        brandEnglishName: "Test",
        musicTracks: [],
      },
    });
    showPage("HOME");
  });

  it("stays compact until clicked, then exposes playlist controls", async () => {
    handleSidecarMessage({
      type: "branding",
      branding: {
        productName: "测试整合包",
        subtitle: "测试",
        serverAddress: "",
        coverObject: null,
        accentColor: "#2ee8df",
        secondaryAccentColor: "#b06cff",
        brandName: "测试服",
        brandEnglishName: "Test",
        musicTracks: [
          { id: "first", title: "启动音乐", fileName: "first.mp3" },
          { id: "second", title: "第二首", fileName: "second.mp3" },
        ],
      },
    });

    root = document.createElement("div");
    document.body.append(root);
    app = createApp(MusicPlayer);
    app.mount(root);
    await nextTick();

    const collapsed = root.querySelector<HTMLButtonElement>(".music-player-collapsed");
    expect(collapsed).not.toBeNull();
    expect(root.querySelector(".music-player-expanded")).toBeNull();
    expect(root.querySelector(".music-player-collapsed-track")?.textContent).toContain("first.mp3");

    expect(root.querySelector(".music-player-toggle")?.getAttribute("aria-label")).toContain("first.mp3");

    collapsed?.click();
    await nextTick();
    expect(root.querySelector(".music-player-expanded")).not.toBeNull();
    expect(root.querySelector(".music-player-collapsed")?.getAttribute("aria-expanded")).toBe("true");
    const options = () => [...root!.querySelectorAll<HTMLButtonElement>(".music-player-track-option")];
    expect(options().map((option) => option.querySelector(".music-player-track-name")?.textContent)).toEqual(["first.mp3", "第二首"]);
    expect(options().map((option) => option.getAttribute("aria-pressed"))).toEqual(["true", "false"]);

    options()[1].click();
    await nextTick();
    expect(options().map((option) => option.getAttribute("aria-pressed"))).toEqual(["false", "true"]);
    expect(root.querySelector(".music-player-collapsed-track")?.textContent).toContain("第二首");

    root.querySelector<HTMLButtonElement>(".music-player-collapse")?.click();
    await nextTick();
    expect(root.querySelector(".music-player-expanded")).toBeNull();
  });

  it("closes the window on a click elsewhere or on Escape", async () => {
    handleSidecarMessage({
      type: "branding",
      branding: {
        productName: "测试整合包",
        subtitle: "测试",
        serverAddress: "",
        coverObject: null,
        accentColor: "#2ee8df",
        secondaryAccentColor: "#b06cff",
        brandName: "测试服",
        brandEnglishName: "Test",
        musicTracks: [{ id: "first", title: "第一首", fileName: "first.mp3" }],
      },
    });
    root = document.createElement("div");
    document.body.append(root);
    app = createApp(MusicPlayer);
    app.mount(root);
    await nextTick();

    root.querySelector<HTMLButtonElement>(".music-player-collapsed")?.click();
    await nextTick();
    expect(root.querySelector(".music-player-expanded")).not.toBeNull();
    // A single track needs no list.
    expect(root.querySelector(".music-player-list")).toBeNull();

    document.body.dispatchEvent(new Event("pointerdown", { bubbles: true }));
    await nextTick();
    expect(root.querySelector(".music-player-expanded")).toBeNull();

    root.querySelector<HTMLButtonElement>(".music-player-collapsed")?.click();
    await nextTick();
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await nextTick();
    expect(root.querySelector(".music-player-expanded")).toBeNull();
  });
});
