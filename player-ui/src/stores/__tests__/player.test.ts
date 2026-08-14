import { beforeEach, describe, expect, it } from "vitest";
import {
  answerConfirm,
  handleSidecarMessage,
  closeCommandForState,
  confirmDisableMod,
  displayBranding,
  setBranding,
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

  it("keeps startup music optional when the instance has no MP3", async () => {
    store.stopStartupMusic();
    await expect(store.initializeStartupMusic()).resolves.toBeUndefined();
    expect(store.state.startupMusicUrl).toBeNull();
    expect(store.state.musicPlaying).toBe(false);
  });

  it("keeps custom title-bar branding and defaults legacy messages", () => {
    const configured = displayBranding({
      productName: "测试整合包",
      subtitle: "测试说明",
      serverAddress: "",
      coverObject: null,
      accentColor: "#123456",
      secondaryAccentColor: "#654321",
      titleColor: "#f0e1c2",
      brandName: "星河服",
      brandEnglishName: "StarRiver",
      welcomeText: "踏入星海",
      topBarColor: "#123456",
      topBarOpacity: 0.35,
      cardColor: "#654321",
    });
    expect(configured).toMatchObject({
      brandName: "星河服",
      brandEnglishName: "StarRiver",
      welcomeText: "踏入星海",
      topBarColor: "#123456",
      topBarOpacity: 0.35,
      cardColor: "#654321",
    });
    setBranding(configured);
    expect(document.documentElement.style.getPropertyValue("--dfs-topbar-rgb"))
      .toBe("15, 44, 72");
    expect(document.documentElement.style.getPropertyValue("--dfs-title-color"))
      .toBe("#f0e1c2");
    expect(document.documentElement.style.getPropertyValue("--dfs-topbar-opacity"))
      .toBe("0.35");
    expect(document.documentElement.style.getPropertyValue("--dfs-card-rgb"))
      .toBe("72, 48, 24");

    const legacy = {
      productName: "旧整合包",
      subtitle: "旧说明",
      serverAddress: "",
      coverObject: null,
      accentColor: "#123456",
      secondaryAccentColor: "#654321",
    } as unknown as Parameters<typeof displayBranding>[0];
    expect(displayBranding(legacy)).toMatchObject({
      brandName: "梦鱼服",
      brandEnglishName: "DreamingFish",
      welcomeText: "欢迎来到",
      titleColor: "#fff8dc",
      topBarColor: "#030708",
      topBarOpacity: 0.22,
      cardColor: "#030708",
    });
  });

  it("uses configured news and exposes the configured navigation page", () => {
    handleSidecarMessage({
      type: "branding",
      branding: {
        productName: "星河整合包",
        subtitle: "一起探索",
        serverAddress: "play.example.com",
        coverObject: null,
        accentColor: "#123456",
        secondaryAccentColor: "#654321",
        brandName: "星河服",
        brandEnglishName: "StarRiver",
        newsArticles: [{
          id: "welcome",
          title: "欢迎",
          summary: "第一条消息",
          publishedOn: "2026-08-04",
          coverUrl: "https://example.com/cover.jpg",
          markdown: "# 欢迎",
        }],
        customPage: {
          enabled: true,
          navigationLabel: "玩法介绍",
          eyebrow: "GUIDE",
          title: "从这里开始",
          lead: "先看这里",
          markdown: "- 安装整合包",
        },
      },
    });

    expect(store.state.newsArticles).toHaveLength(1);
    expect(store.state.latestArticle?.title).toBe("欢迎");
    expect(store.navigationPages()).toContainEqual({
      page: "CUSTOM",
      label: "玩法介绍",
    });
  });

  it("uses server-owned dynamic pages and hides the removed news page", () => {
    handleSidecarMessage({
      type: "branding",
      branding: {
        productName: "星河整合包", subtitle: "一起探索", serverAddress: "",
        coverObject: null, accentColor: "#123456", secondaryAccentColor: "#654321",
        brandName: "星河服", brandEnglishName: "StarRiver",
        newsArticles: [], customPage: null,
        contentPages: [{
          id: "notice", navigationLabel: "公告", announcementPage: true,
          eyebrow: "NOTICE", title: "服务器公告", lead: "最近消息", markdown: "",
          articles: [{
            id: "maintenance", title: "维护完成", summary: "可以进入游戏了",
            publishedOn: "2026-08-04", coverUrl: "", markdown: "## 欢迎回来",
          }],
        }, {
          id: "rules", navigationLabel: "规则", announcementPage: false,
          eyebrow: "RULES", title: "游玩规则", lead: "请先阅读", markdown: "- 友善交流",
          articles: [],
        }],
      },
    });

    expect(store.navigationPages()).toEqual([
      { page: "HOME", label: "主页" },
      { page: "CONTENT:notice", label: "公告" },
      { page: "CONTENT:rules", label: "规则" },
      { page: "ABOUT", label: "关于更新器" },
    ]);
    expect(store.state.latestArticle?.id).toBe("maintenance");
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

  it("delegates pre-launch close confirmation to the Java side", () => {
    expect(closeCommandForState(false, false, false)).toBe("close");
    expect(closeCommandForState(false, true, false)).toBe("quit");
    expect(closeCommandForState(true, false, false)).toBe("quit");
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

  it("queues a sidecar confirmation without abandoning a local promise", async () => {
    const localResult = confirmDisableMod({
      key: "component:test",
      displayName: "Test Mod",
      path: "mods/test.jar",
      componentId: "test",
      managed: true,
      disabled: false,
      active: true,
      forced: false,
    });
    expect(store.state.confirm?.id).toBe(-1);

    handleSidecarMessage({
      type: "confirm-request",
      request: {
        id: 77,
        tone: "DANGER",
        title: "取消更新",
        heading: "关闭更新器？",
        message: "说明",
        actionText: "关闭",
        cancelText: "继续",
      },
    });
    expect(store.state.confirm?.id).toBe(-1);

    answerConfirm(false);
    await expect(localResult).resolves.toBe(false);
    expect(store.state.confirm?.id).toBe(77);

    answerConfirm(false);
    expect(store.state.confirm).toBeNull();
  });
});
