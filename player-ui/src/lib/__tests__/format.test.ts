import { describe, expect, it } from "vitest";
import {
  ellipsize,
  foldPath,
  formatBytes,
  formatUnmanagedModDetails,
  formatUpdateFileDetails,
  parentPath,
  pathDepth,
  playerAddedMods,
} from "../format";
import type { LocalModEntry } from "../types";

describe("formatBytes", () => {
  it("formats byte units", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2.0 KB");
    expect(formatBytes(1536 * 1024)).toBe("1.5 MB");
  });
});

describe("formatUpdateFileDetails", () => {
  it("lists operations and limits long updates", () => {
    const installed = Array.from({ length: 32 }, (_, index) => `mods/updated-${index}.jar`);
    const details = formatUpdateFileDetails(
      installed,
      ["mods/removed.jar"],
      ["mods/archived.jar"],
    );
    expect(details).toContain("安装 / 更新");
    expect(details).toContain("mods/updated-0.jar");
    expect(details).toContain("另外 4 项未展开");
  });

  it("returns a friendly empty message", () => {
    expect(formatUpdateFileDetails([], [], [], [])).toBe("本次没有修改本地文件");
  });
});

describe("formatUnmanagedModDetails", () => {
  it("lists detected player mods", () => {
    const details = formatUnmanagedModDetails([
      "mods/xaeros-minimap.jar",
      "mods/embeddium-options-api.jar",
    ]);
    expect(details).toContain("玩家自选模组（2 个）");
    expect(details).toContain("mods/xaeros-minimap.jar");
    expect(details).toContain("点击进入“自选模组”标签页");
  });
});

describe("playerAddedMods", () => {
  it("keeps only player-added mods and fills detected paths", () => {
    const scanned: LocalModEntry[] = [
      { key: "component:server", displayName: "服务器模组", path: "mods/server.jar",
        componentId: "server", managed: true, disabled: false, active: true, forced: false },
      { key: "component:minimap", displayName: "小地图", path: "mods/minimap.jar",
        componentId: "minimap", managed: false, disabled: false, active: true, forced: false },
    ];
    const entries = playerAddedMods(scanned, [
      "mods/minimap.jar",
      "mods/visual-tweaks.jar",
    ]);
    expect(entries).toHaveLength(2);
    expect(entries.some((entry) => !entry.managed)).toBe(true);
    expect(entries.some((entry) => entry.displayName === "小地图")).toBe(true);
    expect(entries.some((entry) => entry.path === "mods/visual-tweaks.jar")).toBe(true);
  });
});

describe("path helpers", () => {
  it("folds, parents and depths paths", () => {
    expect(foldPath("Mods/Foo.JAR")).toBe("mods/foo.jar");
    expect(parentPath("config/dreamingfish/client.toml")).toBe("config/dreamingfish");
    expect(parentPath("config")).toBeNull();
    expect(pathDepth("config/dreamingfish/client.toml")).toBe(2);
  });

  it("ellipsizes long values", () => {
    expect(ellipsize("短", 5)).toBe("短");
    expect(ellipsize("abcdef", 4)).toBe("abc…");
  });
});
