import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const appSource = readFileSync(resolve("src/App.vue"), "utf8");

describe("player window lifecycle", () => {
  it("shows the native window before enabling startup music", () => {
    expect(appSource).toMatch(
      /await bridge\.window\.show\(\);\s*await store\.enableStartupMusic\(\);/,
    );
    expect(appSource).not.toMatch(
      /onMounted\([\s\S]*?store\.initializeStartupMusic\(\)/,
    );
  });
});
