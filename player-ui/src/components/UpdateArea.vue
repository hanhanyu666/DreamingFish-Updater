<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { changelogLines } from "../lib/changelog";
import { formatHistoryTime, formatUpdateFileDetails } from "../lib/format";
import { entryManaged } from "../lib/fileTree";
import type { IconName } from "../lib/icons";
import { usePlayerStore, type DrawerMode } from "../stores/player";
import AppIcon from "./AppIcon.vue";
import MoonFish, { type FishMood } from "./MoonFish.vue";
import { updateStatusOf, type UpdateStatus } from "../lib/updateStatus";

const MOODS: Record<UpdateStatus, FishMood> = {
  working: "swim",
  done: "happy",
  error: "worried",
  offline: "sleep",
};
const MARKS: Record<Exclude<UpdateStatus, "working">, IconName> = {
  done: "check",
  error: "alert",
  offline: "offline",
};
const QUICK_LINKS: ReadonlyArray<{ mode: DrawerMode; label: string; icon: IconName }> = [
  { mode: "HISTORY", label: "更新记录", icon: "history" },
  { mode: "LOGS", label: "运行记录", icon: "logs" },
  { mode: "FILES", label: "本地文件", icon: "files" },
];
/** The card lists this many changelog lines; the rest wait on the details page. */
const INFO_LINES = 3;
const PET_MS = 1300;
/** Circumference of the keep-open ring, r = 8. */
const RING = 2 * Math.PI * 8;

const store = usePlayerStore();

const result = computed(() => store.state.result);

const status = computed<UpdateStatus>(() => updateStatusOf(store.state));
const mark = computed(() => (status.value === "working" ? null : MARKS[status.value]));

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
    { label: "安装", count: value.installedPaths.length },
    { label: "删除", count: value.deletedPaths.length },
    { label: "备份", count: value.archivedFiles.length },
    { label: "恢复默认", count: (value.resetPaths ?? []).length },
    { label: "放弃管理", count: value.releasedPaths.length },
    { label: "本地停用", count: locallyDisabledMods.value },
    { label: "本地不管理", count: locallyExcludedFiles.value },
  ].filter((stat) => stat.count > 0);
});

/** Every file the update touched, for the tooltip on the link to the full list. */
const summaryTitle = computed(() => {
  if (result.value == null) return "";
  const changelog = result.value.changelog?.trim() ?? "";
  const counts = releaseStats.value.length === 0
    ? "本次无需修改本地文件"
    : releaseStats.value.map((stat) => stat.label + " " + stat.count).join(" · ");
  return (
    "版本 " + result.value.displayVersion + " · " + counts
    + (changelog.length > 0 ? "\n" + changelog.split("\n")[0] : "")
    + "\n\n"
    + formatUpdateFileDetails(
      result.value.installedPaths,
      result.value.deletedPaths,
      result.value.archivedFiles,
      result.value.releasedPaths,
    )
  );
});

const percentText = computed(() =>
  status.value === "working" && store.state.percent.endsWith("%") ? store.state.percent : "",
);
const bytesVisible = computed(() =>
  status.value === "working" && (store.state.progress?.totalBytes ?? 0) > 0,
);
/** One line under the lane: the download size while downloading, otherwise what is happening. */
const detailText = computed(() => {
  if (status.value === "error") return store.state.currentPathText;
  if (bytesVisible.value) return store.state.byteSummary;
  const parts = [store.state.currentPathText];
  if (store.state.launchNotice != null && store.state.countdownRemaining == null) parts.push("窗口保持打开");
  return parts.filter((part) => part.trim().length > 0 && part !== "-- / --").join(" · ");
});

/** How far the fish has swum in this run; stages without byte counts keep it where downloading left it. */
const swum = ref(0);
watch(
  () => [store.state.progress, store.state.working] as const,
  ([event, working], previous) => {
    if (working && previous?.[1] !== true) swum.value = 0;
    if (event != null && event.totalBytes > 0) swum.value = Math.max(swum.value, event.fraction);
  },
  { immediate: true },
);
const laneProgress = computed(() =>
  status.value === "done" || status.value === "offline" ? 1 : swum.value,
);
/** Checking has no size yet, so the fish looks around near the start instead of claiming progress. */
const laneWaiting = computed(() =>
  status.value === "working" && swum.value === 0 && (store.state.progress?.totalBytes ?? 0) <= 0,
);
const laneStyle = computed(() => ({ "--lane-progress": String(laneProgress.value) }));

const petCount = ref(0);
const petted = ref(false);
let petTimer: number | null = null;
const mood = computed<FishMood>(() => (petted.value ? "wow" : MOODS[status.value]));

function pet(): void {
  petCount.value += 1;
  petted.value = true;
  if (petTimer != null) window.clearTimeout(petTimer);
  petTimer = window.setTimeout(() => {
    petted.value = false;
    petTimer = null;
  }, PET_MS);
}

onBeforeUnmount(() => {
  if (petTimer != null) window.clearTimeout(petTimer);
});

const countdown = computed(() => {
  const remaining = store.state.countdownRemaining;
  const total = store.state.countdownTotal;
  if (remaining == null || remaining <= 0) return null;
  const left = total != null && total > 0 ? Math.max(0, Math.min(1, remaining / total)) : 1;
  return { remaining, offset: RING * (1 - left) };
});

const noticeText = computed(() => (store.state.unmanaged?.text ?? "").replace(/\s*›\s*$/, ""));
/**
 * What the update kept safe for the player, in a few words; it opens where those files are.
 * Each part keeps its words together, so a narrow card wraps only between parts.
 */
const keptLine = computed(() => {
  if (status.value === "error" || status.value === "working" || result.value == null) return "";
  const parts: string[] = [];
  const archived = result.value.archivedFiles.length;
  const kept = (result.value.keptModifiedPaths ?? []).length;
  const playerMods = store.state.unmanaged?.mods.length ?? 0;
  if (archived > 0) parts.push("备份\u00a0" + archived + "\u00a0个文件");
  if (kept > 0) parts.push("保留\u00a0" + kept + "\u00a0个配置");
  if (playerMods > 0) parts.push(playerMods + "\u00a0个自选模组");
  if (parts.length > 0) return parts.join(" · ");
  return noticeText.value;
});
const keptTitle = computed(() =>
  [noticeText.value, ...(store.state.unmanaged?.contextLines ?? [])]
    .filter((line) => line.length > 0)
    .join("\n"),
);

/**
 * What this release is, shown in the card under the progress: the owner's first changelog lines
 * and what happened to local files, each count leading to where those files are listed.
 */
const info = computed(() => {
  const value = result.value;
  if (value == null || status.value === "error" || status.value === "working") return null;
  const lines = changelogLines(value.changelog);
  const time = formatHistoryTime(value.createdAt);
  return {
    heading: value.outcome === "UPDATED" ? "本次更新" : value.outcome === "OFFLINE_ALLOWED" ? "离线版本" : "当前版本",
    meta: [value.displayVersion, time.length > 0 ? time.replace(/\s+/, " ") + " 发布" : ""].filter(Boolean).join(" · "),
    lines: lines.slice(0, INFO_LINES),
    tagged: lines.some((line) => line.tag.length > 0),
    more: lines.length > INFO_LINES,
  };
});

const infoStats = computed(() => {
  const value = result.value;
  if (value == null) return [];
  const toUpdate = () => store.openDrawer("UPDATE");
  return [
    { label: "安装", count: value.installedPaths.length + (value.resetPaths ?? []).length, tone: "install", open: toUpdate },
    { label: "删除", count: value.deletedPaths.length, tone: "delete", open: toUpdate },
    { label: "备份", count: value.archivedFiles.length, tone: "archive", open: () => store.openDrawer("BACKUPS") },
    { label: "保留", count: (value.keptModifiedPaths ?? []).length, tone: "kept", open: toUpdate },
    { label: "放弃管理", count: value.releasedPaths.length, tone: "release", open: toUpdate },
  ].filter((stat) => stat.count > 0);
});

/** The info opens by itself when there is something to read; a player's own choice wins until the state changes. */
const chosenOpen = ref<boolean | null>(null);
watch(status, () => {
  chosenOpen.value = null;
});
const hasNews = computed(() =>
  result.value != null
    && (result.value.outcome === "UPDATED" || store.state.unmanaged != null
      || result.value.archivedFiles.length > 0),
);
const open = computed(() => info.value != null && (chosenOpen.value ?? hasNews.value));

function toggleOpen(): void {
  chosenOpen.value = !open.value;
}

function linkActive(mode: DrawerMode): boolean {
  return store.state.drawerOpen && store.state.drawerMode === mode;
}

function openLogs(): void {
  store.toggleDrawer("LOGS");
}

function openKept(): void {
  if ((result.value?.archivedFiles.length ?? 0) > 0) store.openDrawer("BACKUPS");
  else if ((store.state.unmanaged?.mods.length ?? 0) > 0) store.openPlayerModPage();
  else store.openDrawer("UPDATE");
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
</script>

<template>
  <section
    class="update-area"
    :class="'update-' + status"
    aria-label="更新状态"
    :title="status === 'working' ? store.state.currentPathText : undefined"
  >
    <div class="update-head">
      <span class="update-mark" aria-hidden="true"><AppIcon v-if="mark" :name="mark" /></span>
      <span class="update-title" role="status">{{ store.state.stageTitle }}</span>
      <span v-if="percentText" class="update-percent">{{ percentText }}</span>
      <button
        v-else-if="status === 'error'"
        type="button"
        class="update-more"
        title="查看运行记录"
        @click="openLogs"
      >
        运行记录<AppIcon name="chevron" />
      </button>
      <button
        v-else-if="info != null"
        type="button"
        class="update-toggle"
        :aria-expanded="open"
        :aria-label="open ? '收起更新内容' : '展开更新内容'"
        :title="open ? '收起更新内容' : '展开更新内容'"
        @click="toggleOpen"
      >
        <AppIcon name="chevron" />
      </button>
    </div>

    <div class="swim-lane" :class="{ 'swim-lane-waiting': laneWaiting }" :style="laneStyle">
      <span
        class="swim-lane-track"
        role="progressbar"
        aria-label="更新进度"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuenow="laneWaiting ? undefined : Math.round(laneProgress * 100)"
      ></span>
      <span class="swim-lane-water" aria-hidden="true"></span>
      <svg class="swim-lane-goal" viewBox="0 0 20 20" aria-hidden="true" focusable="false">
        <path d="M10 0c1 6 4 9 10 10-6 1-9 4-10 10-1-6-4-9-10-10 6-1 9-4 10-10Z" />
      </svg>
      <button type="button" class="swim-lane-fish" title="摸鱼" aria-label="摸摸小鱼" @click="pet">
        <MoonFish :key="petCount" :mood="mood" :size="28" />
      </button>
    </div>

    <div class="update-foot">
      <p
        v-if="detailText"
        class="update-detail"
        :class="{ 'update-detail-error': status === 'error' }"
        :title="status === 'error' ? undefined : detailText"
      >{{ detailText }}</p>
      <button
        v-if="countdown != null"
        type="button"
        class="update-keep"
        :aria-label="'保持窗口打开，' + countdown.remaining + ' 秒后会自动关闭'"
        @click="store.keepWindowOpen()"
      >
        保持打开
        <span class="update-keep-ring" aria-hidden="true">
          <svg viewBox="0 0 20 20">
            <circle class="ring-track" cx="10" cy="10" r="8" />
            <circle
              class="ring-left"
              cx="10"
              cy="10"
              r="8"
              :stroke-dasharray="RING"
              :stroke-dashoffset="countdown.offset"
            />
          </svg>
          <span class="update-keep-seconds">{{ countdown.remaining }}</span>
        </span>
      </button>
    </div>
    <p v-if="status === 'error'" class="update-error-status">{{ store.state.byteSummary }}</p>

    <div v-if="open && info != null" class="update-info">
      <div class="update-info-head">
        <span class="update-info-heading">{{ info.heading }}</span>
        <span class="update-info-meta">{{ info.meta }}</span>
      </div>
      <ul class="update-info-lines" :class="{ plain: !info.tagged }">
        <li v-for="(line, index) in info.lines" :key="index">
          <span v-if="info.tagged" class="update-change-tag" :class="line.tone ? 'change-' + line.tone : 'change-plain'">{{ line.tag || "·" }}</span>
          <span class="update-info-text" :title="line.text">{{ line.text }}</span>
        </li>
      </ul>
      <div class="update-info-stats">
        <button
          v-for="stat in infoStats"
          :key="stat.label"
          type="button"
          class="update-stat"
          :class="'update-operation-' + stat.tone"
          @click="stat.open()"
        ><b>{{ stat.count }}</b>{{ stat.label }}</button>
        <span v-if="infoStats.length === 0" class="update-stat-none">本地文件不需要改动</span>
      </div>
      <div class="update-info-foot">
        <button type="button" class="update-info-all" :title="summaryTitle" @click="store.openDrawer('UPDATE')">
          {{ info.more ? "查看完整说明与改动" : "查看全部改动" }}<AppIcon name="chevron" />
        </button>
        <nav class="quick-links" aria-label="更新详情">
          <button
            v-for="link in QUICK_LINKS"
            :key="link.mode"
            type="button"
            class="link-button"
            :class="{ active: linkActive(link.mode) }"
            :aria-pressed="linkActive(link.mode)"
            :aria-label="link.label"
            :title="link.label"
            @click="store.toggleDrawer(link.mode)"
          >
            <AppIcon :name="link.icon" />
          </button>
        </nav>
      </div>
    </div>

    <button
      v-else-if="keptLine"
      type="button"
      class="update-note"
      :title="keptTitle || keptLine"
      @click="openKept"
    >
      <AppIcon name="archive" />
      <span class="update-note-text">{{ keptLine }}</span>
      <AppIcon name="chevron" />
    </button>

    <div v-if="store.state.error != null" class="update-actions">
      <button type="button" class="update-action" @click="openDirectory">
        <AppIcon name="folder" />打开目录
      </button>
      <button
        v-if="store.state.error.allowContinue"
        type="button"
        class="update-action"
        @click="continueLaunch"
      >
        <AppIcon name="play" />仍然启动
      </button>
      <button type="button" class="update-action primary" :disabled="store.state.working" @click="retry">
        <AppIcon name="retry" />重试
      </button>
    </div>
  </section>
</template>
