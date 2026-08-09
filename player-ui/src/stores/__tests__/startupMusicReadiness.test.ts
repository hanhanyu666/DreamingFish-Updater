import { afterEach, describe, expect, it, vi } from "vitest";
import { getBridge } from "../../lib/bridge";
import { handleSidecarMessage, usePlayerStore } from "../player";

describe("startup music readiness", () => {
  const store = usePlayerStore();

  afterEach(() => {
    store.disableStartupMusic();
    vi.restoreAllMocks();
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
