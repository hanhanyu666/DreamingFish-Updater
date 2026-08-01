import { formatNewsDate } from "./format";

export interface NewsArticle {
  id: string;
  title: string;
  summary: string;
  publishedOn: string;
  cover: string;
  markdown: string;
}

export function selectRequestedArticle(
  articles: readonly NewsArticle[],
  articleId: string | null,
): NewsArticle | null {
  return articles.find((candidate) => candidate.id === articleId) ?? articles[0] ?? null;
}

interface NewsIndex {
  schemaVersion: number;
  articles: Array<{
    id: string;
    title: string;
    summary: string;
    publishedOn: string;
    cover: string;
    body: string;
  }>;
}

const ARTICLE_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const RESOURCE_SEGMENT = /^[A-Za-z0-9._-]+$/;

function validateResourcePath(value: string): string {
  const normalized = value.trim();
  if (normalized.startsWith("/") || normalized.includes("\\") || normalized.includes(":")) {
    throw new Error("新闻资源路径无效：" + normalized);
  }
  const segments = normalized.split("/");
  if (segments.length < 2) throw new Error("新闻资源必须位于子目录：" + normalized);
  for (const segment of segments) {
    if (
      segment.length === 0 ||
      segment === "." ||
      segment === ".." ||
      !RESOURCE_SEGMENT.test(segment)
    ) {
      throw new Error("新闻资源路径无效：" + normalized);
    }
  }
  return segments.join("/");
}

function resolveAsset(path: string): string {
  const value = path.trim();
  try {
    const uri = new URL(value);
    if (uri.protocol === "https:" || uri.protocol === "http:") return uri.toString();
  } catch {
    // relative resource
  }
  const safe = validateResourcePath(value);
  return "/" + safe;
}

export async function loadBundledNews(base = "/"): Promise<NewsArticle[]> {
  const indexResponse = await fetch(base + "news/index.json");
  if (!indexResponse.ok) throw new Error("无法读取内置新闻索引");
  const index = (await indexResponse.json()) as NewsIndex;
  if (index.schemaVersion !== 1 || !Array.isArray(index.articles)) {
    throw new Error("不支持的新闻索引");
  }
  const articles: NewsArticle[] = [];
  const ids = new Set<string>();
  for (const metadata of index.articles) {
    if (metadata == null) throw new Error("新闻索引包含空文章");
    if (!ARTICLE_ID.test(metadata.id) || ids.has(metadata.id)) {
      throw new Error("文章 ID 无效或重复：" + metadata.id);
    }
    ids.add(metadata.id);
    const bodyPath = validateResourcePath(metadata.body);
    if (!bodyPath.toLowerCase().endsWith(".md")) {
      throw new Error("新闻正文必须是 Markdown 文件：" + bodyPath);
    }
    const [markdownResponse, cover] = await Promise.all([
      fetch(base + bodyPath),
      Promise.resolve(resolveAsset(metadata.cover)),
    ]);
    if (!markdownResponse.ok) throw new Error("无法读取新闻正文：" + bodyPath);
    const markdown = await markdownResponse.text();
    if (markdown.trim().length === 0) throw new Error("新闻正文为空：" + bodyPath);
    articles.push({
      id: metadata.id,
      title: metadata.title,
      summary: metadata.summary,
      publishedOn: metadata.publishedOn,
      cover,
      markdown,
    });
  }
  return articles.sort((left, right) => {
    const byDate = right.publishedOn.localeCompare(left.publishedOn);
    return byDate !== 0 ? byDate : left.title.localeCompare(right.title);
  });
}

export function newsDateLabel(publishedOn: string): string {
  return formatNewsDate(publishedOn);
}
