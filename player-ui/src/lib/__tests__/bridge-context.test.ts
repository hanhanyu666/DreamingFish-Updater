import { afterEach, expect, test, vi } from "vitest";

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules(); });

test("the native player window uses its sidecar bridge", async () => {
  const native: Record<string, unknown> = { __TAURI_INTERNALS__: {}, location: { pathname: "/" } };
  native.parent = native;
  vi.stubGlobal("window", native);
  vi.resetModules();
  expect((await import("../bridge")).getBridge().isTauri).toBe(true);
});

test("an embedded management preview never starts a native player sidecar", async () => {
  vi.stubGlobal("window", { __TAURI_INTERNALS__: {}, parent: {}, location: { pathname: "/player-preview/index.html" } });
  vi.resetModules();
  expect((await import("../bridge")).getBridge().isTauri).toBe(false);
});

test("the standalone server preview stays in preview mode inside a desktop webview", async () => {
  const preview: Record<string, unknown> = { __TAURI_INTERNALS__: {}, location: { pathname: "/player-preview/" } };
  preview.parent = preview;
  vi.stubGlobal("window", preview);
  vi.resetModules();
  expect((await import("../bridge")).getBridge().isTauri).toBe(false);
});
