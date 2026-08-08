import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import MusicPlayer from "../MusicPlayer.vue";
import { handleSidecarMessage, stopStartupMusic } from "../../stores/player";

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
          { id: "first", title: "第一首", fileName: "first.mp3" },
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

    collapsed?.click();
    await nextTick();
    expect(root.querySelector(".music-player-expanded")).not.toBeNull();
    expect(root.querySelector(".music-player-select")).not.toBeNull();

    root.querySelector<HTMLButtonElement>(".music-player-collapse")?.click();
    await nextTick();
    expect(root.querySelector(".music-player-expanded")).toBeNull();
  });
});
