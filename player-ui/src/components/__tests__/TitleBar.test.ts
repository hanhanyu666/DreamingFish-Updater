import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const titleBarSource = readFileSync(resolve("src/components/TitleBar.vue"), "utf8");
const capabilities = JSON.parse(
  readFileSync(
    resolve("src-tauri/capabilities/default.json"),
    "utf8",
  ),
) as { permissions: string[] };

describe("custom title bar dragging", () => {
  it("marks the title bar surface as a Tauri drag region", () => {
    expect(titleBarSource).toMatch(
      /class="title-bar reveal"[\s\S]*?data-tauri-drag-region/,
    );
  });

  it("keeps navigation and window controls clickable", () => {
    const buttonOpeningTags = titleBarSource.match(/<button\b[^>]*>/g) ?? [];

    expect(buttonOpeningTags.length).toBeGreaterThan(0);
    expect(buttonOpeningTags.every((tag) => !tag.includes("data-tauri-drag-region"))).toBe(true);
  });

  it("grants the capability used by Tauri drag regions", () => {
    expect(capabilities.permissions).toContain("core:window:allow-start-dragging");
  });
});
