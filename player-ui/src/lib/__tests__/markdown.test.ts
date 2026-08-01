import { describe, expect, it } from "vitest";
import { renderMarkdown, safeWebUri } from "../markdown";

describe("renderMarkdown", () => {
  it("renders headings, paragraphs and emphasis", () => {
    const html = renderMarkdown("# 标题\n\n**粗体** 与 *斜体*");
    expect(html).toContain('<h1 class="markdown-heading markdown-h1">');
    expect(html).toContain('<p class="markdown-paragraph">');
    expect(html).toContain('<strong class="markdown-strong">粗体</strong>');
    expect(html).toContain('<em class="markdown-emphasis">斜体</em>');
  });

  it("renders quotes, lists, code and dividers", () => {
    const html = renderMarkdown("> 引用\n\n- 一\n- 二\n\n```java\nint x;\n```\n\n---");
    expect(html).toContain('<blockquote class="markdown-quote">');
    expect(html).toContain("markdown-list-row");
    expect(html).toContain('<pre class="markdown-code-block">');
    expect(html).toContain("markdown-divider");
  });

  it("drops raw HTML", () => {
    const html = renderMarkdown("<script>alert(1)</script>\n\nhello");
    expect(html).not.toContain("<script>");
    expect(html).toContain("hello");
  });

  it("keeps http links only", () => {
    expect(safeWebUri("https://example.com/a")).toBe("https://example.com/a");
    expect(safeWebUri("http://example.com")).toBe("http://example.com/");
    expect(safeWebUri("file:///etc/passwd")).toBeNull();
    expect(safeWebUri("javascript:alert(1)")).toBeNull();
    expect(safeWebUri("relative/path")).toBeNull();
  });
});
