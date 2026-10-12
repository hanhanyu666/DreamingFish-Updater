import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import UpdateArea from "../UpdateArea.vue";
import { handleSidecarMessage, showError } from "../../stores/player";

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

  it("marks the running step and points a failure at the step that stopped", async () => {
    handleSidecarMessage({ type: "progress", event: {
      stage: "DOWNLOADING", message: "正在下载更新", currentPath: "mods/a.jar",
      completedBytes: 1, totalBytes: 4, fraction: 0.25,
    } });
    const element = mount();
    await nextTick();
    const stepStates = () => [...element.querySelectorAll<HTMLElement>(".stage-step")]
      .map((step) => step.className.replace("stage-step stage-step-", ""));
    expect(stepStates()).toEqual(["done", "active", "pending", "pending"]);
    expect(element.querySelector(".status-orb-working")).not.toBeNull();

    showError("更新失败", "连接超时", true);
    await nextTick();
    expect(stepStates()).toEqual(["done", "error", "pending", "pending"]);
    expect(element.querySelector(".update-summary")).toBeNull();
    expect(element.querySelector(".archive-button")).toBeNull();
    expect(element.querySelector(".error-status")?.textContent).toBe("Minecraft 启动已暂停");
    expect(element.querySelector(".action-row .primary-button")?.textContent?.trim()).toBe("仍然启动");
  });
});
