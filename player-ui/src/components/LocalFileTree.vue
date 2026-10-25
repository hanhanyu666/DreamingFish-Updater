<script setup lang="ts">
import { computed, ref } from "vue";
import { buildTree, buildVisibleEntries } from "../lib/fileTree";
import { usePlayerStore } from "../stores/player";
import AppIcon from "./AppIcon.vue";
import TreeBranch from "./TreeBranch.vue";

const store = usePlayerStore();
const search = ref("");

const query = computed(() => search.value.trim().toLowerCase());
const queryActive = computed(() => query.value.length > 0);
const visibleEntries = computed(() => buildVisibleEntries(store.state.files, query.value));
const tree = computed(() => buildTree(visibleEntries.value));
const emptyText = computed(() =>
  queryActive.value ? "没有匹配的文件或目录" : "当前版本没有受管理文件",
);
</script>

<template>
  <div class="local-file-page">
    <div class="file-tools">
      <label class="search-field">
        <AppIcon name="search" />
        <input v-model="search" class="mod-search" placeholder="搜索目录、文件名或路径" type="search" aria-label="搜索本地文件" />
      </label>
      <span class="file-legend" aria-hidden="true">
        <span><i class="legend-managed"></i>随整合包更新</span>
        <span><i class="legend-self"></i>自行管理</span>
        <span><AppIcon name="lock" />服主锁定</span>
      </span>
    </div>
    <div class="local-file-tree-pane">
      <div v-if="visibleEntries.length === 0" class="drawer-empty local-file-empty">
        {{ emptyText }}
      </div>
      <div v-else class="local-file-tree" role="tree" aria-label="本地文件">
        <TreeBranch
          v-for="node in tree"
          :key="node.entry.path"
          :node="node"
          :depth="0"
          :expanded="store.state.fileTreeExpanded"
          :query-active="queryActive"
        />
      </div>
    </div>
  </div>
</template>
