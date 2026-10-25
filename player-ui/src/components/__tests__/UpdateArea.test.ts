import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import UpdateArea from "../UpdateArea.vue";
import type { UpdateResultDto } from "../../lib/types";
import { handleSidecarMessage, showError } from "../../stores/player";

function result(extra: Partial<UpdateResultDto>): UpdateResultDto {
  return {
    releaseId: "r1", sequence: 1, projectId: "p1", createdAt: "2026-10-02T00:00:00Z",
    outcome: "UP_TO_DATE", displayVersion: "1.0", changelog: "", downloadedBytes: 0,
    installedPaths: [], deletedPaths: [], archivedFiles: [], releasedPaths: [],
    archiveDirectory: null, unmanagedMods: [], forcedSyncDirectories: [],
    ...extra,
  } as UpdateResultDto;
}

describe("UpdateArea card", () => {
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

  const lane = (element: HTMLElement) => element.querySelector<HTMLElement>(".swim-lane");
  const laneProgress = (element: HTMLElement) => lane(element)?.style.getPropertyValue("--lane-progress");
  const texts = (element: HTMLElement, selector: string) =>
    [...element.querySelectorAll(selector)].map((node) => node.textContent?.replace(/ /g, " ").trim());

  it("lets the fish wait while checking, then shows what the release is and folds it on request", async () => {
    handleSidecarMessage({ type: "progress", event: {
      stage: "CHECKING", message: "正在连接更新服务", currentPath: null,
      completedBytes: 0, totalBytes: 0, fraction: -1,
    } });
    const element = mount();
    await nextTick();
    expect(lane(element)?.classList.contains("swim-lane-waiting")).toBe(true);
    expect(element.querySelector(".moon-fish-swim")).not.toBeNull();
    expect(element.querySelector(".update-info")).toBeNull();
    expect(element.querySelector(".update-toggle")).toBeNull();

    handleSidecarMessage({ type: "result", result: result({ archivedFiles: ["config/a.toml"] }) });
    await nextTick();
    expect(lane(element)?.classList.contains("swim-lane-waiting")).toBe(false);
    expect(laneProgress(element)).toBe("1");
    expect(element.querySelector(".moon-fish-happy")).not.toBeNull();

    // A backup is news, so the info opens by itself.
    const toggle = element.querySelector<HTMLButtonElement>(".update-toggle");
    expect(toggle?.getAttribute("aria-expanded")).toBe("true");
    expect(element.querySelector(".update-info-heading")?.textContent).toBe("当前版本");
    expect(texts(element, ".update-info-lines li")).toEqual(["本次发布没有填写更新说明。"]);
    expect(texts(element, ".update-stat")).toEqual(["1备份"]);
    expect(element.querySelector<HTMLButtonElement>(".update-info-all")?.title).toContain("config/a.toml");
    expect(element.querySelectorAll(".quick-links .link-button")).toHaveLength(3);

    toggle?.click();
    await nextTick();
    expect(toggle?.getAttribute("aria-expanded")).toBe("false");
    expect(element.querySelector(".update-info")).toBeNull();
    expect(texts(element, ".update-note-text")).toEqual(["备份 1 个文件"]);
  });

  it("follows the download and offers the actions when the update stops", async () => {
    handleSidecarMessage({ type: "progress", event: {
      stage: "DOWNLOADING", message: "正在下载更新", currentPath: "mods/a.jar",
      completedBytes: 1, totalBytes: 4, fraction: 0.25,
    } });
    const element = mount();
    await nextTick();
    expect(laneProgress(element)).toBe("0.25");
    expect(element.querySelector(".update-percent")?.textContent).toBe("25%");
    expect(element.querySelector(".update-info")).toBeNull();
    expect(element.querySelector(".update-note")).toBeNull();

    showError("更新失败", "连接超时", true);
    await nextTick();
    expect(laneProgress(element)).toBe("0.25");
    expect(element.querySelector(".moon-fish-worried")).not.toBeNull();
    expect(element.querySelector(".update-info")).toBeNull();
    expect(element.querySelector(".update-detail")?.textContent).toBe("连接超时");
    expect(element.querySelector(".update-error-status")?.textContent).toBe("Minecraft 启动已暂停");
    expect(element.querySelector(".update-more")?.textContent?.trim()).toBe("运行记录");
    expect(texts(element, ".update-actions .update-action")).toEqual(["打开目录", "仍然启动", "重试"]);
    expect(element.querySelector(".update-action.primary")?.textContent?.trim()).toBe("重试");
  });

  it("names the file in the tooltip while downloading and wakes the fish when it is petted", async () => {
    handleSidecarMessage({ type: "progress", event: {
      stage: "DOWNLOADING", message: "正在下载更新", currentPath: "mods/b.jar",
      completedBytes: 2, totalBytes: 4, fraction: 0.5,
    } });
    const element = mount();
    await nextTick();
    expect(element.querySelector(".update-area")?.getAttribute("title")).toBe("mods/b.jar");
    expect(element.querySelector(".update-detail")?.textContent).toContain(" / ");

    element.querySelector<HTMLButtonElement>(".swim-lane-fish")?.click();
    await nextTick();
    expect(element.querySelector(".moon-fish-wow")).not.toBeNull();
  });

  it("lists the first tagged changelog lines and counts what changed", async () => {
    const element = mount();
    handleSidecarMessage({ type: "result", result: result({
      outcome: "UPDATED", displayVersion: "1.20.1-r12",
      changelog: "新增：永夜维度\n修复：交易所上架\n- 调整：光影改为可选\n移除：旧版渲染模组",
      installedPaths: ["mods/a.jar", "mods/b.jar"], deletedPaths: ["mods/old.jar"],
    }) });
    await nextTick();
    expect(element.querySelector(".update-info-heading")?.textContent).toBe("本次更新");
    expect(element.querySelector(".update-info-meta")?.textContent).toContain("1.20.1-r12");
    expect(texts(element, ".update-info-lines .update-change-tag")).toEqual(["新增", "修复", "调整"]);
    expect(texts(element, ".update-info-text")).toEqual(["永夜维度", "交易所上架", "光影改为可选"]);
    expect(texts(element, ".update-stat")).toEqual(["2安装", "1删除"]);
    expect(element.querySelector(".update-info-all")?.textContent?.trim()).toBe("查看完整说明与改动");
  });
});
