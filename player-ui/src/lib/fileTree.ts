import { foldPath, parentPath, pathDepth } from "./format";
import { PRESET_HINTS, PRESET_LABELS } from "./maintenance";
import type { LocalFileEntry } from "./types";

export interface TreeNode {
  entry: LocalFileEntry;
  children: TreeNode[];
}

export function entryManaged(entry: LocalFileEntry): boolean {
  return entry.forced || (!entry.directlyExcluded && entry.inheritedExclusion == null);
}

export function checkboxLabel(entry: LocalFileEntry): string {
  if (entry.forced) {
    if (entry.group != null) return "随可选内容";
    return entry.preset === "REQUIRED" || entry.directory ? "必需" : "固定";
  }
  if (entry.inheritedExclusion != null) return "随目录";
  return "管理";
}

export function checkboxTooltip(entry: LocalFileEntry): string {
  if (entry.forced) return entry.lockReason ?? "服主设为强制同步，不能在本机取消管理";
  if (entry.inheritedExclusion != null) return "由目录 " + entry.inheritedExclusion + " 控制";
  return "取消勾选后改为自行管理：更新器不再安装、覆盖或删除它";
}

export function detailText(entry: LocalFileEntry): string {
  const details: string[] = [];
  const preset = entry.preset ?? null;
  if (entry.forced && entry.group != null) {
    details.push(entry.lockReason ?? "由可选内容统一开关");
  } else if (entry.forced) {
    details.push(preset === "REQUIRED" && !entry.directory
      ? PRESET_LABELS.REQUIRED + " · " + PRESET_HINTS.REQUIRED
      : "服主强制同步");
  } else if (entry.inheritedExclusion != null) {
    details.push("随 " + entry.inheritedExclusion + " 自行管理");
  } else if (entry.directlyExcluded) {
    details.push("你自行管理");
  } else if (entry.partiallyExcluded) {
    details.push("部分子项自行管理");
  } else if (preset != null && !entry.directory && entry.present) {
    details.push(PRESET_LABELS[preset] + " · " + PRESET_HINTS[preset]);
  } else {
    details.push("由更新器管理");
  }
  if (entry.directory) {
    details.push(entry.managedFileCount + " 个远程文件");
  } else if (!entry.present) {
    details.push("当前版本中已不存在");
  } else if (preset != null) {
    if (entry.resetPending) {
      details.push("下次更新时恢复默认");
    } else if (preset === "DEFAULT_CONFIG" && entry.modified === true && entryManaged(entry)) {
      details.push("你改过，更新时保留你的版本");
    }
  } else if (entry.policy === "DEFAULT") {
    details.push("旧版发布兼容 · 仅缺失时补齐");
  } else {
    details.push("ENFORCED · 校验并同步");
  }
  details.push(entry.path);
  return details.join("  ·  ");
}

export function addVisibleAncestors(visible: Set<string>, path: string): void {
  let parent = parentPath(path);
  while (parent != null) {
    visible.add(foldPath(parent));
    parent = parentPath(parent);
  }
}

export function buildVisibleEntries(
  entries: readonly LocalFileEntry[],
  query: string,
): LocalFileEntry[] {
  const visible = new Set<string>();
  if (query.length === 0) {
    entries.forEach((entry) => visible.add(foldPath(entry.path)));
  } else {
    for (const entry of entries) {
      const matches =
        entry.path.toLowerCase().includes(query) ||
        entry.displayName.toLowerCase().includes(query);
      if (!matches) continue;
      visible.add(foldPath(entry.path));
      addVisibleAncestors(visible, entry.path);
      if (entry.directory) {
        const prefix = foldPath(entry.path) + "/";
        entries
          .map((candidate) => candidate.path)
          .filter((path) => foldPath(path).startsWith(prefix))
          .forEach((path) => visible.add(foldPath(path)));
      }
    }
  }
  return entries
    .filter((entry) => visible.has(foldPath(entry.path)))
    .sort(
      (left, right) =>
        pathDepth(left.path) - pathDepth(right.path) ||
        left.path.localeCompare(right.path, undefined, { sensitivity: "base" }),
    );
}

export function buildTree(entries: LocalFileEntry[]): TreeNode[] {
  const roots: TreeNode[] = [];
  const nodes = new Map<string, TreeNode>();
  for (const entry of entries) {
    const node: TreeNode = { entry, children: [] };
    nodes.set(foldPath(entry.path), node);
    const parent = parentPath(entry.path);
    if (parent == null) {
      roots.push(node);
    } else {
      const parentNode = nodes.get(foldPath(parent));
      if (parentNode != null) parentNode.children.push(node);
      else roots.push(node);
    }
  }
  return roots;
}

export function defaultExpanded(path: string, queryActive: boolean): boolean {
  return queryActive || pathDepth(path) === 0;
}

export function isPathExpanded(
  expanded: ReadonlyMap<string, boolean>,
  path: string,
  queryActive: boolean,
): boolean {
  if (queryActive) return true;
  const folded = foldPath(path);
  const remembered = expanded.get(folded);
  return remembered != null ? remembered : defaultExpanded(path, queryActive);
}
