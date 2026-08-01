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
import { usePlayerStore } from "../stores/player";

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
    <div class="local-file-row" :style="{ paddingLeft: depth * 18 + 'px' }">
      <button
        v-if="node.children.length > 0"
        type="button"
        class="tree-disclosure"
        :class="{ open: expanded() }"
        @click="toggle"
      >
        <span class="tree-disclosure-arrow"></span>
      </button>
      <span v-else class="tree-disclosure-placeholder"></span>
      <div class="local-file-labels" :title="detailText(node.entry)">
        <div class="local-file-name">
          {{ node.entry.displayName }}{{ node.entry.directory ? "/" : "" }}
        </div>
        <div class="local-file-detail">{{ detailText(node.entry) }}</div>
      </div>
      <label
        class="mod-toggle"
        :class="{ disabled: node.entry.forced || node.entry.inheritedExclusion != null }"
        :title="checkboxTooltip(node.entry)"
      >
        <input
          type="checkbox"
          :checked="entryManaged(node.entry)"
          :indeterminate="node.entry.partiallyExcluded && !node.entry.forced"
          :disabled="node.entry.forced || node.entry.inheritedExclusion != null"
          @change="toggleEntry(($event.target as HTMLInputElement).checked)"
        />
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
