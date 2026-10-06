import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick } from "vue";
import { getBridge } from "../../lib/bridge";
import type { SidecarCommand } from "../../lib/types";
import DetailsDrawer from "../DetailsDrawer.vue";
import {
  handleSidecarMessage,
  hideDrawer,
  openDrawer,
  setDrawerExpanded,
  setLogs,
  showLocalMode,
} from "../../stores/player";

describe("DetailsDrawer local file management", () => {
  let mountedApp: ReturnType<typeof createApp> | null = null;
  let root: HTMLDivElement | null = null;

  afterEach(() => {
    mountedApp?.unmount();
    root?.remove();
    mountedApp = null;
    root = null;
    hideDrawer();
    setDrawerExpanded(false);
    setLogs([]);
    handleSidecarMessage({ type: "groups", groups: [] });
    handleSidecarMessage({ type: "archives", archives: [] });
    showLocalMode("FILES");
    vi.restoreAllMocks();
  });

  function mount(): HTMLDivElement {
    root = document.createElement("div");
    document.body.append(root);
    mountedApp = createApp(DetailsDrawer);
    mountedApp.mount(root);
    return root;
  }

  function captureCommands(): SidecarCommand[] {
    const sent: SidecarCommand[] = [];
    vi.spyOn(getBridge(), "sendCommand").mockImplementation((command) => { sent.push(command); });
    return sent;
  }

  it("offers optional content first and switches a group", async () => {
    const sent = captureCommands();
    handleSidecarMessage({ type: "groups", groups: [{
      id: "visuals", title: "光影与美化", description: "显卡较弱建议关闭", defaultInstall: true,
      enabled: true, explicit: false, members: ["Iris Shaders"],
    }] });
    openDrawer("FILES");
    showLocalMode("OPTIONS");
    const element = mount();
    await nextTick();

    expect(element.querySelectorAll('[role="tab"]')).toHaveLength(3);
    expect(element.querySelector("#local-option-panel")).not.toBeNull();
    expect(element.querySelector(".optional-state")?.textContent).toContain("跟随服主默认");
    expect(element.querySelector(".optional-member")?.textContent).toBe("Iris Shaders");
    const toggle = element.querySelector<HTMLInputElement>(".optional-switch input");
    if (toggle == null) throw new Error("group switch was not rendered");
    toggle.checked = false;
    toggle.dispatchEvent(new Event("change"));
    expect(sent).toContainEqual({ command: "toggle-group", groupId: "visuals", enabled: false });
  });

  it("lists backups with their reasons and puts a file back", async () => {
    const sent = captureCommands();
    openDrawer("BACKUPS");
    handleSidecarMessage({ type: "archives", archives: [{
      id: "a1", legacy: false, createdAt: "2026-10-02T08:00:00Z", releaseId: "r2",
      displayVersion: "1.1", totalBytes: 2048, files: [{
        path: "mods/old.jar", reason: "WITHDRAWN", reasonText: "服主撤回了这个版本",
        detail: "进入主城会崩溃", size: 2048, componentId: "old", version: "1.0",
        restoredAt: null,
      }],
    }] });
    const element = mount();
    await nextTick();

    expect(sent).toContainEqual({ command: "archives" });
    expect(element.querySelector(".drawer-tab.selected")?.textContent?.trim()).toBe("备份与恢复");
    expect(element.querySelector(".backup-heading strong")?.textContent).toBe("更新到版本 1.1 时");
    const reason = element.querySelector(".backup-file-reason")?.textContent ?? "";
    expect(reason).toContain("服主撤回了这个版本");
    expect(reason).toContain("版本 1.0");
    expect(reason).toContain("进入主城会崩溃");
    element.querySelector<HTMLButtonElement>(".backup-file .backup-action")?.click();
    expect(sent).toContainEqual({ command: "restore-archive", archiveId: "a1", path: "mods/old.jar" });
  });

  it("explains both local management modes and switches their panels", async () => {
    openDrawer("FILES");
    showLocalMode("FILES");

    root = document.createElement("div");
    document.body.append(root);
    mountedApp = createApp(DetailsDrawer);
    mountedApp.mount(root);
    await nextTick();

    const tabs = [...root.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
    expect(tabs).toHaveLength(2);
    expect(tabs[0].textContent).toContain("文件管理范围");
    expect(tabs[0].textContent).toContain("决定哪些文件随整合包更新");
    expect(tabs[0].getAttribute("aria-selected")).toBe("true");
    expect(root.querySelector("#local-file-management-panel")).not.toBeNull();

    tabs[1].click();
    await nextTick();

    expect(tabs[1].textContent).toContain("模组启停");
    expect(tabs[1].textContent).toContain("启用或停用整合包内模组");
    expect(tabs[1].getAttribute("aria-selected")).toBe("true");
    expect(root.querySelector("#local-mod-management-panel")).not.toBeNull();
  });

  it("groups running logs by date and separates levels from messages", async () => {
    openDrawer("LOGS");
    setLogs([
      "2026-08-13 12:08:40.210 | START | 启动 | 玩家端 0.1.38 · 项目 demo",
      "2026-08-13 12:08:41.035 | INFO  | 检查更新 | 已连接到更新服务",
      "2026-08-13 12:08:42.184 | ERROR | 整合包更新 | 更新失败：连接超时",
      "    java.io.IOException: 连接超时",
    ]);

    root = document.createElement("div");
    document.body.append(root);
    mountedApp = createApp(DetailsDrawer);
    mountedApp.mount(root);
    await nextTick();

    expect(root.querySelector(".log-day-heading")?.textContent).toContain("2026年8月13日");
    expect(root.querySelector(".log-session-line")?.textContent).toContain("玩家端 0.1.38");
    expect(root.querySelector(".level-error .log-level")?.textContent).toBe("错误");
    expect(root.querySelector(".level-error .log-category")?.textContent).toBe("整合包更新");
    expect(root.querySelector(".log-details")?.textContent).toContain("java.io.IOException");
    expect(root.querySelector(".log-overview")?.textContent).toContain("错误 1");
  });

  it("opens running logs at the newest entry without stealing manual scroll", async () => {
    openDrawer("FILES");
    setLogs([
      "2026-08-14 08:00:00.000 | INFO  | 启动 | 第一条",
      "2026-08-14 08:00:01.000 | INFO  | 启动 | 最新一条",
    ]);
    const scrollHeight = Object.getOwnPropertyDescriptor(
      HTMLElement.prototype, "scrollHeight",
    );
    const clientHeight = Object.getOwnPropertyDescriptor(
      HTMLElement.prototype, "clientHeight",
    );
    Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
      configurable: true, get: () => 480,
    });
    Object.defineProperty(HTMLElement.prototype, "clientHeight", {
      configurable: true, get: () => 100,
    });
    try {
      root = document.createElement("div");
      document.body.append(root);
      mountedApp = createApp(DetailsDrawer);
      mountedApp.mount(root);

      openDrawer("LOGS");
      await nextTick();
      await nextTick();
      const list = root.querySelector<HTMLElement>(".log-list");
      expect(list?.scrollTop).toBe(480);

      if (list == null) throw new Error("log list was not rendered");
      list.scrollTop = 40;
      list.dispatchEvent(new Event("scroll"));
      setLogs([
        "2026-08-14 08:00:00.000 | INFO  | 启动 | 第一条",
        "2026-08-14 08:00:01.000 | INFO  | 启动 | 最新一条",
        "2026-08-14 08:00:02.000 | INFO  | 启动 | 后续追加",
      ]);
      await nextTick();
      await nextTick();
      expect(list.scrollTop).toBe(40);
    } finally {
      if (scrollHeight) {
        Object.defineProperty(HTMLElement.prototype, "scrollHeight", scrollHeight);
      } else {
        delete (HTMLElement.prototype as unknown as Record<string, unknown>).scrollHeight;
      }
      if (clientHeight) {
        Object.defineProperty(HTMLElement.prototype, "clientHeight", clientHeight);
      } else {
        delete (HTMLElement.prototype as unknown as Record<string, unknown>).clientHeight;
      }
    }
  });
});
