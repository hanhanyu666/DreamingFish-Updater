<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { changelogLines as parseChangelog, changelogText } from "../lib/changelog";
import { formatBytes, formatHistoryTime, playerAddedMods } from "../lib/format";
import type { IconName } from "../lib/icons";
import { groupPlayerLogs, logLevelLabel } from "../lib/playerLogs";
import { usePlayerStore, type DrawerMode, type LocalManagementMode } from "../stores/player";
import AppIcon from "./AppIcon.vue";
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

/** One rail entry; local management sections share the FILES drawer mode. */
type SectionId = "UPDATE" | "HISTORY" | "LOGS" | "OPTIONS" | "FILES" | "MODS" | "PLAYER_MODS" | "BACKUPS";

interface Section {
  id: SectionId;
  label: string;
  icon: IconName;
  mode: DrawerMode;
  localMode?: LocalManagementMode;
  badge?: string;
  alert?: boolean;
}

interface PageHeader {
  title: string;
  description: string;
  action?: { label: string; run: () => void };
}

interface UpdateDetailRow {
  operation: string;
  operationClass: string;
  path: string;
  note: string | null;
}

interface OperationCount {
  label: string;
  operationClass: string;
  count: number;
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
  appendRows(rows, "保留你的修改", "update-operation-kept", resultValue.keptModifiedPaths ?? [],
    null, "服主更新了默认配置，你改过的版本没有被覆盖");
  appendRows(rows, "自行管理", "update-operation-kept",
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

/** Counts in the same order as the rows, shown as coloured chips beside the version. */
const operationCounts = computed<OperationCount[]>(() => {
  const counts: OperationCount[] = [];
  for (const row of updateRows.value) {
    const last = counts[counts.length - 1];
    if (last != null && last.label === row.operation) last.count++;
    else counts.push({ label: row.operation, operationClass: row.operationClass, count: 1 });
  }
  return counts;
});

const updateChangelog = computed(() => changelogText(result.value?.changelog));

const historyReleases = computed(() => store.state.releaseHistory?.releases ?? []);
const logGroups = computed(() => groupPlayerLogs(store.state.logs));
const logEntries = computed(() => logGroups.value.flatMap((group) => group.entries));
const logErrors = computed(() => logEntries.value.filter((entry) => entry.level === "ERROR").length);
function levelCount(entries: readonly { level: string }[], level: string): number {
  return entries.filter((entry) => entry.level === level).length;
}

/** Lines that start with 新增、更新、修复、移除 or 调整 get a coloured tag; other text stays a paragraph. */
const changelogLines = computed(() => parseChangelog(result.value?.changelog));
const changelogTagged = computed(() => changelogLines.value.some((line) => line.tag));

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

const modCountText = computed(() => {
  const disabled = store.state.mods.filter((entry) => entry.disabled).length;
  return "共 " + store.state.mods.length + " 个" + (disabled > 0 ? " · " + disabled + " 个已停用" : " · 全部启用");
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
  return "共 " + playerMods.value.length + " 个 · " + enabled + " 个启用";
});
const playerModEmptyText = computed(() =>
  playerMods.value.length === 0
    ? "没有检测到玩家自选模组"
    : "没有匹配的玩家自选模组",
);

const hasGroups = computed(() => store.state.groups.length > 0);

const railGroups = computed<{ label: string; sections: Section[] }[]>(() => [
  {
    label: "更新",
    sections: [
      { id: "UPDATE", label: "本次更新", icon: "document", mode: "UPDATE" },
      { id: "HISTORY", label: "更新记录", icon: "history", mode: "HISTORY" },
      {
        id: "LOGS", label: "运行记录", icon: "terminal", mode: "LOGS",
        badge: logErrors.value > 0 ? logErrors.value + " 错误" : undefined, alert: logErrors.value > 0,
      },
    ],
  },
  {
    label: "本地管理",
    sections: [
      ...(hasGroups.value
        ? [{
            id: "OPTIONS", label: "可选内容", icon: "sliders", mode: "FILES", localMode: "OPTIONS",
            badge: String(store.state.groups.length),
          } satisfies Section]
        : []),
      { id: "FILES", label: "文件管理", icon: "files", mode: "FILES", localMode: "FILES" },
      {
        id: "MODS", label: "模组启停", icon: "cube", mode: "FILES", localMode: "MODS",
        badge: store.state.mods.length > 0 ? String(store.state.mods.length) : undefined,
      },
      ...(playerMods.value.length > 0
        ? [{
            id: "PLAYER_MODS", label: "自选模组", icon: "userPlus", mode: "PLAYER_MODS",
            badge: String(playerMods.value.length),
          } satisfies Section]
        : []),
      {
        id: "BACKUPS", label: "备份与恢复", icon: "archive", mode: "BACKUPS",
        badge: store.state.archivesLoaded && store.state.archives.length > 0
          ? String(store.state.archives.length) : undefined,
      },
    ],
  },
]);

const currentSection = computed<SectionId>(() => {
  const mode = store.state.drawerMode;
  if (mode !== "FILES") return mode;
  return store.state.localMode === "OPTIONS" && !hasGroups.value ? "FILES" : store.state.localMode;
});

function pick(section: Section): void {
  if (section.localMode != null) store.showLocalMode(section.localMode);
  store.openDrawer(section.mode);
}

const updateDescription = computed(() => {
  const resultValue = result.value;
  if (resultValue == null) return "完成更新后，可以在这里查看本次修改的全部文件。";
  const parts: string[] = [];
  const published = formatHistoryTime(resultValue.createdAt);
  if (published.length > 0) parts.push(published + " 发布");
  if (resultValue.downloadedBytes > 0) parts.push("下载 " + formatBytes(resultValue.downloadedBytes));
  return (parts.length > 0 ? parts.join(" · ") + "。" : "") + "下面是这次对你电脑上文件做的每一处改动。";
});

const header = computed<PageHeader>(() => {
  switch (currentSection.value) {
    case "UPDATE":
      return { title: "本次更新", description: updateDescription.value };
    case "HISTORY":
      return { title: "更新记录", description: "服主发布过的每个版本，以及当时写的更新说明。" };
    case "LOGS":
      return { title: "运行记录", description: "更新器每一步做了什么。遇到问题时，把这里截图发给服主最快。" };
    case "OPTIONS":
      return {
        title: "可选内容",
        description: "服主把这些内容设为可选，你可以按电脑配置决定是否安装。关闭后，相关模组会移出游戏并保留在本机，随时可以重新开启；更改从下次启动游戏时生效。",
      };
    case "FILES":
      return {
        title: "文件管理范围",
        description: "取消勾选“管理”后改为自行管理：更新不再安装、覆盖或删除它。服主设为强制同步的文件不能取消；首次提供的文件之后由你维护，也可以主动恢复默认；服主明确移除或处理问题版本时会先备份再移出。",
        action: { label: "恢复全部管理", run: store.confirmRestoreFiles },
      };
    case "MODS":
      return {
        title: "模组启停",
        description: "停用必要模组可能导致游戏崩溃或无法连接服务器。服主设为必需的模组、以及由可选内容统一开关的模组不能在这里单独切换。更改会在本次更新完成前重新校验；游戏已经启动时则从下次启动生效。",
        action: { label: "恢复整合包默认", run: store.confirmRestoreMods },
      };
    case "PLAYER_MODS":
      return {
        title: "自选模组",
        description: "这些模组不属于服务器整合包，更新器会保留玩家的本地选择。停用模组可能导致依赖缺失或无法进入服务器，请确认后再修改。",
      };
    case "BACKUPS":
      return {
        title: "备份与恢复",
        description: "更新器不会直接删除你的文件：被替换、清理、撤回或恢复默认的本地文件都会先移到这里，需要时可以放回游戏。",
        action: { label: "打开备份文件夹", run: () => store.openArchive() },
      };
  }
  return { title: "", description: "" };
});

const railMeta = computed(() => {
  const version = historyReleases.value[0]?.displayVersion ?? result.value?.displayVersion;
  const name = store.state.branding.productName.trim();
  return [name, version].filter((part) => part != null && part.length > 0).join(" · ");
});

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

function openGameDirectory(): void {
  store.sendCommand({ command: "open-directory" });
}

function panelId(section: SectionId): string {
  return "drawer-panel-" + section.toLowerCase().replace("_", "-");
}
</script>

<template>
  <aside
    class="details-drawer"
    :class="{ expanded: store.state.drawerExpanded }"
    aria-label="更新与本地管理"
  >
    <nav class="drawer-rail" aria-label="更新与本地管理分类">
      <div class="drawer-rail-heading">
        <strong class="drawer-title">更新与本地管理</strong>
        <span v-if="railMeta" class="drawer-rail-meta">{{ railMeta }}</span>
      </div>
      <div
        v-for="group in railGroups"
        :key="group.label"
        class="drawer-rail-group"
        role="tablist"
        aria-orientation="vertical"
        :aria-label="group.label"
      >
        <span class="drawer-rail-group-label" aria-hidden="true">{{ group.label }}</span>
        <button
          v-for="section in group.sections"
          :key="section.id"
          type="button"
          class="drawer-rail-item"
          :class="{ selected: currentSection === section.id }"
          role="tab"
          :aria-selected="currentSection === section.id"
          :aria-controls="panelId(section.id)"
          @click="pick(section)"
        >
          <AppIcon :name="section.icon" />
          <span class="drawer-rail-label">{{ section.label }}</span>
          <span v-if="section.badge" class="drawer-rail-badge" :class="{ alert: section.alert }">
            {{ section.badge }}
          </span>
        </button>
      </div>
      <span class="drawer-rail-spacer"></span>
      <button type="button" class="drawer-rail-folder" @click="openGameDirectory">
        <AppIcon name="folder" />打开游戏目录
      </button>
    </nav>

    <div class="drawer-main">
      <div class="drawer-header">
        <div class="drawer-header-copy">
          <h2 class="drawer-page-title">{{ header.title }}</h2>
          <p class="drawer-page-description">{{ header.description }}</p>
        </div>
        <button
          v-if="header.action"
          type="button"
          class="drawer-page-action"
          @click="header.action.run"
        >
          {{ header.action.label }}
        </button>
        <button
          type="button"
          class="drawer-icon-button drawer-expand-button"
          :title="store.state.drawerExpanded ? '恢复侧栏' : '铺满内容区'"
          :aria-label="store.state.drawerExpanded ? '恢复侧栏' : '铺满内容区'"
          @click="expand"
        >
          <AppIcon :name="store.state.drawerExpanded ? 'collapse' : 'expand'" />
        </button>
        <button type="button" class="drawer-icon-button" title="收起详情" aria-label="收起详情" @click="store.hideDrawer">
          <AppIcon name="close" />
        </button>
      </div>

      <div :id="panelId(currentSection)" class="drawer-content" role="tabpanel">
        <div v-if="currentSection === 'UPDATE'" class="update-details-page">
          <template v-if="result != null">
            <div class="update-hero">
              <div class="update-hero-version">
                <span class="update-hero-eyebrow">THIS UPDATE</span>
                <span class="update-detail-version">{{ result.displayVersion }}</span>
              </div>
              <div v-if="operationCounts.length > 0" class="update-detail-counts">
                <span
                  v-for="count in operationCounts"
                  :key="count.label"
                  class="update-count"
                  :class="count.operationClass"
                >{{ count.label }}<b>{{ count.count }}</b></span>
              </div>
            </div>
            <section class="update-section update-changelog-card">
              <h3 class="update-section-title">服主写的更新说明</h3>
              <ul v-if="changelogTagged" class="update-changelog-list">
                <li v-for="(line, index) in changelogLines" :key="index">
                  <span class="update-change-tag" :class="line.tone ? 'change-' + line.tone : 'change-plain'">{{ line.tag || "·" }}</span>
                  <span>{{ line.text }}</span>
                </li>
              </ul>
              <div v-else class="update-detail-changelog">{{ updateChangelog }}</div>
            </section>
            <section class="update-section">
              <h3 class="update-section-title">本地文件的变化</h3>
              <div v-if="updateRows.length > 0" class="update-detail-list">
                <div v-for="row in updateRows" :key="row.operation + row.path" class="update-detail-row">
                  <span class="update-operation" :class="row.operationClass">{{ row.operation }}</span>
                  <span class="update-detail-labels">
                    <span class="update-detail-path" :title="row.path">{{ row.path }}</span>
                    <span v-if="row.note" class="update-detail-note">{{ row.note }}</span>
                  </span>
                </div>
              </div>
              <div v-else class="drawer-empty">本次没有修改本地文件</div>
            </section>
          </template>
          <div v-else class="drawer-empty">尚未完成更新，完成后这里会列出本次修改的全部文件。</div>
        </div>

        <div v-else-if="currentSection === 'HISTORY'" class="history-scroll">
          <div v-if="historyReleases.length === 0" class="drawer-empty">
            还没有可显示的发布记录
          </div>
          <ol v-else class="history-timeline">
            <li
              v-for="(release, index) in historyReleases"
              :key="release.releaseId"
              class="history-entry"
              :class="{ current: index === 0 }"
            >
              <span class="history-dot" aria-hidden="true"></span>
              <div class="history-body">
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
            </li>
          </ol>
        </div>

        <div v-else-if="currentSection === 'LOGS'" ref="logsElement" class="log-list" @scroll="onLogScroll">
          <div v-if="store.state.logs.length === 0" class="drawer-empty">
            还没有运行记录
          </div>
          <template v-else>
            <section v-for="group in logGroups" :key="group.key" class="log-day">
              <div class="log-day-heading log-overview">
                <span class="log-day-label"><AppIcon name="document" />{{ group.label }}</span>
                <span class="log-day-rule" aria-hidden="true"></span>
                <span>共 {{ group.entries.length }} 条</span>
                <span v-if="levelCount(group.entries, 'WARN') > 0" class="log-count-warn">提醒 {{ levelCount(group.entries, 'WARN') }}</span>
                <span v-if="levelCount(group.entries, 'ERROR') > 0" class="log-count-error">错误 {{ levelCount(group.entries, 'ERROR') }}</span>
              </div>
              <template v-for="entry in group.entries" :key="entry.id">
                <div v-if="entry.level === 'START'" class="log-session-line">
                  <time :title="entry.time">{{ entry.time.slice(0, 8) }}</time>
                  <span class="log-session-rule short" aria-hidden="true"></span>
                  <strong>{{ entry.message }}</strong>
                  <span class="log-session-rule" aria-hidden="true"></span>
                </div>
                <article v-else class="log-entry" :class="'level-' + entry.level.toLowerCase()">
                  <time class="log-time" :title="entry.time">{{ entry.time.slice(0, 8) }}</time>
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

        <OptionalGroups v-else-if="currentSection === 'OPTIONS'" />

        <LocalFileTree v-else-if="currentSection === 'FILES'" />

        <div v-else-if="currentSection === 'MODS'" class="mod-page">
          <div class="file-tools">
            <label class="search-field">
              <AppIcon name="search" />
              <input v-model="modSearch" class="mod-search" placeholder="搜索模组名称或文件名" type="search" aria-label="搜索模组" />
            </label>
            <span class="player-mod-count">{{ modCountText }}</span>
          </div>
          <div v-if="visibleMods.length === 0" class="drawer-empty">{{ modEmptyText }}</div>
          <div v-else class="mod-list">
            <template v-for="(entry, index) in visibleMods" :key="entry.key">
              <ModRow :entry="entry" />
              <div v-if="index + 1 < visibleMods.length" class="drawer-divider"></div>
            </template>
          </div>
        </div>

        <BackupList v-else-if="currentSection === 'BACKUPS'" />

        <div v-else-if="currentSection === 'PLAYER_MODS'" class="player-mod-page">
          <div class="file-tools">
            <label class="search-field">
              <AppIcon name="search" />
              <input
                v-model="playerModSearch"
                class="mod-search"
                placeholder="搜索自选模组"
                type="search"
                aria-label="搜索自选模组"
              />
            </label>
            <span class="player-mod-count">{{ playerModCountText }}</span>
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
  </aside>
</template>
