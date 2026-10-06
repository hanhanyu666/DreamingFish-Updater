<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { formatHistoryTime, playerAddedMods } from "../lib/format";
import { groupPlayerLogs, logLevelLabel } from "../lib/playerLogs";
import { DRAWER_LABELS, usePlayerStore, type DrawerMode } from "../stores/player";
import BackupList from "./BackupList.vue";
import LocalFileTree from "./LocalFileTree.vue";
import ModRow from "./ModRow.vue";
import OptionalGroups from "./OptionalGroups.vue";

const store = usePlayerStore();
const modSearch = ref("");
const playerModSearch = ref("");
const logsElement = ref<HTMLElement | null>(null);
const followLatestLog = ref(true);

const result = computed(() => store.state.result);

interface UpdateDetailRow {
  operation: string;
  operationClass: string;
  path: string;
  note: string | null;
}

const updateRows = computed<UpdateDetailRow[]>(() => {
  const rows: UpdateDetailRow[] = [];
  const resultValue = result.value;
  if (resultValue == null) return rows;
  const reasons = new Map((resultValue.archived ?? []).map((file) =>
    [file.path.replace(/\\/g, "/").toLowerCase(), file.reasonText]));
  appendRows(rows, "安装 / 更新", "update-operation-install", resultValue.installedPaths);
  appendRows(rows, "删除", "update-operation-delete", resultValue.deletedPaths);
  appendRows(rows, "移入备份", "update-operation-archive", resultValue.archivedFiles, reasons);
  appendRows(rows, "恢复默认", "update-operation-install", resultValue.resetPaths ?? []);
  appendRows(rows, "保留你的修改", "update-operation-release", resultValue.keptModifiedPaths ?? [],
    null, "服主更新了默认配置，你改过的版本没有被覆盖");
  appendRows(rows, "自行管理", "update-operation-release",
    resultValue.skippedSelfManagedPaths ?? [], null, "服主提供了新版本，你自行管理的文件没有更新");
  appendRows(rows, "放弃管理", "update-operation-release", resultValue.releasedPaths,
    null, "服主不再管理，本地文件已保留");
  return rows;
});

function appendRows(
  rows: UpdateDetailRow[],
  operation: string,
  operationClass: string,
  paths: readonly string[],
  notes: ReadonlyMap<string, string> | null = null,
  note: string | null = null,
): void {
  for (const path of [...paths].sort((left, right) =>
    left.localeCompare(right, undefined, { sensitivity: "base" }),
  )) {
    const normalized = path.replace(/\\/g, "/");
    rows.push({
      operation,
      operationClass,
      path: normalized,
      note: notes?.get(normalized.toLowerCase()) ?? note,
    });
  }
}

const updateCounts = computed(() => {
  const resultValue = result.value;
  if (resultValue == null) return "本次没有修改本地文件";
  const counts: string[] = [];
  const add = (label: string, values: readonly string[] | undefined) => {
    if (values != null && values.length > 0) counts.push(label + " " + values.length + " 项");
  };
  add("安装 / 更新", resultValue.installedPaths);
  add("删除", resultValue.deletedPaths);
  add("移入备份", resultValue.archivedFiles);
  add("恢复默认", resultValue.resetPaths);
  add("保留你的修改", resultValue.keptModifiedPaths);
  add("自行管理未更新", resultValue.skippedSelfManagedPaths);
  add("放弃管理", resultValue.releasedPaths);
  return counts.length === 0 ? "本次没有修改本地文件" : counts.join("  ·  ");
});

const drawerTabs = computed(() =>
  (["UPDATE", "HISTORY", "LOGS", "FILES", "BACKUPS", "PLAYER_MODS"] as DrawerMode[])
    .filter((mode) => mode !== "PLAYER_MODS" || playerMods.value.length > 0)
    .map((mode) => ({ mode, label: DRAWER_LABELS[mode] })),
);
const hasGroups = computed(() => store.state.groups.length > 0);

const updateChangelog = computed(() => {
  const changelog = result.value?.changelog;
  return changelog == null || changelog.trim().length === 0
    ? "本次发布没有填写更新说明。"
    : changelog.trim();
});

const historyReleases = computed(() => store.state.releaseHistory?.releases ?? []);
const logGroups = computed(() => groupPlayerLogs(store.state.logs));
const logEntries = computed(() => logGroups.value.flatMap((group) => group.entries));
const logSummary = computed(() => {
  const warnings = logEntries.value.filter((entry) => entry.level === "WARN").length;
  const errors = logEntries.value.filter((entry) => entry.level === "ERROR").length;
  const parts = [`共 ${logEntries.value.length} 条`];
  if (warnings > 0) parts.push(`提醒 ${warnings}`);
  if (errors > 0) parts.push(`错误 ${errors}`);
  return parts.join("  ·  ");
});

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
  async ([, mode], [, previousMode]) => {
    if (mode !== "LOGS") return;
    if (previousMode !== "LOGS") followLatestLog.value = true;
    await nextTick();
    if (logsElement.value != null && followLatestLog.value) {
      logsElement.value.scrollTop = logsElement.value.scrollHeight;
    }
  },
  { flush: "post" },
);

function onLogScroll(): void {
  const element = logsElement.value;
  if (element == null) return;
  followLatestLog.value =
    element.scrollHeight - element.scrollTop - element.clientHeight <= 28;
}

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
        v-for="tab in drawerTabs"
        :key="tab.mode"
        type="button"
        class="drawer-tab"
        :class="{ selected: store.state.drawerMode === tab.mode }"
        @click="store.openDrawer(tab.mode)"
      >
        {{ tab.label }}
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
            <span class="update-detail-labels">
              <span class="update-detail-path">{{ row.path }}</span>
              <span v-if="row.note" class="update-detail-note">{{ row.note }}</span>
            </span>
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

      <div v-if="store.state.drawerMode === 'LOGS'" ref="logsElement" class="log-list" @scroll="onLogScroll">
        <div v-if="store.state.logs.length === 0" class="drawer-empty">
          还没有运行记录
        </div>
        <template v-else>
          <div class="log-overview">
            <span>运行记录</span>
            <span>{{ logSummary }}</span>
          </div>
          <section v-for="group in logGroups" :key="group.key" class="log-day">
            <div class="log-day-heading">
              <span>{{ group.label }}</span>
              <span>{{ group.entries.length }} 条</span>
            </div>
            <template v-for="entry in group.entries" :key="entry.id">
              <div v-if="entry.level === 'START'" class="log-session-line">
                <time>{{ entry.time }}</time>
                <strong>{{ entry.message }}</strong>
                <span></span>
              </div>
              <article v-else class="log-entry" :class="'level-' + entry.level.toLowerCase()">
                <time class="log-time">{{ entry.time }}</time>
                <span class="log-level">{{ logLevelLabel(entry.level) }}</span>
                <div class="log-content">
                  <div class="log-message-row">
                    <span class="log-category">{{ entry.category }}</span>
                    <span class="log-message">{{ entry.message }}</span>
                  </div>
                  <details v-if="entry.details.length > 0" class="log-details">
                    <summary>查看异常详情</summary>
                    <pre>{{ entry.details.join("\n") }}</pre>
                  </details>
                </div>
              </article>
            </template>
          </section>
        </template>
      </div>

      <div v-if="store.state.drawerMode === 'FILES'" class="local-management-page">
        <div
          class="local-mode-bar"
          :class="{ 'with-options': hasGroups }"
          role="tablist"
          aria-label="本地文件管理方式"
        >
          <button
            v-if="hasGroups"
            type="button"
            class="local-mode-button"
            :class="{ selected: store.state.localMode === 'OPTIONS' }"
            role="tab"
            :aria-selected="store.state.localMode === 'OPTIONS'"
            aria-controls="local-option-panel"
            @click="store.showLocalMode('OPTIONS')"
          >
            <span class="local-mode-label">可选内容</span>
            <span class="local-mode-description">按电脑配置开关光影、美化等</span>
          </button>
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
        <OptionalGroups
          v-if="store.state.localMode === 'OPTIONS' && hasGroups"
          id="local-option-panel"
          role="tabpanel"
        />
        <LocalFileTree
          v-else-if="store.state.localMode === 'FILES'"
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
            停用必要模组可能导致游戏崩溃或无法连接服务器。服主设为必需的模组、以及由可选内容统一开关的模组不能在这里单独切换。更改会在本次更新完成前重新校验；游戏已经启动时则从下次启动生效。
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

      <BackupList v-if="store.state.drawerMode === 'BACKUPS'" />

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
