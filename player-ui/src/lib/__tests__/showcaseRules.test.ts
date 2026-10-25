import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { previewBranding } from "../previewShowcase";
import {
  SHOWCASE_AI_PROMPT,
  applyShowcase,
  normalizeShowcase,
  parseShowcase,
  showcaseFromBranding,
  validateShowcase,
} from "../showcaseRules";
import { DEFAULT_BRANDING, type Branding } from "../types";

const example = JSON.parse(readFileSync(resolve("../docs/showcase/example-whalesong.json"), "utf8"));

describe("showcase rules", () => {
  it("ships the same AI instructions as the owner documentation", () => {
    const readme = readFileSync(resolve("../docs/showcase/README.md"), "utf8").replace(/\r\n/g, "\n");
    expect(readme).toContain(SHOWCASE_AI_PROMPT);
  });

  it("accepts the documented example template", () => {
    expect(validateShowcase(example)).toEqual([]);
  });

  it("accepts the browser preview content once exported", () => {
    expect(validateShowcase(showcaseFromBranding(previewBranding()))).toEqual([]);
  });

  it("lists every problem with its JSON path", () => {
    const broken = structuredClone(example);
    broken.contentPages[1].blocks[2].kind = "HERO";
    broken.spotlights[0].title = "";
    broken.spotlights[1].action = { kind: "PAGE", pageId: "missing", label: "去看看" };
    broken.events[0].startsAt = "下周六晚上";
    broken.serverCard.links[0].url = "javascript:alert(1)";
    broken.theme.corner = "BLOB";
    broken.addressPlacement = "FOOTER";
    broken.extra = true;

    const issues = validateShowcase(broken);
    const paths = issues.map((issue) => issue.path);
    expect(issues).toContainEqual({ path: "contentPages[1].blocks[2].kind", message: "不支持 \"HERO\"" });
    expect(paths).toEqual(expect.arrayContaining([
      "spotlights[0].title",
      "spotlights[1].action.pageId",
      "events[0].startsAt",
      "serverCard.links[0].url",
      "theme.corner",
      "addressPlacement",
      "extra",
    ]));
  });

  it("reports length limits in characters, not bytes", () => {
    const document = { format: "dreamingfish-showcase", version: 1, productName: "鲸".repeat(60) };
    expect(validateShowcase(document)).toEqual([]);
    document.productName = "鲸".repeat(61);
    expect(validateShowcase(document)).toEqual([{ path: "productName", message: "不能超过 60 字" }]);
  });

  it("explains JSON syntax errors instead of throwing", () => {
    const result = parseShowcase("{ \"format\": ");
    expect(result.document).toBeNull();
    expect(result.issues[0].path).toBe("$");
    expect(result.issues[0].message).toContain("不是有效的 JSON");
  });

  it("imports only showcase fields and keeps uploaded files", () => {
    const current: Branding = {
      ...DEFAULT_BRANDING,
      coverObject: "cover-object",
      musicTracks: [{ id: "sea", title: "海边", fileName: "sea.ogg" }],
    };
    const applied = applyShowcase(current, parseShowcase(JSON.stringify(example)).document!);
    expect(applied.coverObject).toBe("cover-object");
    expect(applied.musicTracks).toEqual(current.musicTracks);
    expect(applied.spotlights).toEqual(example.spotlights);
    expect(applied.productName).toBe(example.productName);
  });

  it("cleans unknown options and over-long lists for the player", () => {
    const branding = {
      ...DEFAULT_BRANDING,
      spotlights: Array.from({ length: 8 }, (_, index) => ({
        id: "s" + index, tag: "", eyebrow: "", title: index === 0 ? " " : "第 " + index + " 张", lead: "",
      })),
      theme: { corner: "BLOB", surface: "GLASS" },
      addressPlacement: "FOOTER",
      contentPages: [{
        id: "guide", navigationLabel: "介绍", announcementPage: false, eyebrow: "", title: "介绍", lead: "",
        markdown: "", articles: null, layout: "BLOCKS",
        blocks: [
          { id: "a", kind: "HERO" },
          { id: "b", kind: "CARDS", columns: 9, tone: "NEON", items: [{ id: "i", title: "x", icon: "rocket" }] },
        ],
      }],
    } as unknown as Branding;

    const clean = normalizeShowcase(branding);
    // Spotlights have no count limit, so all seven stay.
    expect(clean.spotlights).toHaveLength(7);
    expect(clean.spotlights?.[0].title).toBe("第 1 张");
    expect(clean.theme).toEqual({ corner: null, surface: "GLASS" });
    expect(clean.addressPlacement).toBeNull();
    const blocks = clean.contentPages?.[0].blocks ?? [];
    expect(blocks.map((block) => block.id)).toEqual(["b"]);
    expect(blocks[0]).toMatchObject({ columns: 4, tone: null });
    expect(blocks[0].items?.[0].icon).toBeNull();
  });
});
