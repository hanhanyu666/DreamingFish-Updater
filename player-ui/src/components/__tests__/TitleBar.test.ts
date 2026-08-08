import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const titleBarSource = readFileSync(resolve("src/components/TitleBar.vue"), "utf8");
const playerStyles = readFileSync(resolve("src/styles/player.css"), "utf8");
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

  it("renders both configured title-bar brand names", () => {
    expect(titleBarSource).toContain("store.state.branding.brandName");
    expect(titleBarSource).toContain("store.state.branding.brandEnglishName");
    expect(titleBarSource).not.toContain(">梦鱼服</span>");
  });

  it("truncates long brand names without pushing the navigation", () => {
    expect(playerStyles).toMatch(/\.top-brand\s*\{[\s\S]*?max-width:\s*280px/);
    expect(playerStyles).toMatch(
      /\.brand-chinese,\s*\.brand-english\s*\{[\s\S]*?text-overflow:\s*ellipsis/,
    );
  });

  it("keeps music controls out of the draggable title bar", () => {
    expect(titleBarSource).not.toContain("music-select");
    expect(titleBarSource).not.toContain("music-button");
    expect(titleBarSource).not.toContain("store.toggleStartupMusic()");
    expect(playerStyles).toMatch(/\.music-player\s*\{/);
  });
});
