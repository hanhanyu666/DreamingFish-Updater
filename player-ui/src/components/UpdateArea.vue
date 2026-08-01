<script setup lang="ts">
import { computed, ref } from "vue";
import { getBridge } from "../lib/bridge";
import {
  ellipsize,
  formatBytes,
  formatUpdateFileDetails,
} from "../lib/format";
import { entryManaged } from "../lib/fileTree";
import { usePlayerStore } from "../stores/player";

const store = usePlayerStore();
const bridge = getBridge();
const tooltipVisible = ref(false);

const result = computed(() => store.state.result);
const summaryVersion = computed(() =>
  result.value == null ? "" : "版本 " + result.value.displayVersion,
);
const summaryChangelog = computed(() => {
  if (result.value == null) return "";
  const changelog =
    result.value.changelog == null || result.value.changelog.trim().length === 0
      ? "本次发布没有填写更新说明。"
      : result.value.changelog.trim();
  const firstLine = changelog.split("\n")[0] ?? "";
  return ellipsize(firstLine, 74);
});
const summaryCounts = computed(() => {
  if (result.value == null) return "";
  const counts: string[] = [];
  if (result.value.installedPaths.length > 0) {
    counts.push("安装 / 更新 " + result.value.installedPaths.length + " 项");
  }
  if (result.value.deletedPaths.length > 0) {
    counts.push("删除 " + result.value.deletedPaths.length + " 项");
  }
  if (result.value.archivedFiles.length > 0) {
    counts.push("备份 " + result.value.archivedFiles.length + " 项");
  }
  if (result.value.releasedPaths.length > 0) {
    counts.push("保留并放弃管理 " + result.value.releasedPaths.length + " 项");
  }
  if (locallyDisabledMods.value > 0) counts.push("本地停用 " + locallyDisabledMods.value + " 项");
  if (locallyExcludedFiles.value > 0) {
    counts.push("本地不管理 " + locallyExcludedFiles.value + " 项");
  }
  return counts.length === 0 ? "本次无需修改本地文件" : counts.join("  ·  ");
});

const locallyDisabledMods = computed(
  () => store.state.mods.filter((entry) => entry.disabled && !entry.forced).length,
);
const locallyExcludedFiles = computed(
  () =>
    store.state.files.filter(
      (entry) =>
        !entry.directory && entry.present && !entryManaged(entry) && !entry.forced,
    ).length,
);

const tooltipText = computed(() => {
  if (result.value == null) return "";
  return (
    "版本 " +
    result.value.displayVersion +
    " · 文件变更\n\n" +
    formatUpdateFileDetails(
      result.value.installedPaths,
      result.value.deletedPaths,
      result.value.archivedFiles,
      result.value.releasedPaths,
    )
  );
});

const percentText = computed(() => store.state.percent);

const progressStyle = computed(() => {
  const event = store.state.progress;
  if (event == null || event.totalBytes <= 0) return {};
  return { width: Math.round(event.fraction * 100) + "%" };
});

function openSummaryDrawer(): void {
  tooltipVisible.value = false;
  store.toggleDrawer("UPDATE");
}

function retry(): void {
  store.sendCommand({ command: "retry" });
}

function continueLaunch(): void {
  store.sendCommand({ command: "continue-launch" });
}

function openDirectory(): void {
  store.sendCommand({ command: "open-directory" });
}

function openArchive(): void {
  store.sendCommand({ command: "open-archive" });
}
</script>

<template>
  <div class="update-area">
    <div
      v-if="result != null"
      class="update-summary"
      tabindex="0"
      @mouseenter="tooltipVisible = true"
      @mouseleave="tooltipVisible = false"
      @click="openSummaryDrawer"
      @keydown.enter="openSummaryDrawer"
      @keydown.space.prevent="openSummaryDrawer"
    >
      <div class="update-summary-version">{{ summaryVersion }}</div>
      <div class="update-summary-changelog">{{ summaryChangelog }}</div>
      <div class="update-summary-counts">{{ summaryCounts }}</div>
      <div v-if="tooltipVisible" class="update-file-tooltip">{{ tooltipText }}</div>
    </div>

    <div class="progress-region">
      <div class="progress-heading">
        <div class="stage-label">{{ store.state.stageTitle }}</div>
        <div class="progress-spacer"></div>
        <div class="percent-label">{{ percentText }}</div>
      </div>
      <div class="current-path" :class="{ 'error-detail': store.state.error != null }">
        {{ store.state.currentPathText }}
      </div>
      <div class="update-progress-track">
        <div
          class="update-progress-bar"
          :class="{ indeterminate: !store.state.progress || store.state.progress.totalBytes <= 0 }"
          :style="progressStyle"
        ></div>
      </div>
      <div class="progress-summary">
        <div class="byte-summary">{{ store.state.byteSummary }}</div>
        <div class="progress-spacer"></div>
        <button
          type="button"
          class="link-button"
          :class="{ collapsed: store.state.drawerOpen && store.state.drawerMode === 'HISTORY' }"
          @click="store.toggleDrawer('HISTORY')"
        >
          {{ store.state.drawerOpen && store.state.drawerMode === "HISTORY" ? "收起记录  ‹" : "更新记录  ›" }}
        </button>
        <button
          type="button"
          class="link-button"
          :class="{ collapsed: store.state.drawerOpen && store.state.drawerMode === 'LOGS' }"
          @click="store.toggleDrawer('LOGS')"
        >
          {{ store.state.drawerOpen && store.state.drawerMode === "LOGS" ? "收起记录  ‹" : "运行记录  ›" }}
        </button>
        <button
          type="button"
          class="link-button"
          :class="{ collapsed: store.state.drawerOpen && store.state.drawerMode === 'FILES' }"
          @click="store.toggleDrawer('FILES')"
        >
          {{ store.state.drawerOpen && store.state.drawerMode === "FILES" ? "收起管理  ‹" : "本地文件  ›" }}
        </button>
      </div>
      <div
        v-if="store.state.unmanaged != null"
        class="unmanaged-notice"
        :class="{ 'unmanaged-action': store.state.unmanaged.mods.length > 0 }"
        :title="store.state.unmanaged.contextLines.join('\n')"
        :role="store.state.unmanaged.mods.length > 0 ? 'button' : undefined"
        :tabindex="store.state.unmanaged.mods.length > 0 ? 0 : undefined"
        @click="store.openPlayerModPage()"
        @keydown.enter="store.openPlayerModPage()"
        @keydown.space.prevent="store.openPlayerModPage()"
      >
        {{ store.state.unmanaged.text }}
      </div>
      <button
        v-if="store.state.result != null && store.state.result.archivedFiles.length > 0"
        type="button"
        class="archive-button"
        @click="openArchive"
      >
        打开备份目录
      </button>
      <div v-if="store.state.error != null" class="action-row">
        <button type="button" class="secondary-button" @click="openDirectory">打开目录</button>
        <button type="button" class="secondary-button" :disabled="store.state.working" @click="retry">
          重试
        </button>
        <button
          v-if="store.state.error.allowContinue"
          type="button"
          class="primary-button"
          @click="continueLaunch"
        >
          仍然启动
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.progress-heading,
.progress-summary {
  display: flex;
  align-items: center;
}

.progress-spacer {
  flex: 1;
}
</style>
