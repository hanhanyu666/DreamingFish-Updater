<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import ConfirmDialog from "./components/ConfirmDialog.vue";
import ContentPages from "./components/ContentPages.vue";
import DetailsDrawer from "./components/DetailsDrawer.vue";
import MusicPlayer from "./components/MusicPlayer.vue";
import TitleBar from "./components/TitleBar.vue";
import UpdateArea from "./components/UpdateArea.vue";
import { getBridge } from "./lib/bridge";
import { newsDateLabel } from "./lib/news";
import {
  handleSidecarMessage,
  applyAdminPreview,
  setMaximized,
  startPreview,
  usePlayerStore,
} from "./stores/player";

const store = usePlayerStore();
const bridge = getBridge();
const root = ref<HTMLElement | null>(null);
const entrancePlayed = ref(false);
const windowHeight = ref(window.innerHeight);
const adminPreview = new URLSearchParams(window.location.search).get("adminPreview") === "1";
let countdownTimer: number | null = null;

const latestNews = computed(() => store.state.latestArticle);
const homeVisible = computed(() => store.state.page === "HOME");
const latestNewsVisible = computed(
  () => homeVisible.value && latestNews.value != null && windowHeight.value >= 640,
);
const backgroundUrl = computed(
  () => store.state.backgroundUrl ?? `${import.meta.env.BASE_URL}images/hero-dreamhaven.png`,
);

function onPreviewMessage(event: MessageEvent): void {
  if (!adminPreview || event.origin !== window.location.origin
      || event.data?.type !== "dfs-admin-preview") return;
  applyAdminPreview(event.data);
}

onMounted(() => {
  bridge.onMessage(handleSidecarMessage);
  bridge.window.onMaximizedChange((maximized) => setMaximized(maximized));
  void bridge.window.isMaximized().then((maximized) => setMaximized(maximized));
  window.addEventListener("resize", updateLatestNewsVisibility);
  void store.loadNews();

  if (!bridge.isTauri) startPreview();
  if (adminPreview) {
    window.addEventListener("message", onPreviewMessage);
    window.parent.postMessage({ type: "dfs-player-preview-ready" }, window.location.origin);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", updateLatestNewsVisibility);
  window.removeEventListener("message", onPreviewMessage);
  if (countdownTimer != null) window.clearInterval(countdownTimer);
  store.disableStartupMusic();
});

function updateLatestNewsVisibility(): void {
  windowHeight.value = window.innerHeight;
}

function openLatestNews(): void {
  store.openLatestNews();
  store.keepWindowOpen();
}

async function playEntrance(): Promise<void> {
  if (entrancePlayed.value) return;
  entrancePlayed.value = true;
  await nextTick();
  try {
    await bridge.window.show();
    await store.enableStartupMusic();
  } catch {
    // Never play audio if the native window could not be shown.
  }
}

function onResizeGripDown(event: PointerEvent): void {
  if (store.state.maximized) return;
  void import("@tauri-apps/api/window")
    .then(({ getCurrentWindow }) => getCurrentWindow().startResizeDragging("SouthEast"))
    .catch(() => undefined);
  event.preventDefault();
}

watch(
  () => store.state.ready,
  (ready) => {
    if (ready) void playEntrance();
  },
  { immediate: true },
);

watch(
  () => store.state.error,
  (error) => {
    if (error != null) void nextTick(() => bridge.window.show());
  },
);

watch(
  () => store.state.countdownRemaining,
  (remaining) => {
    if (countdownTimer != null) {
      window.clearInterval(countdownTimer);
      countdownTimer = null;
    }
    if (remaining != null && remaining > 0) {
      countdownTimer = window.setInterval(() => {
        if (store.state.countdownRemaining == null) return;
        store.countdownTick();
        if (store.state.countdownRemaining <= 0) {
          if (countdownTimer != null) window.clearInterval(countdownTimer);
          countdownTimer = null;
          store.sendCommand({ command: "quit" });
          bridge.window.close();
        }
      }, 1000);
    }
  },
);

function formatNewsDate(value: string): string {
  return newsDateLabel(value);
}
</script>

<template>
  <div
    ref="root"
    class="app-root"
    :class="{ maximized: store.state.maximized, entrance: entrancePlayed }"
  >
    <img class="background-image" :src="backgroundUrl" alt="" draggable="false" />
    <img
      class="refracted-background"
      :src="backgroundUrl"
      alt=""
      draggable="false"
    />
    <div class="image-shade"></div>
    <div class="liquid-glass-wash"></div>
    <div class="canvas">
      <div
        class="identity-pane reveal"
        style="--reveal-delay: 130ms; --from-x: -22px; --from-y: 6px"
      >
        <div class="welcome-title">欢迎来到</div>
        <div class="product-name">{{ store.state.branding.productName }}</div>
        <div class="subtitle">{{ store.state.branding.subtitle }}</div>
      </div>

      <div
        class="player-identity reveal"
        style="--reveal-delay: 230ms; --from-x: -14px; --from-y: 10px"
      >
        <div class="player-accent"></div>
        <div class="player-labels">
          <div class="player-caption">当前玩家</div>
          <div class="player-name">{{ store.state.playerName }}</div>
        </div>
      </div>

      <div
        v-if="latestNewsVisible && latestNews != null"
        class="home-latest-news reveal"
        style="--reveal-delay: 165ms; --from-x: 18px; --from-y: 6px"
        tabindex="0"
        @click="openLatestNews"
        @keydown.enter="openLatestNews"
        @keydown.space.prevent="openLatestNews"
      >
        <div class="home-latest-news-meta">
          最新新闻 · {{ formatNewsDate(latestNews.publishedOn) }}
        </div>
        <div class="home-latest-news-title">{{ latestNews.title }}</div>
        <div class="home-latest-news-summary">{{ latestNews.summary }}</div>
        <div class="home-latest-news-action">查看全文  ›</div>
      </div>

      <UpdateArea
        v-if="homeVisible"
        class="reveal"
        style="--reveal-delay: 190ms; --from-x: 18px; --from-y: 14px"
      />
      <div
        class="updater-info reveal"
        style="--reveal-delay: 310ms; --from-y: 8px"
      >
        DreamingFish Updater {{ "0.1.32" }}
      </div>

      <ContentPages />

      <div
        v-if="store.state.launchNotice != null"
        class="launch-notice-layer"
      >
        <div class="launch-notice">
          <span class="launch-notice-glyph">✓</span>
          <span class="launch-notice-text">{{ store.state.launchNotice }}</span>
        </div>
      </div>

      <MusicPlayer />

      <DetailsDrawer v-if="store.state.drawerOpen" />
      <TitleBar />
      <div
        v-if="!store.state.maximized"
        class="resize-grip"
        @pointerdown="onResizeGripDown"
      ></div>
    </div>
    <div class="liquid-glass-rim"></div>
    <div class="liquid-glass-sweep"></div>
    <ConfirmDialog />
  </div>
</template>
