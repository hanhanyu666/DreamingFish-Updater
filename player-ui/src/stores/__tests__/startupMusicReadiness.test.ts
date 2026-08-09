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
});
