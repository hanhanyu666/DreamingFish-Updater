<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ellipsize, formatUpdateFileDetails } from "../lib/format";
import { entryManaged } from "../lib/fileTree";
import type { IconName } from "../lib/icons";
import type { UpdateStage } from "../lib/types";
import { usePlayerStore, type DrawerMode } from "../stores/player";
import AppIcon from "./AppIcon.vue";

type UpdateStatus = "working" | "done" | "error" | "offline";
type StepState = "done" | "active" | "error" | "pending";

const STEPS: ReadonlyArray<{ key: string; label: string; stages: readonly UpdateStage[] }> = [
  { key: "check", label: "检查", stages: ["RECOVERING", "CHECKING", "SCANNING", "OFFLINE"] },
  { key: "download", label: "下载", stages: ["DOWNLOADING"] },
  { key: "install", label: "安装", stages: ["PREPARING", "INSTALLING", "VERIFYING"] },
  { key: "launch", label: "启动", stages: ["COMPLETE"] },
];

const QUICK_LINKS: ReadonlyArray<{ mode: DrawerMode; label: string; icon: IconName }> = [
  { mode: "HISTORY", label: "更新记录", icon: "history" },
  { mode: "LOGS", label: "运行记录", icon: "logs" },
  { mode: "FILES", label: "本地文件", icon: "files" },
];

const store = usePlayerStore();
const tooltipVisible = ref(false);
/** The last stage seen, so a failure can point at the step that stopped. */
const lastStage = ref<UpdateStage | null>(null);

watch(
  () => store.state.progress?.stage,
  (stage) => {
    if (stage != null) lastStage.value = stage;
  },
  { immediate: true },
);

const result = computed(() => store.state.result);

const status = computed<UpdateStatus>(() => {
  if (store.state.error != null) return "error";
  if (store.state.working || (!store.state.launchPermitted && result.value == null)) {
    return "working";
  }
  if (store.state.unverifiedOffline || store.state.localContentOverride
      || result.value?.outcome === "OFFLINE_ALLOWED") {
    return "offline";
  }
  return "done";
});

const releaseLabel = computed(() => {
  switch (result.value?.outcome) {
    case "UPDATED":
      return "本次更新";
    case "OFFLINE_ALLOWED":
      return "离线版本";
    default:
      return "当前版本";
  }
});
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

const releaseStats = computed(() => {
  const value = result.value;
  if (value == null) return [];
  return [
    { label: "安装 / 更新", count: value.installedPaths.length, tone: "install" },
    { label: "删除", count: value.deletedPaths.length, tone: "delete" },
    { label: "备份", count: value.archivedFiles.length, tone: "archive" },
    { label: "恢复默认", count: (value.resetPaths ?? []).length, tone: "install" },
    { label: "保留并放弃管理", count: value.releasedPaths.length, tone: "local" },
    { label: "本地停用", count: locallyDisabledMods.value, tone: "local" },
    { label: "本地不管理", count: locallyExcludedFiles.value, tone: "local" },
  ].filter((stat) => stat.count > 0);
});

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

const percentParts = computed(() => {
  const text = store.state.percent;
  return text.endsWith("%")
    ? { value: text.slice(0, -1), unit: "%" }
    : { value: text, unit: "" };
});
const percentVisible = computed(() =>
  (status.value === "working" || status.value === "done") && percentParts.value.unit !== "",
);

const activeStep = computed(() => {
  const stage = status.value === "working"
    ? store.state.progress?.stage ?? null
    : lastStage.value;
  if (stage == null) return 0;
  return Math.max(0, STEPS.findIndex((step) => step.stages.includes(stage)));
});
/** Steps explain where a running or stopped check is; a finished one says so in its title. */
const stepsVisible = computed(() => status.value === "working" || status.value === "error");
const steps = computed(() =>
  STEPS.map((step, index) => {
    let state: StepState = "pending";
    if (index < activeStep.value) state = "done";
    else if (index === activeStep.value) state = status.value === "error" ? "error" : "active";
    return { ...step, state };
  }),
);

/** A finished check fills the bar; a stopped one (error or offline start) leaves it still. */
const progressFinished = computed(() =>
  store.state.result != null && !store.state.working && store.state.error == null);
const progressStopped = computed(() =>
  !store.state.working && (store.state.error != null || store.state.launchPermitted));
const progressIndeterminate = computed(() => {
  if (progressFinished.value || progressStopped.value) return false;
  const event = store.state.progress;
  return event == null || event.totalBytes <= 0;
});

const progressStyle = computed(() => {
  if (progressFinished.value) return { width: "100%" };
  if (progressStopped.value) return { width: status.value === "done" ? "100%" : "0%" };
  const event = store.state.progress;
  if (event == null || event.totalBytes <= 0) return {};
  return { width: Math.round(event.fraction * 100) + "%" };
});

const noticeText = computed(() =>
  (store.state.unmanaged?.text ?? "").replace(/\s*›\s*$/, ""),
);
const noticeActionable = computed(() => (store.state.unmanaged?.mods.length ?? 0) > 0);

function linkActive(mode: DrawerMode): boolean {
  return store.state.drawerOpen && store.state.drawerMode === mode;
}

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

function openBackups(): void {
  store.openDrawer("BACKUPS");
}
</script>

<template>
  <div class="update-area" :class="'update-' + status">
    <div
      v-if="result != null && status !== 'error'"
      class="update-summary"
      role="button"
      tabindex="0"
      :aria-label="summaryVersion + '，查看本次更新的文件变更'"
      @mouseenter="tooltipVisible = true"
      @mouseleave="tooltipVisible = false"
      @click="openSummaryDrawer"
      @keydown.enter="openSummaryDrawer"
      @keydown.space.prevent="openSummaryDrawer"
    >
      <div class="update-summary-head">
        <span class="update-summary-label">{{ releaseLabel }}</span>
        <span class="update-summary-version">{{ summaryVersion }}</span>
        <span class="update-summary-more">文件变更<AppIcon name="chevron" /></span>
      </div>
      <div class="update-summary-changelog">{{ summaryChangelog }}</div>
      <div class="update-summary-counts">
        <span
          v-for="stat in releaseStats"
          :key="stat.label"
          class="update-stat"
          :class="'update-stat-' + stat.tone"
        >{{ stat.label }}<b>{{ stat.count }}</b></span>
        <span v-if="releaseStats.length === 0" class="update-stat-empty">本次无需修改本地文件</span>
      </div>
      <div v-if="tooltipVisible" class="update-file-tooltip">{{ tooltipText }}</div>
    </div>

    <div class="progress-region">
      <div class="progress-heading">
        <span class="status-orb" :class="'status-orb-' + status" aria-hidden="true">
          <AppIcon v-if="status === 'done'" name="check" />
          <AppIcon v-else-if="status === 'error'" name="alert" />
          <AppIcon v-else-if="status === 'offline'" name="offline" />
        </span>
        <div class="stage-label">{{ store.state.stageTitle }}</div>
        <div v-if="percentVisible" class="percent-label">
          {{ percentParts.value }}<small>{{ percentParts.unit }}</small>
        </div>
        <div class="current-path" :class="{ 'error-detail': store.state.error != null }">
          {{ store.state.currentPathText }}
        </div>
        <div v-if="status !== 'error'" class="byte-summary">{{ store.state.byteSummary }}</div>
        <div v-else class="error-status">{{ store.state.byteSummary }}</div>
      </div>

      <div class="update-progress-track">
        <div
          class="update-progress-bar"
          :class="{ indeterminate: progressIndeterminate }"
          :style="progressStyle"
        ></div>
      </div>

      <ol v-if="stepsVisible" class="stage-steps" aria-label="更新阶段">
        <li
          v-for="step in steps"
          :key="step.key"
          class="stage-step"
          :class="'stage-step-' + step.state"
          :aria-current="step.state === 'active' ? 'step' : undefined"
        >
          <span class="stage-step-dot"></span>
          <span class="stage-step-label">{{ step.label }}</span>
        </li>
      </ol>

      <div
        v-if="store.state.unmanaged != null && status !== 'error'"
        class="unmanaged-notice"
        :class="{ 'unmanaged-action': noticeActionable }"
        :title="store.state.unmanaged.contextLines.join('\n')"
        :role="noticeActionable ? 'button' : undefined"
        :tabindex="noticeActionable ? 0 : undefined"
        @click="store.openPlayerModPage()"
        @keydown.enter="store.openPlayerModPage()"
        @keydown.space.prevent="store.openPlayerModPage()"
      >
        <AppIcon name="info" class="notice-icon" />
        <span class="unmanaged-text">{{ noticeText }}</span>
        <AppIcon v-if="noticeActionable" name="chevron" class="notice-chevron" />
      </div>
      <button
        v-if="status !== 'error' && store.state.result != null
          && store.state.result.archivedFiles.length > 0"
        type="button"
        class="archive-button"
        @click="openBackups"
      ><AppIcon name="archive" />查看备份与恢复<AppIcon name="chevron" class="archive-chevron" /></button>

      <div v-if="store.state.error != null" class="action-row">
        <button type="button" class="secondary-button" @click="openDirectory">
          <AppIcon name="folder" />打开目录
        </button>
        <button type="button" class="secondary-button" :disabled="store.state.working" @click="retry">
          <AppIcon name="retry" />重试
        </button>
        <button
          v-if="store.state.error.allowContinue"
          type="button"
          class="primary-button"
          @click="continueLaunch"
        >
          <AppIcon name="play" />仍然启动
        </button>
      </div>

      <nav class="quick-links" aria-label="更新详情">
        <button
          v-for="link in QUICK_LINKS"
          :key="link.mode"
          type="button"
          class="link-button"
          :class="{ active: linkActive(link.mode) }"
          :aria-pressed="linkActive(link.mode)"
          :title="linkActive(link.mode) ? '收起' + link.label : '查看' + link.label"
          @click="store.toggleDrawer(link.mode)"
        >
          <AppIcon :name="link.icon" />{{ link.label }}
        </button>
      </nav>
    </div>
  </div>
</template>
