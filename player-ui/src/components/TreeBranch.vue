<script setup lang="ts">
import { getBridge } from "../lib/bridge";
import {
  checkboxLabel,
  checkboxTooltip,
  detailText,
  entryManaged,
  isPathExpanded,
  type TreeNode,
} from "../lib/fileTree";
import { canRestoreDefault } from "../lib/maintenance";
import { usePlayerStore } from "../stores/player";
import AppIcon from "./AppIcon.vue";

const props = defineProps<{
  node: TreeNode;
  depth: number;
  expanded: ReadonlyMap<string, boolean>;
  queryActive: boolean;
}>();

const store = usePlayerStore();
const bridge = getBridge();

function expanded(): boolean {
  return isPathExpanded(props.expanded, props.node.entry.path, props.queryActive);
}

function toggle(): void {
  const folded = props.node.entry.path.replace(/\\/g, "/").toLowerCase();
  const next = new Map(props.expanded);
  next.set(folded, !expanded());
  store.setFileTreeExpanded(next);
}

function toggleEntry(checked: boolean): void {
  const entry = props.node.entry;
  const requested = entry.partiallyExcluded || checked;
  bridge.sendCommand({ command: "toggle-file", entry, managed: requested });
}
</script>

<template>
  <div class="local-file-branch">
    <div
      class="local-file-row"
      :class="{ unmanaged: !entryManaged(node.entry) }"
      role="treeitem"
      :aria-expanded="node.children.length > 0 ? expanded() : undefined"
      :style="{ paddingLeft: 14 + depth * 22 + 'px' }"
    >
      <button
        v-if="node.children.length > 0"
        type="button"
        class="tree-disclosure"
        :class="{ open: expanded() }"
        :aria-label="(expanded() ? '收起 ' : '展开 ') + node.entry.path"
        @click="toggle"
      >
        <AppIcon name="chevron" />
      </button>
      <span v-else class="tree-disclosure-placeholder"></span>
      <AppIcon :name="node.entry.directory ? 'files' : 'document'" class="local-file-icon" :class="{ folder: node.entry.directory }" />
      <div class="local-file-labels" :title="detailText(node.entry)">
        <div class="local-file-name">
          {{ node.entry.displayName }}{{ node.entry.directory ? "/" : "" }}
        </div>
        <div class="local-file-detail">{{ detailText(node.entry) }}</div>
      </div>
      <button
        v-if="canRestoreDefault(node.entry)"
        type="button"
        class="file-reset-button"
        title="下次更新时换回服主提供的默认配置，你现在的这份会先移入备份"
        @click="store.requestDefaultReset(node.entry)"
      >
        恢复默认
      </button>
      <span v-else-if="node.entry.resetPending" class="file-reset-pending">等待恢复默认</span>
      <label
        class="file-manage-toggle"
        :class="{ disabled: node.entry.forced || node.entry.inheritedExclusion != null, forced: node.entry.forced }"
        :title="checkboxTooltip(node.entry)"
      >
        <input
          type="checkbox"
          class="dfs-check"
          :checked="entryManaged(node.entry)"
          :indeterminate="node.entry.partiallyExcluded && !node.entry.forced"
          :disabled="node.entry.forced || node.entry.inheritedExclusion != null"
          :aria-label="'管理 ' + node.entry.path"
          @change="toggleEntry(($event.target as HTMLInputElement).checked)"
        />
        <AppIcon v-if="node.entry.forced" name="lock" class="file-manage-lock" />
        <span>{{ checkboxLabel(node.entry) }}</span>
      </label>
    </div>
    <div v-if="expanded() && node.children.length > 0" class="local-file-children">
      <TreeBranch
        v-for="child in node.children"
        :key="child.entry.path"
        :node="child"
        :depth="depth + 1"
        :expanded="props.expanded"
        :query-active="queryActive"
      />
    </div>
  </div>
</template>
