import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import UpdateArea from "../UpdateArea.vue";
import { handleSidecarMessage } from "../../stores/player";

describe("UpdateArea progress bar", () => {
  let mountedApp: ReturnType<typeof createApp> | null = null;
  let root: HTMLDivElement | null = null;

  afterEach(() => {
    mountedApp?.unmount();
    root?.remove();
    mountedApp = null;
    root = null;
  });

  function mount(): HTMLDivElement {
    root = document.createElement("div");
    document.body.append(root);
    mountedApp = createApp(UpdateArea);
    mountedApp.mount(root);
    return root;
  }

  it("moves while checking and stays full once the update finished", async () => {
    handleSidecarMessage({ type: "progress", event: {
      stage: "CHECKING", message: "正在连接更新服务", currentPath: null,
      completedBytes: 0, totalBytes: 0, fraction: -1,
    } });
    const element = mount();
    await nextTick();
    const bar = () => element.querySelector<HTMLElement>(".update-progress-bar");
    expect(bar()?.classList.contains("indeterminate")).toBe(true);

    handleSidecarMessage({ type: "result", result: {
      releaseId: "r1", sequence: 1, projectId: "p1", createdAt: "2026-10-02T00:00:00Z",
      outcome: "UP_TO_DATE", displayVersion: "1.0", changelog: "", downloadedBytes: 0,
      installedPaths: [], deletedPaths: [], archivedFiles: ["config/a.toml"], releasedPaths: [],
      archiveDirectory: null, unmanagedMods: [], forcedSyncDirectories: [],
    } });
    await nextTick();
    expect(bar()?.classList.contains("indeterminate")).toBe(false);
    expect(bar()?.style.width).toBe("100%");
    expect(element.querySelector(".archive-button")?.textContent?.trim()).toBe("查看备份与恢复");
  });
});
