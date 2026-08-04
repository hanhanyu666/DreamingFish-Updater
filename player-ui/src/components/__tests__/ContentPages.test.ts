import { afterEach, describe, expect, it } from "vitest";
import { createApp, nextTick } from "vue";
import ContentPages from "../ContentPages.vue";
import { handleSidecarMessage, showPage } from "../../stores/player";

describe("ContentPages", () => {
  let mountedApp: ReturnType<typeof createApp> | null = null;
  let root: HTMLDivElement | null = null;

  afterEach(() => {
    mountedApp?.unmount();
    root?.remove();
    mountedApp = null;
    root = null;
    showPage("HOME");
  });

  it("renders the configured custom page with the same Markdown component as news", async () => {
    handleSidecarMessage({
      type: "branding",
      branding: {
        productName: "预览测试服",
        subtitle: "测试副标题",
        serverAddress: "play.example.com",
        coverObject: null,
        accentColor: "#2ee8df",
        secondaryAccentColor: "#b06cff",
        brandName: "预览服",
        brandEnglishName: "PreviewServer",
        newsArticles: [],
        customPage: {
          enabled: true,
          navigationLabel: "玩法介绍",
          eyebrow: "GUIDE",
          title: "从这里开始",
          lead: "这是自定义页面的实时预览",
          markdown: "## 第一步\n\n请先安装整合包。",
        },
      },
    });
    showPage("CUSTOM");

    root = document.createElement("div");
    document.body.append(root);
    mountedApp = createApp(ContentPages);
    mountedApp.mount(root);
    await nextTick();

    expect(root.textContent).toContain("GUIDE");
    expect(root.textContent).toContain("从这里开始");
    expect(root.textContent).toContain("这是自定义页面的实时预览");
    expect(root.querySelector("h2")?.textContent).toBe("第一步");
    expect(root.textContent).toContain("请先安装整合包。");
  });
});
