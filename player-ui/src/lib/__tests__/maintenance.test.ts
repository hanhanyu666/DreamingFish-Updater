import { describe, expect, it } from "vitest";
import { detailText } from "../fileTree";
import {
  archiveTitle,
  canRestoreDefault,
  groupStateText,
  modSourceText,
  modToggleLabel,
  pendingRestoreCount,
} from "../maintenance";
import type { LocalFileEntry, LocalModEntry } from "../types";

const mod: LocalModEntry = {
  key: "component:iris", displayName: "Iris Shaders", path: "mods/iris.jar",
  componentId: "iris", managed: true, disabled: false, active: true, forced: false,
};

const config: LocalFileEntry = {
  path: "config/voice.toml", displayName: "voice.toml", directory: false,
  directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false,
  present: true, forced: false, policy: "ENFORCED", managedFileCount: 0,
  preset: "DEFAULT_CONFIG", modified: true, resetPending: false,
};

describe("mod wording", () => {
  it("names who controls a mod and its version", () => {
    expect(modSourceText({ ...mod, version: "1.7.2" })).toBe("整合包  ·  版本 1.7.2");
    expect(modSourceText({ ...mod, managed: false })).toBe("玩家添加");
    expect(modSourceText({ ...mod, preset: "REQUIRED", forced: true })).toBe("服主强制");
    expect(modSourceText({ ...mod, group: "visuals", groupTitle: "光影与美化", forced: true,
      disabled: true, active: false })).toBe("可选内容“光影与美化”  ·  已随可选内容关闭");
    expect(modSourceText({ ...mod, version: "1.7.2" }, false)).toBe("版本 1.7.2");
  });

  it("labels locked switches by their reason", () => {
    expect(modToggleLabel(mod)).toBe("启用");
    expect(modToggleLabel({ ...mod, preset: "REQUIRED", forced: true })).toBe("必需");
    expect(modToggleLabel({ ...mod, group: "visuals", forced: true })).toBe("随可选内容");
  });
});

describe("default configuration restore", () => {
  it("is offered only for a changed default the updater still manages", () => {
    expect(canRestoreDefault(config)).toBe(true);
    expect(canRestoreDefault({ ...config, modified: false })).toBe(false);
    expect(canRestoreDefault({ ...config, resetPending: true })).toBe(false);
    expect(canRestoreDefault({ ...config, preset: "SYNC" })).toBe(false);
    expect(canRestoreDefault({ ...config, directlyExcluded: true })).toBe(false);
    expect(canRestoreDefault({ ...config, inheritedExclusion: "config" })).toBe(false);
  });

  it("explains presets and pending restores in the file tree", () => {
    expect(detailText(config)).toContain("旧版：未修改才更新 · 你没改过就跟随更新，改过就保留你的");
    expect(detailText(config)).toContain("你改过，更新时保留你的版本");
    expect(detailText({ ...config, resetPending: true })).toContain("下次更新时恢复默认");
    expect(detailText({ ...config, preset: "REQUIRED", forced: true }))
      .toBe("强制同步 · 始终和服主保持一致  ·  config/voice.toml");
    expect(detailText({ ...config, directlyExcluded: true })).toMatch(/^你自行管理/);
    expect(detailText({ ...config, forced: true, group: "visuals",
      lockReason: "由可选内容“光影与美化”统一开关" })).toMatch(/^由可选内容“光影与美化”统一开关/);
  });
});

describe("initial restore", () => {
  it("offers an explicit restore for changed or missing initial player files", () => {
    expect(canRestoreDefault({ ...config, preset: "INITIAL" })).toBe(true);
    expect(canRestoreDefault({ ...config, preset: "INITIAL", present: false, modified: false })).toBe(true);
    expect(canRestoreDefault({ ...config, preset: "INITIAL", modified: false })).toBe(false);
    expect(canRestoreDefault({ ...config, preset: "INITIAL", resetPending: true })).toBe(false);
  });
});

describe("optional groups and backups", () => {
  it("says whether a group follows the owner's default", () => {
    expect(groupStateText({ enabled: true, explicit: false, defaultInstall: true }))
      .toBe("已开启  ·  跟随服主默认");
    expect(groupStateText({ enabled: true, explicit: true, defaultInstall: false }))
      .toBe("已开启  ·  你的选择（服主默认关闭）");
  });

  it("titles archives by the release that made them", () => {
    expect(archiveTitle({ legacy: false, displayVersion: "1.2.0" })).toBe("更新到版本 1.2.0 时");
    expect(archiveTitle({ legacy: true, displayVersion: null })).toBe("旧版备份");
    expect(pendingRestoreCount({ files: [{ restoredAt: null }, { restoredAt: "2026-10-02T00:00:00Z" }] }))
      .toBe(1);
  });
});
