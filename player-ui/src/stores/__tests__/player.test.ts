import { beforeEach, describe, expect, it } from "vitest";
import {
  answerConfirm,
  handleSidecarMessage,
  showPage,
  toggleDrawer,
  usePlayerStore,
} from "../player";

describe("player store", () => {
  const store = usePlayerStore();

  beforeEach(() => {
    handleSidecarMessage({ type: "identity", name: "测试玩家" });
    handleSidecarMessage({ type: "logs", lines: [] });
    handleSidecarMessage({ type: "mods", entries: [] });
    handleSidecarMessage({ type: "files", entries: [] });
  });

  it("applies identity and logs", () => {
    expect(store.state.playerName).toBe("测试玩家");
    handleSidecarMessage({ type: "log", line: "hello" });
    expect(store.state.logs).toEqual(["hello"]);
  });

  it("applies progress state", () => {
    handleSidecarMessage({
      type: "progress",
      event: {
        stage: "DOWNLOADING",
        message: "正在下载更新",
        currentPath: "mods/core.jar",
        completedBytes: 50,
        totalBytes: 100,
        fraction: 0.5,
      },
    });
    expect(store.state.working).toBe(true);
    expect(store.state.percent).toBe("50%");
    expect(store.state.byteSummary).toContain("/");
    expect(store.state.currentPathText).toBe("mods/core.jar");
  });

  it("applies result state and permits launch", () => {
    handleSidecarMessage({
      type: "result",
      result: {
        releaseId: "r1",
        sequence: 1,
        projectId: "p1",
        createdAt: "2026-08-01T00:00:00Z",
        outcome: "UPDATED",
        displayVersion: "1.0.0",
        changelog: "更新内容",
        downloadedBytes: 1024,
        installedPaths: ["mods/a.jar"],
        deletedPaths: [],
        archivedFiles: [],
        releasedPaths: [],
        archiveDirectory: null,
        unmanagedMods: ["mods/b.jar"],
        forcedSyncDirectories: [],
      },
    });
    expect(store.state.result?.displayVersion).toBe("1.0.0");
    expect(store.state.launchPermitted).toBe(true);
    expect(store.state.working).toBe(false);
    expect(store.state.percent).toBe("100%");
    expect(store.state.releaseHistory?.releases[0].releaseId).toBe("r1");
  });

  it("countdown messages set the launch notice", () => {
    handleSidecarMessage({ type: "countdown", seconds: 15 });
    expect(store.state.launchNotice).toContain("15 秒后自动关闭");
  });

  it("navigates pages and hides the drawer", () => {
    toggleDrawer("HISTORY");
    expect(store.state.drawerOpen).toBe(true);
    showPage("NEWS");
    expect(store.state.page).toBe("NEWS");
    expect(store.state.drawerOpen).toBe(false);
  });

  it("clears confirmations on answer", () => {
    handleSidecarMessage({
      type: "confirm-request",
      request: {
        id: 7,
        tone: "WARNING",
        title: "停用本地模组",
        heading: "确认停用这个模组吗？",
        message: "说明",
        actionText: "确认停用",
        cancelText: "取消",
      },
    });
    expect(store.state.confirm?.id).toBe(7);
    answerConfirm(false);
    expect(store.state.confirm).toBeNull();
  });
});
