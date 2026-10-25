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

  it("keeps bold that ends or starts on Chinese punctuation", () => {
    expect(renderMarkdown("- **永夜维度：**从主城进入")).toContain('<strong class="markdown-strong">永夜维度：</strong>从主城进入');
    expect(renderMarkdown("这是**「重点」**内容")).toContain('这是<strong class="markdown-strong">「重点」</strong>内容');
    expect(renderMarkdown("**a *b* c**")).toContain('<strong class="markdown-strong">a <em class="markdown-emphasis">b</em> c</strong>');
    expect(renderMarkdown("a ** b ** c")).not.toContain("<strong");
    expect(renderMarkdown("`**不是粗体**`")).toContain('<code class="markdown-inline-code">**不是粗体**</code>');
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
