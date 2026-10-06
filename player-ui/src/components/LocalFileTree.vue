<script setup lang="ts">
import { computed, ref } from "vue";
import { buildTree, buildVisibleEntries } from "../lib/fileTree";
import { usePlayerStore } from "../stores/player";
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
      <input v-model="search" class="mod-search" placeholder="搜索目录、文件名或路径" type="text" />
      <button type="button" class="restore-mods-button" @click="store.confirmRestoreFiles">
        恢复全部管理
      </button>
    </div>
    <div class="mod-warning">
      取消勾选“管理”后改为自行管理：更新不再安装、覆盖或删除它。服主设为强制同步的文件不能取消；首次提供的文件之后由你维护，也可以主动恢复默认；服主明确移除或处理问题版本时会先备份再移出。
    </div>
    <div class="local-file-tree-pane">
      <div v-if="visibleEntries.length === 0" class="drawer-empty local-file-empty">
        {{ emptyText }}
      </div>
      <div v-else class="local-file-tree">
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
