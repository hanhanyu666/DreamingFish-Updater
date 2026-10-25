import { afterEach, describe, expect, it, vi } from "vitest";
import { getBridge } from "../../lib/bridge";
import { handleSidecarMessage, usePlayerStore } from "../player";

describe("startup music readiness", () => {
  const store = usePlayerStore();

  afterEach(() => {
    store.disableStartupMusic();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("does not read or play music before the native window is shown", async () => {
    const musicTrackUrl = vi.spyOn(getBridge(), "musicTrackUrl").mockResolvedValue(null);

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
          { id: "theme", title: "主题曲", fileName: "theme.mp3" },
        ],
      },
    });

    await Promise.resolve();
    expect(musicTrackUrl).not.toHaveBeenCalled();

    await store.enableStartupMusic();
    expect(musicTrackUrl).toHaveBeenCalledOnce();
    expect(musicTrackUrl).toHaveBeenCalledWith("theme.mp3");
  });

  it("plays the track once although the sidecar sends the same playlist several times", async () => {
    vi.spyOn(getBridge(), "musicTrackUrl").mockImplementation(async (fileName) => "blob:" + fileName);
    const created: string[] = [];
    const play = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("Audio", class {
      loop = false;
      volume = 1;
      preload = "";
      play = play;
      constructor(readonly src: string) { created.push(src); }
      addEventListener(): void {}
      pause(): void {}
      removeAttribute(): void {}
      load(): void {}
    });
    const branding = {
      productName: "测试整合包", subtitle: "测试", serverAddress: "", coverObject: null,
      accentColor: "#2ee8df", secondaryAccentColor: "#b06cff", brandName: "测试服", brandEnglishName: "Test",
      musicTracks: [{ id: "theme", title: "主题曲", fileName: "theme.mp3" }],
    };

    // Cached at startup, refreshed from the server, then once more after the update finishes.
    handleSidecarMessage({ type: "branding", branding });
    await store.enableStartupMusic();
    handleSidecarMessage({ type: "branding", branding: { ...branding, subtitle: "已刷新" } });
    handleSidecarMessage({ type: "branding", branding: { ...branding } });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(created).toEqual(["blob:theme.mp3"]);
    expect(play).toHaveBeenCalledOnce();

    // A playlist that really changed does switch to the new music.
    handleSidecarMessage({
      type: "branding",
      branding: { ...branding, musicTracks: [{ id: "night", title: "夜曲", fileName: "night.mp3" }] },
    });
    await vi.waitFor(() => expect(created).toEqual(["blob:theme.mp3", "blob:night.mp3"]));
  });

  it("marks player self-update progress for the main progress heading", () => {
    handleSidecarMessage({
      type: "progress",
      event: {
        stage: "DOWNLOADING",
        message: "正在更新玩家端程序",
        currentPath: null,
        completedBytes: 25,
        totalBytes: 100,
        fraction: 0.25,
      },
    });

    expect(store.state.playerProgramUpdating).toBe(true);
    expect(store.state.stageTitle).toBe("玩家端正在自更新");

    handleSidecarMessage({
      type: "progress",
      event: {
        stage: "CHECKING",
        message: "正在连接更新服务",
        currentPath: null,
        completedBytes: 0,
        totalBytes: 0,
        fraction: -1,
      },
    });
    expect(store.state.playerProgramUpdating).toBe(false);
  });
});
