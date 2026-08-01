import { describe, expect, it } from "vitest";
import {
  buildTree,
  buildVisibleEntries,
  defaultExpanded,
  detailText,
  entryManaged,
  isPathExpanded,
} from "../fileTree";
import type { LocalFileEntry } from "../types";

const entries: LocalFileEntry[] = [
  { path: "mods", displayName: "mods", directory: true, directlyExcluded: false,
    inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
    policy: null, managedFileCount: 2 },
  { path: "mods/core.jar", displayName: "Core", directory: false, directlyExcluded: false,
    inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
    policy: "ENFORCED", managedFileCount: 0 },
  { path: "config", displayName: "config", directory: true, directlyExcluded: false,
    inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
    policy: null, managedFileCount: 1 },
  { path: "config/voice.toml", displayName: "voice.toml", directory: false,
    directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false,
    present: true, forced: false, policy: "DEFAULT", managedFileCount: 0 },
];

describe("buildVisibleEntries", () => {
  it("keeps everything without a query", () => {
    expect(buildVisibleEntries(entries, "")).toHaveLength(4);
  });

  it("shows matches plus ancestors and directory descendants", () => {
    const visible = buildVisibleEntries(entries, "voice");
    expect(visible.map((entry) => entry.path)).toEqual(["config", "config/voice.toml"]);
  });

  it("expands a matching directory to its children", () => {
    const visible = buildVisibleEntries(entries, "core");
    expect(visible.map((entry) => entry.path)).toEqual(["mods", "mods/core.jar"]);
  });
});

describe("buildTree", () => {
  it("builds a parent-child hierarchy", () => {
    const tree = buildTree(entries);
    expect(tree).toHaveLength(2);
    const mods = tree.find((node) => node.entry.path === "mods");
    expect(mods?.children.map((child) => child.entry.path)).toEqual(["mods/core.jar"]);
  });
});

describe("file entry helpers", () => {
  it("computes managed state", () => {
    const file = entries[1];
    expect(entryManaged(file)).toBe(true);
    expect(entryManaged({ ...file, directlyExcluded: true })).toBe(false);
    expect(entryManaged({ ...file, inheritedExclusion: "mods" })).toBe(false);
    expect(entryManaged({ ...file, forced: true })).toBe(true);
  });

  it("formats details with policy labels", () => {
    expect(detailText(entries[1])).toContain("ENFORCED · 校验并同步");
    expect(detailText(entries[3])).toContain("DEFAULT · 仅缺失时安装");
    expect(detailText(entries[0])).toContain("2 个远程文件");
  });

  it("defaults expansion for top-level directories only", () => {
    expect(defaultExpanded("mods", false)).toBe(true);
    expect(defaultExpanded("mods/core.jar", false)).toBe(false);
    expect(defaultExpanded("mods/core.jar", true)).toBe(true);
    const remembered = new Map([["mods", false]]);
    expect(isPathExpanded(remembered, "mods", false)).toBe(false);
    expect(isPathExpanded(remembered, "mods", true)).toBe(true);
  });

  it("forces collapsed ancestors open while searching for a descendant", () => {
    const remembered = new Map([["config", false]]);
    const visible = buildVisibleEntries(entries, "voice");

    expect(visible.map((entry) => entry.path)).toEqual(["config", "config/voice.toml"]);
    expect(isPathExpanded(remembered, "config", true)).toBe(true);
  });
});
