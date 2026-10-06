import { afterEach, describe, expect, it, vi } from "vitest";
import { getBridge } from "../../lib/bridge";
import type { SidecarCommand } from "../../lib/types";
import {
  handleSidecarMessage,
  hideDrawer,
  openDrawer,
  showLocalMode,
  usePlayerStore,
} from "../player";

describe("player store maintenance features", () => {
  const store = usePlayerStore();
  const bridge = getBridge();

  afterEach(() => {
    vi.restoreAllMocks();
    handleSidecarMessage({ type: "groups", groups: [] });
    hideDrawer();
  });

  it("opens optional content first until the player picks another mode", () => {
    handleSidecarMessage({ type: "groups", groups: [{
      id: "visuals", title: "光影与美化", description: "", defaultInstall: true,
      enabled: true, explicit: false, members: ["Iris Shaders"],
    }] });
    expect(store.state.groups).toHaveLength(1);
    expect(store.state.localMode).toBe("OPTIONS");

    showLocalMode("MODS");
    handleSidecarMessage({ type: "groups", groups: [...store.state.groups] as never });
    expect(store.state.localMode).toBe("MODS");

    showLocalMode("OPTIONS");
    handleSidecarMessage({ type: "groups", groups: [] });
    expect(store.state.localMode).toBe("FILES");
  });

  it("sends group, reset and backup commands to the updater", () => {
    const sent: SidecarCommand[] = [];
    vi.spyOn(bridge, "sendCommand").mockImplementation((command) => { sent.push(command); });

    store.setGroupChoice({ id: "visuals" }, false);
    store.setGroupChoice({ id: "visuals" }, null);
    store.restoreArchivedFile("a1", "config/a.toml");
    store.deleteArchive("a1");
    store.openArchive("a1");
    store.openArchive();
    openDrawer("BACKUPS");

    expect(sent).toContainEqual({ command: "toggle-group", groupId: "visuals", enabled: false });
    expect(sent).toContainEqual({ command: "toggle-group", groupId: "visuals", enabled: null });
    expect(sent).toContainEqual({ command: "restore-archive", archiveId: "a1", path: "config/a.toml" });
    expect(sent).toContainEqual({ command: "delete-archive", archiveId: "a1" });
    expect(sent).toContainEqual({ command: "open-archive", archiveId: "a1" });
    expect(sent).toContainEqual({ command: "open-archive" });
    expect(sent).toContainEqual({ command: "archives" });
  });

  it("keeps the archive list and explains what the update did with local files", () => {
    handleSidecarMessage({ type: "archives", archives: [{
      id: "a1", legacy: false, createdAt: "2026-10-02T00:00:00Z", releaseId: "r2",
      displayVersion: "1.1", totalBytes: 10, files: [],
    }] });
    expect(store.state.archivesLoaded).toBe(true);
    expect(store.state.archives[0].id).toBe("a1");

    handleSidecarMessage({ type: "result", result: {
      releaseId: "r2", sequence: 2, projectId: "p1", createdAt: "2026-10-02T00:00:00Z",
      outcome: "UPDATED", displayVersion: "1.1", changelog: "", downloadedBytes: 0,
      installedPaths: [], deletedPaths: [], archivedFiles: ["config/a.toml"], releasedPaths: [],
      archiveDirectory: "backups/archive/a1", unmanagedMods: [], forcedSyncDirectories: [],
      archived: [{ path: "config/a.toml", reason: "WITHDRAWN", reasonText: "服主撤回了这个版本",
        detail: "崩溃", size: 1, componentId: null, version: null, restoredAt: null }],
      keptModifiedPaths: ["config/b.toml"], skippedSelfManagedPaths: [], resetPaths: ["config/c.toml"],
    } });
    const notice = store.state.unmanaged;
    expect(notice?.text).toBe(
      "已将 1 个本地文件移入备份；已恢复 1 个默认配置；保留了你修改过的 1 个配置");
    expect(notice?.contextLines).toContain("备份：config/a.toml（服主撤回了这个版本）");
    expect(notice?.contextLines).toContain("保留你的修改：config/b.toml");
  });
});
