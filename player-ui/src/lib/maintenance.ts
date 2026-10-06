import type {
  ArchiveDto,
  LocalFileEntry,
  LocalModEntry,
  MaintenancePreset,
  OptionalGroupView,
} from "./types";

/** Player-facing names of the owner's maintenance presets. */
export const PRESET_LABELS: Record<MaintenancePreset, string> = {
  REQUIRED: "强制同步",
  SYNC: "普通同步",
  INITIAL: "首次提供",
  DEFAULT_CONFIG: "旧版：未修改才更新",
  LEGACY_MISSING_ONLY: "缺失时补齐",
};

/** What each preset means for the player's own copy. */
export const PRESET_HINTS: Record<MaintenancePreset, string> = {
  REQUIRED: "始终和服主保持一致",
  SYNC: "跟随服主更新",
  INITIAL: "只在缺失时放一份，之后由你决定",
  DEFAULT_CONFIG: "你没改过就跟随更新，改过就保留你的",
  LEGACY_MISSING_ONLY: "只在缺失时补齐",
};

export function presetLabel(preset: MaintenancePreset | null | undefined): string | null {
  return preset == null ? null : PRESET_LABELS[preset] ?? null;
}

/**
 * Who controls a mod and in which state it is, for the mod list. A locked mod
 * explains itself on its own line, so its source can be left out here.
 */
export function modSourceText(entry: LocalModEntry, includeSource = true): string {
  const parts: string[] = [];
  if (!includeSource) {
    // The lock line names the owner rule.
  } else if (entry.preset === "REQUIRED") {
    parts.push("服主强制");
  } else if (entry.group != null) {
    parts.push("可选内容“" + (entry.groupTitle ?? entry.group) + "”");
  } else if (entry.managed) {
    parts.push("整合包");
  } else {
    parts.push("玩家添加");
  }
  if (entry.version) parts.push("版本 " + entry.version);
  if (entry.disabled && !entry.forced) {
    parts.push(entry.active ? "等待停用" : "已停用");
  } else if (entry.disabled && entry.group != null) {
    parts.push(entry.active ? "等待关闭" : "已随可选内容关闭");
  }
  return parts.join("  ·  ");
}

export function modToggleLabel(entry: LocalModEntry): string {
  if (entry.preset === "REQUIRED" && entry.forced) return "必需";
  if (entry.group != null && entry.forced) return "随可选内容";
  if (entry.forced) return "固定";
  return "启用";
}

/** Whether the player can ask the next update to put the shipped default back. */
export function canRestoreDefault(entry: LocalFileEntry): boolean {
  return !entry.directory
    && (entry.preset === "DEFAULT_CONFIG" || entry.preset === "INITIAL")
    && (entry.modified === true || !entry.present)
    && entry.resetPending !== true
    && !entry.directlyExcluded
    && entry.inheritedExclusion == null;
}

export function groupStateText(
  group: Pick<OptionalGroupView, "enabled" | "explicit" | "defaultInstall">,
): string {
  const state = group.enabled ? "已开启" : "已关闭";
  if (group.explicit) {
    return state + "  ·  你的选择（服主默认" + (group.defaultInstall ? "开启" : "关闭") + "）";
  }
  return state + "  ·  跟随服主默认";
}

export function archiveTitle(archive: Pick<ArchiveDto, "legacy" | "displayVersion">): string {
  if (archive.legacy) return "旧版备份";
  return archive.displayVersion ? "更新到版本 " + archive.displayVersion + " 时" : "更新时";
}

export function pendingRestoreCount(
  archive: { readonly files: ReadonlyArray<{ readonly restoredAt: string | null }> },
): number {
  return archive.files.filter((file) => file.restoredAt == null).length;
}
