import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import DetailsDrawer from "../DetailsDrawer.vue";
import {
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
    showLocalMode("FILES");
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
      "2026-08-13 12:08:40.210 | START | 启动 | 玩家端 0.1.37 · 项目 demo",
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
    expect(root.querySelector(".log-session-line")?.textContent).toContain("玩家端 0.1.37");
    expect(root.querySelector(".level-error .log-level")?.textContent).toBe("错误");
    expect(root.querySelector(".level-error .log-category")?.textContent).toBe("整合包更新");
    expect(root.querySelector(".log-details")?.textContent).toContain("java.io.IOException");
    expect(root.querySelector(".log-overview")?.textContent).toContain("错误 1");
  });
});
