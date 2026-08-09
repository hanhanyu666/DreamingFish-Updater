import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const appSource = readFileSync(resolve("src/App.vue"), "utf8");

describe("player window lifecycle", () => {
  it("shows progress before startup is complete without enabling music early", () => {
    expect(appSource).toMatch(
      /store\.state\.progress[\s\S]*?progress != null[\s\S]*?completeStartup\(\)/,
    );
    expect(appSource).toMatch(
      /async function completeStartup[\s\S]*?await showUpdaterWindow\(\)[\s\S]*?await store\.enableStartupMusic\(\)/,
    );
    expect(appSource).toMatch(
      /async \(\) => \{[\s\S]*?await bridge\.window\.show\(\);[\s\S]*?windowShown\.value = true/,
    );
    expect(appSource).not.toMatch(
      /onMounted\([\s\S]*?store\.initializeStartupMusic\(\)/,
    );
  });
});
