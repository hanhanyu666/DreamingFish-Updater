import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const appSource = readFileSync(resolve("src/App.vue"), "utf8");
const playerStyles = readFileSync(resolve("src/styles/player.css"), "utf8");

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
    expect(appSource).toMatch(
      /'drawer-open': store\.state\.drawerOpen[\s\S]*?'drawer-expanded': store\.state\.drawerOpen && store\.state\.drawerExpanded/,
    );
  });

  it("keeps launch notices away from the local management drawer", () => {
    expect(playerStyles).toMatch(
      /\.launch-notice-layer\.drawer-open\s*\{[\s\S]*?right:\s*620px/,
    );
    expect(playerStyles).toMatch(
      /\.launch-notice-layer\.drawer-expanded\s*\{[\s\S]*?visibility:\s*hidden;[\s\S]*?animation:\s*none/,
    );
    expect(playerStyles).toMatch(
      /@media \(max-width:\s*820px\)[\s\S]*?\.launch-notice-layer\.drawer-open\s*\{[\s\S]*?visibility:\s*hidden;[\s\S]*?animation:\s*none/,
    );
  });
});
