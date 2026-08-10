import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import DetailsDrawer from "../DetailsDrawer.vue";
import {
  hideDrawer,
  openDrawer,
  setDrawerExpanded,
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
});
