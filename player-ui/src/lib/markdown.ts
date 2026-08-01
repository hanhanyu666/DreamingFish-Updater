import { marked, Renderer, Tokens } from "marked";

export interface MarkdownRenderOptions {
  openExternalLink?: (uri: string) => void;
}

function safeWebUri(destination: string): string | null {
  const value = destination?.trim();
  if (!value) return null;
  try {
    const uri = new URL(value);
    return uri.protocol === "https:" || uri.protocol === "http:" ? uri.toString() : null;
  } catch {
    return null;
  }
}

function resolveImageSource(destination: string): string | null {
  const value = destination?.trim();
  if (!value) return null;
  const external = safeWebUri(value);
  if (external) return external;
  const normalized = value.startsWith("/") ? value : "/" + value;
  if (normalized.includes("..")) return null;
  return normalized;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

const renderer = new Renderer();

renderer.heading = function (this: Renderer, { tokens, depth }: Tokens.Heading) {
  const level = Math.min(Math.max(depth, 1), 6);
  const content = this.parser.parseInline(tokens);
  return `<h${level} class="markdown-heading markdown-h${Math.min(level, 3)}">${content}</h${level}>`;
};

renderer.paragraph = function (this: Renderer, { tokens }: Tokens.Paragraph) {
  return `<p class="markdown-paragraph">${this.parser.parseInline(tokens)}</p>`;
};

renderer.blockquote = function (this: Renderer, { tokens }: Tokens.Blockquote) {
  return `<blockquote class="markdown-quote">${this.parser.parse(tokens)}</blockquote>`;
};

renderer.list = function (this: Renderer, { ordered, start, items }: Tokens.List) {
  let number = ordered ? Math.max(1, typeof start === "number" ? start : 1) : 1;
  const rows = items
    .map((item) => {
      const marker = ordered ? number++ + "." : "•";
      return `<div class="markdown-list-row"><span class="markdown-list-marker">${marker}</span><div class="markdown-list-item">${this.parser.parse(item.tokens)}</div></div>`;
    })
    .join("");
  return `<div class="markdown-list">${rows}</div>`;
};

renderer.listitem = function (this: Renderer, { tokens }: Tokens.ListItem) {
  // Lists are handled entirely by renderer.list to keep the original marker layout.
  return `<div>${this.parser.parse(tokens)}</div>`;
};

renderer.codespan = function ({ text }: Tokens.Codespan) {
  return `<code class="markdown-inline-code">${escapeHtml(text)}</code>`;
};

renderer.code = function ({ text }: Tokens.Code) {
  return `<pre class="markdown-code-block">${escapeHtml(text.replace(/\s+$/, ""))}</pre>`;
};

renderer.hr = function () {
  return `<div class="markdown-divider"></div>`;
};

renderer.html = function () {
  return "";
};

renderer.link = function (this: Renderer, { tokens, href }: Tokens.Link) {
  const text = this.parser.parseInline(tokens);
  const destination = safeWebUri(href ?? "");
  if (!destination) return text;
  return `<a class="markdown-link" href="${escapeHtml(destination)}" target="_blank" rel="noopener noreferrer">${text}</a>`;
};

renderer.image = function ({ href, text }: Tokens.Image) {
  const source = resolveImageSource(href ?? "");
  const alternative = escapeHtml(text?.trim() || "图片无法显示");
  if (!source) {
    return `<span class="markdown-image-missing">${alternative}</span>`;
  }
  return `<span class="markdown-image-frame"><img class="markdown-image" src="${escapeHtml(source)}" alt="${alternative}" /><span class="markdown-image-missing">${alternative}</span></span>`;
};

renderer.strong = function (this: Renderer, { tokens }: Tokens.Strong) {
  return `<strong class="markdown-strong">${this.parser.parseInline(tokens)}</strong>`;
};

renderer.em = function (this: Renderer, { tokens }: Tokens.Em) {
  return `<em class="markdown-emphasis">${this.parser.parseInline(tokens)}</em>`;
};

marked.use({
  gfm: true,
  breaks: false,
  renderer,
});

export function renderMarkdown(markdown: string, options: MarkdownRenderOptions = {}): string {
  return marked.parse(markdown ?? "", {
    async: false,
  }) as string;
}

export function markdownClickHandler(
  event: MouseEvent,
  options: MarkdownRenderOptions,
): void {
  const target = event.target as HTMLElement | null;
  const anchor = target?.closest?.("a.markdown-link") as HTMLAnchorElement | null;
  if (!anchor) return;
  event.preventDefault();
  options.openExternalLink?.(anchor.href);
}

export { safeWebUri, resolveImageSource, escapeHtml };
