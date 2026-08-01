import { foldPath, parentPath, pathDepth } from "./format";
import type { LocalFileEntry } from "./types";

export interface TreeNode {
  entry: LocalFileEntry;
  children: TreeNode[];
}

export function entryManaged(entry: LocalFileEntry): boolean {
  return entry.forced || (!entry.directlyExcluded && entry.inheritedExclusion == null);
}

export function checkboxLabel(entry: LocalFileEntry): string {
  if (entry.forced) return "强制";
  if (entry.inheritedExclusion != null) return "随目录";
  return "管理";
}

export function checkboxTooltip(entry: LocalFileEntry): string {
  if (entry.forced) return "管理端已为该目录启用强制同步";
  if (entry.inheritedExclusion != null) return "由目录 " + entry.inheritedExclusion + " 控制";
  return "";
}

export function detailText(entry: LocalFileEntry): string {
  const details: string[] = [];
  if (entry.forced) {
    details.push("服务器强制同步");
  } else if (entry.inheritedExclusion != null) {
    details.push("随 " + entry.inheritedExclusion + " 不受管理");
  } else if (entry.directlyExcluded) {
    details.push("本机不受管理");
  } else if (entry.partiallyExcluded) {
    details.push("部分子项不受管理");
  } else {
    details.push("由更新器管理");
  }
  if (entry.directory) {
    details.push(entry.managedFileCount + " 个远程文件");
  } else if (!entry.present) {
    details.push("当前版本中已不存在");
  } else if (entry.policy === "DEFAULT") {
    details.push("DEFAULT · 仅缺失时安装");
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
