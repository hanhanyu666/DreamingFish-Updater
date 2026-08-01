import { afterEach, describe, expect, it } from "vitest";
import { loadBundledNews, selectRequestedArticle, type NewsArticle } from "../news";

const originalFetch = globalThis.fetch;

function installFetch(routes: Record<string, unknown>): void {
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    if (!(url in routes)) {
      return new Response("not found", { status: 404 });
    }
    const body = routes[url];
    return new Response(typeof body === "string" ? body : JSON.stringify(body), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }) as typeof fetch;
}

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("loadBundledNews", () => {
  it("loads and sorts articles by date", async () => {
    installFetch({
      "/news/index.json": {
        schemaVersion: 1,
        articles: [
          {
            id: "older",
            title: "旧文章",
            summary: "摘要",
            publishedOn: "2026-07-28",
            cover: "news/images/old.jpg",
            body: "news/articles/old.md",
          },
          {
            id: "newer",
            title: "新文章",
            summary: "摘要",
            publishedOn: "2026-07-30",
            cover: "news/images/new.jpg",
            body: "news/articles/new.md",
          },
        ],
      },
      "/news/articles/old.md": "# 旧",
      "/news/articles/new.md": "# 新",
      "/news/images/old.jpg": "",
      "/news/images/new.jpg": "",
    });
    const articles = await loadBundledNews();
    expect(articles.map((article) => article.id)).toEqual(["newer", "older"]);
    expect(articles[0].cover).toBe("/news/images/new.jpg");
  });

  it("rejects unsupported schema versions", async () => {
    installFetch({
      "/news/index.json": { schemaVersion: 2, articles: [] },
    });
    await expect(loadBundledNews()).rejects.toThrow("不支持的新闻索引");
  });

  it("rejects unsafe resource paths", async () => {
    installFetch({
      "/news/index.json": {
        schemaVersion: 1,
        articles: [
          {
            id: "bad",
            title: "标题",
            summary: "摘要",
            publishedOn: "2026-07-28",
            cover: "../secret.jpg",
            body: "news/articles/bad.md",
          },
        ],
      },
    });
    await expect(loadBundledNews()).rejects.toThrow("新闻资源路径无效");
  });
});

describe("selectRequestedArticle", () => {
  const articles: NewsArticle[] = [
    { id: "latest", title: "最新", summary: "", publishedOn: "2026-08-01", cover: "", markdown: "" },
    { id: "target", title: "目标", summary: "", publishedOn: "2026-07-31", cover: "", markdown: "" },
  ];

  it("resolves a request that existed before the news page mounted", () => {
    expect(selectRequestedArticle(articles, "target")?.id).toBe("target");
  });

  it("falls back to the latest article when the requested id is unavailable", () => {
    expect(selectRequestedArticle(articles, "missing")?.id).toBe("latest");
  });
});
