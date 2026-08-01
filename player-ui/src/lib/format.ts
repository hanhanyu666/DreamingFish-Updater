import type { LocalModEntry } from "./types";

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + " B";
  const kib = bytes / 1024.0;
  if (kib < 1024) return kib.toFixed(1) + " KB";
  const mib = kib / 1024.0;
  if (mib < 1024) return mib.toFixed(1) + " MB";
  return (mib / 1024.0).toFixed(2) + " GB";
}

export function formatAmount(amount: number): string {
  return amount < 1024 ? String(amount) : formatBytes(amount);
}

export function displayPath(path: string | null | undefined, fallback: string): string {
  if (path && path.trim().length > 0) return path;
  const safe = fallback && fallback.trim().length > 0 ? fallback : "正在处理";
  return safe;
}

export function validColor(value: string | null | undefined, fallback: string): string {
  return value && /^#[0-9a-fA-F]{6}$/.test(value) ? value : fallback;
}

export function ellipsize(value: string, maximum: number): string {
  if (value.length <= maximum) return value;
  return value.substring(0, Math.max(0, maximum - 1)).trimEnd() + "…";
}

function size(values: readonly unknown[] | null | undefined): number {
  return values == null ? 0 : values.length;
}

function appendFileSection(
  lines: string[],
  title: string,
  paths: readonly string[] | null | undefined,
  remaining: number,
): number {
  if (paths == null || paths.length === 0 || remaining <= 0) return remaining;
  if (lines.length > 0) lines.push("");
  lines.push(title);
  const shown = Math.min(paths.length, remaining);
  for (let index = 0; index < shown; index++) {
    lines.push("  " + paths[index].replace(/\\/g, "/"));
  }
  return remaining - shown;
}

export function formatUpdateFileDetails(
  installed: readonly string[] | null | undefined,
  deleted: readonly string[] | null | undefined,
  archived: readonly string[] | null | undefined,
  released: readonly string[] | null | undefined = [],
): string {
  const lines: string[] = [];
  let remaining = 30;
  remaining = appendFileSection(lines, "安装 / 更新", installed, remaining);
  remaining = appendFileSection(lines, "删除", deleted, remaining);
  remaining = appendFileSection(lines, "移入备份", archived, remaining);
  appendFileSection(lines, "放弃管理（保留本地文件）", released, remaining);
  if (lines.length === 0) return "本次没有修改本地文件";
  const total =
    size(installed) + size(deleted) + size(archived) + size(released);
  const displayed = Math.min(total, 30);
  if (total > displayed) {
    lines.push("");
    lines.push("另外 " + (total - displayed) + " 项未展开");
  }
  return lines.join("\n");
}

export function formatUnmanagedModDetails(mods: string[] | null | undefined): string {
  const values = mods == null ? [] : mods;
  const lines: string[] = [];
  lines.push("玩家自选模组（" + values.length + " 个）");
  values
    .slice(0, 20)
    .map((path) => path.replace(/\\/g, "/"))
    .forEach((path) => lines.push("  " + path));
  if (values.length > 20) lines.push("  另有 " + (values.length - 20) + " 个未展开");
  lines.push("");
  lines.push("点击进入“自选模组”标签页");
  return lines.join("\n");
}

export function foldPath(path: string): string {
  return path.replace(/\\/g, "/").toLowerCase();
}

export function parentPath(path: string): string | null {
  const slash = path.lastIndexOf("/");
  return slash < 0 ? null : path.substring(0, slash);
}

export function pathDepth(path: string): number {
  let count = 0;
  for (const character of path) if (character === "/") count++;
  return count;
}

export function modNameFromPath(path: string): string {
  const slash = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  const value = slash < 0 ? path : path.substring(slash + 1);
  return value.toLowerCase().endsWith(".jar")
    ? value.substring(0, value.length - 4)
    : value;
}

export function playerAddedMods(
  scanned: readonly LocalModEntry[] | null | undefined,
  detectedPaths: readonly string[] | null | undefined,
): LocalModEntry[] {
  const entries = new Map<string, LocalModEntry>();
  if (scanned != null) {
    for (const entry of scanned) {
      if (!entry.managed) entries.set(foldPath(entry.path), entry);
    }
  }
  if (detectedPaths != null) {
    for (const detected of detectedPaths) {
      const path = detected.replace(/\\/g, "/");
      const folded = foldPath(path);
      if (!entries.has(folded)) {
        entries.set(folded, {
          key: "path:" + folded,
          displayName: modNameFromPath(detected),
          path,
          componentId: null,
          managed: false,
          disabled: false,
          active: true,
          forced: false,
        });
      }
    }
  }
  return [...entries.values()].sort((left, right) => {
    if (left.disabled !== right.disabled) return left.disabled ? -1 : 1;
    return left.displayName.localeCompare(right.displayName, undefined, {
      sensitivity: "base",
    });
  });
}

export function formatHistoryTime(createdAt: string): string {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (value: number) => String(value).padStart(2, "0");
  return (
    date.getFullYear() +
    "." +
    pad(date.getMonth() + 1) +
    "." +
    pad(date.getDate()) +
    "  " +
    pad(date.getHours()) +
    ":" +
    pad(date.getMinutes())
  );
}

export function formatNewsDate(value: string): string {
  const date = new Date(value + "T00:00:00");
  if (Number.isNaN(date.getTime())) return "";
  const pad = (input: number) => String(input).padStart(2, "0");
  return date.getFullYear() + "." + pad(date.getMonth() + 1) + "." + pad(date.getDate());
}
