<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { formatHistoryTime, playerAddedMods } from "../lib/format";
import { usePlayerStore } from "../stores/player";
import type { LocalModEntry } from "../lib/types";
import LocalFileTree from "./LocalFileTree.vue";
import ModRow from "./ModRow.vue";

const store = usePlayerStore();
const modSearch = ref("");
const playerModSearch = ref("");
const logsElement = ref<HTMLElement | null>(null);

const result = computed(() => store.state.result);

interface UpdateDetailRow {
  operation: string;
  operationClass: string;
  path: string;
}

const updateRows = computed<UpdateDetailRow[]>(() => {
  const rows: UpdateDetailRow[] = [];
  const resultValue = result.value;
  if (resultValue == null) return rows;
  appendRows(rows, "安装 / 更新", resultValue.installedPaths);
  appendRows(rows, "删除", resultValue.deletedPaths);
  appendRows(rows, "移入备份", resultValue.archivedFiles);
  appendRows(rows, "放弃管理", resultValue.releasedPaths);
  return rows;
});

function appendRows(
  rows: UpdateDetailRow[],
  operation: string,
  paths: readonly string[],
): void {
  const operationClass =
    operation === "删除"
      ? "update-operation-delete"
      : operation === "移入备份"
        ? "update-operation-archive"
        : operation === "放弃管理"
          ? "update-operation-release"
          : "update-operation-install";
  for (const path of [...paths].sort((left, right) =>
    left.localeCompare(right, undefined, { sensitivity: "base" }),
  )) {
    rows.push({ operation, operationClass, path: path.replace(/\\/g, "/") });
  }
}

const updateCounts = computed(() => {
  const resultValue = result.value;
  if (resultValue == null) return "本次没有修改本地文件";
  const counts: string[] = [];
  if (resultValue.installedPaths.length > 0) {
    counts.push("安装 / 更新 " + resultValue.installedPaths.length + " 项");
  }
  if (resultValue.deletedPaths.length > 0) {
    counts.push("删除 " + resultValue.deletedPaths.length + " 项");
  }
  if (resultValue.archivedFiles.length > 0) {
    counts.push("移入备份 " + resultValue.archivedFiles.length + " 项");
  }
  if (resultValue.releasedPaths.length > 0) {
    counts.push("放弃管理 " + resultValue.releasedPaths.length + " 项");
  }
  return counts.length === 0 ? "本次没有修改本地文件" : counts.join("  ·  ");
});

const updateChangelog = computed(() => {
  const changelog = result.value?.changelog;
  return changelog == null || changelog.trim().length === 0
    ? "本次发布没有填写更新说明。"
    : changelog.trim();
});

const historyReleases = computed(() => store.state.releaseHistory?.releases ?? []);

const visibleMods = computed(() => {
  const query = modSearch.value.trim().toLowerCase();
  return store.state.mods.filter(
    (entry) =>
      query.length === 0 ||
      entry.displayName.toLowerCase().includes(query) ||
      entry.path.toLowerCase().includes(query) ||
      (entry.componentId != null && entry.componentId.toLowerCase().includes(query)),
  );
});

const modEmptyText = computed(() =>
  modSearch.value.trim().length === 0 ? "没有检测到模组" : "没有匹配的模组",
);

const playerMods = computed(() =>
  playerAddedMods(store.state.mods, store.state.unmanaged?.mods),
);
const visiblePlayerMods = computed(() => {
  const query = playerModSearch.value.trim().toLowerCase();
  return playerMods.value.filter(
    (entry) =>
      query.length === 0 ||
      entry.displayName.toLowerCase().includes(query) ||
      entry.path.toLowerCase().includes(query) ||
      (entry.componentId != null && entry.componentId.toLowerCase().includes(query)),
  );
});
const playerModCountText = computed(() => {
  const enabled = playerMods.value.filter((entry) => !entry.disabled).length;
  return "共 " + playerMods.value.length + " 个  ·  " + enabled + " 个启用";
});
const playerModEmptyText = computed(() =>
  playerMods.value.length === 0
    ? "没有检测到玩家自选模组"
    : "没有匹配的玩家自选模组",
);

watch(
  [() => store.state.logs.length, () => store.state.drawerMode],
  async () => {
    await nextTick();
    if (logsElement.value != null) {
      logsElement.value.scrollTop = logsElement.value.scrollHeight;
    }
  },
);

function expand(): void {
  store.setDrawerExpanded(!store.state.drawerExpanded);
}
</script>

<template>
  <div
    class="details-drawer"
    :class="{ expanded: store.state.drawerExpanded }"
  >
    <div class="drawer-header">
      <div class="drawer-title">更新与本地管理</div>
      <div class="drawer-header-spacer"></div>
      <button
        type="button"
        class="window-button drawer-expand-button"
        :title="store.state.drawerExpanded ? '恢复侧栏' : '铺满内容区'"
        @click="expand"
      >
        <span v-if="!store.state.drawerExpanded" class="window-glyph-box"></span>
        <span v-else class="window-glyph-restore">
          <span class="window-glyph-box"></span>
          <span class="window-glyph-box"></span>
        </span>
      </button>
      <button type="button" class="window-button" title="收起详情" @click="store.hideDrawer">
        <span class="window-glyph-close">
          <span class="window-glyph-line"></span>
          <span class="window-glyph-line"></span>
        </span>
      </button>
    </div>
    <div class="drawer-tabs">
      <button
        v-for="(label, mode) in {
          UPDATE: '本次更新',
          HISTORY: '更新记录',
          LOGS: '运行记录',
          FILES: '本地文件',
          PLAYER_MODS: '自选模组',
        }"
        :key="mode"
        type="button"
        class="drawer-tab"
        :class="{ selected: store.state.drawerMode === mode }"
        :style="mode === 'PLAYER_MODS' && playerMods.length === 0 ? { display: 'none' } : {}"
        @click="store.openDrawer(mode as 'UPDATE' | 'HISTORY' | 'LOGS' | 'FILES' | 'PLAYER_MODS')"
      >
        {{ label }}
      </button>
    </div>
    <div class="drawer-content">
      <div v-if="store.state.drawerMode === 'UPDATE'" class="update-details-page">
        <div class="update-detail-version">
          {{ result == null ? "尚未完成更新" : "版本 " + result.displayVersion }}
        </div>
        <div class="update-detail-changelog">
          {{
            result == null
              ? "完成更新后，可在这里查看本次修改的全部文件。"
              : updateChangelog
          }}
        </div>
        <div class="update-detail-counts">
          {{ result == null ? "本次暂无文件变更" : updateCounts }}
        </div>
        <div v-if="updateRows.length > 0" class="update-detail-list">
          <div v-for="row in updateRows" :key="row.operation + row.path" class="update-detail-row">
            <span class="update-operation" :class="row.operationClass">{{ row.operation }}</span>
            <span class="update-detail-path">{{ row.path }}</span>
          </div>
        </div>
        <div v-else class="drawer-empty">本次没有修改本地文件</div>
      </div>

      <div v-if="store.state.drawerMode === 'HISTORY'" class="history-scroll">
        <div v-if="historyReleases.length === 0" class="drawer-empty">
          还没有可显示的发布记录
        </div>
        <template v-else>
          <div v-for="(release, index) in historyReleases" :key="release.releaseId">
            <div class="history-entry">
              <div class="history-heading">
                <span class="history-version">版本 {{ release.displayVersion }}</span>
                <span v-if="index === 0" class="history-current">当前</span>
                <span class="history-heading-spacer"></span>
                <span class="history-time">{{ formatHistoryTime(release.createdAt) }}</span>
              </div>
              <div class="history-changelog">
                {{
                  release.changelog == null || release.changelog.trim().length === 0
                    ? "本次发布没有填写更新说明。"
                    : release.changelog.trim()
                }}
              </div>
            </div>
            <div v-if="index + 1 < historyReleases.length" class="drawer-divider"></div>
          </div>
        </template>
      </div>

      <div v-if="store.state.drawerMode === 'LOGS'" ref="logsElement" class="log-list">
        <div v-if="store.state.logs.length === 0" class="drawer-empty">
          本次运行还没有日志
        </div>
        <div v-for="(line, index) in store.state.logs" :key="index" class="log-line">
          {{ line }}
        </div>
      </div>

      <div v-if="store.state.drawerMode === 'FILES'" class="local-management-page">
        <div class="local-mode-bar" role="tablist" aria-label="本地文件管理方式">
          <button
            type="button"
            class="local-mode-button"
            :class="{ selected: store.state.localMode === 'FILES' }"
            role="tab"
            :aria-selected="store.state.localMode === 'FILES'"
            aria-controls="local-file-management-panel"
            @click="store.showLocalMode('FILES')"
          >
            <span class="local-mode-label">文件管理范围</span>
            <span class="local-mode-description">决定哪些文件随整合包更新</span>
          </button>
          <button
            type="button"
            class="local-mode-button"
            :class="{ selected: store.state.localMode === 'MODS' }"
            role="tab"
            :aria-selected="store.state.localMode === 'MODS'"
            aria-controls="local-mod-management-panel"
            @click="store.showLocalMode('MODS')"
          >
            <span class="local-mode-label">模组启停</span>
            <span class="local-mode-description">启用或停用整合包内模组</span>
          </button>
        </div>
        <LocalFileTree
          v-if="store.state.localMode === 'FILES'"
          id="local-file-management-panel"
          role="tabpanel"
        />
        <div v-else id="local-mod-management-panel" class="mod-page" role="tabpanel">
          <div class="file-tools">
            <input v-model="modSearch" class="mod-search" placeholder="搜索模组名称或文件名" type="text" />
            <button type="button" class="restore-mods-button" @click="store.confirmRestoreMods">
              恢复整合包默认
            </button>
          </div>
          <div class="mod-warning">
            停用必要模组可能导致游戏崩溃或无法连接服务器。更改会在本次更新完成前重新校验；游戏已经启动时则从下次启动生效。
          </div>
          <div v-if="visibleMods.length === 0" class="drawer-empty">{{ modEmptyText }}</div>
          <div v-else class="mod-list">
            <template v-for="(entry, index) in visibleMods" :key="entry.key">
              <ModRow :entry="entry" />
              <div v-if="index + 1 < visibleMods.length" class="drawer-divider"></div>
            </template>
          </div>
        </div>
      </div>

      <div v-if="store.state.drawerMode === 'PLAYER_MODS'" class="player-mod-page">
        <div class="file-tools">
          <input
            v-model="playerModSearch"
            class="mod-search"
            placeholder="搜索玩家自选模组"
            type="text"
          />
          <span class="player-mod-count">{{ playerModCountText }}</span>
        </div>
        <div class="mod-warning">
          这些模组不属于服务器整合包，更新器会保留玩家的本地选择。停用模组可能导致依赖缺失或无法进入服务器，请确认后再修改。
        </div>
        <div v-if="visiblePlayerMods.length === 0" class="drawer-empty">
          {{ playerModEmptyText }}
        </div>
        <div v-else class="mod-list player-mod-list">
          <template v-for="(entry, index) in visiblePlayerMods" :key="entry.key">
            <ModRow :entry="entry" />
            <div v-if="index + 1 < visiblePlayerMods.length" class="drawer-divider"></div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
