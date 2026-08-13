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
