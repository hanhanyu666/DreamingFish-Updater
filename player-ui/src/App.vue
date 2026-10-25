<script setup lang="ts">
import {acceptedPreviewMessage} from "./lib/previewBoundary";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import AppIcon from "./components/AppIcon.vue";
import ConfirmDialog from "./components/ConfirmDialog.vue";
import ContentPages from "./components/ContentPages.vue";
import DetailsDrawer from "./components/DetailsDrawer.vue";
import HomeBackdrop from "./components/HomeBackdrop.vue";
import HomeHero from "./components/HomeHero.vue";
import HomeRail from "./components/HomeRail.vue";
import TitleBar from "./components/TitleBar.vue";
import { getBridge } from "./lib/bridge";
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
const startupCompleted = ref(false);
const entranceStarted = ref(false);
const windowShown = ref(false);
const adminPreview = new URLSearchParams(window.location.search).get("adminPreview") === "1";
let countdownTimer: number | null = null;
let windowShowPromise: Promise<boolean> | null = null;

const homeVisible = computed(() => store.state.page === "HOME");
const backgroundUrl = computed(
  () => store.state.backgroundUrl ?? `${import.meta.env.BASE_URL}images/hero-default.webp`,
);
const countdownProgress = computed(() => {
  const remaining = store.state.countdownRemaining;
  const total = store.state.countdownTotal;
  if (remaining == null || total == null || total <= 0) return null;
  return Math.max(0, Math.min(1, remaining / total));
});

function onPreviewMessage(event: MessageEvent): void {
  if (!acceptedPreviewMessage(event,window.location.origin,window.parent,window,adminPreview)) return;
  applyAdminPreview(event.data);
}

onMounted(() => {
  bridge.onMessage(handleSidecarMessage);
  bridge.window.onMaximizedChange((maximized) => setMaximized(maximized));
  void bridge.window.isMaximized().then((maximized) => setMaximized(maximized));
  void store.loadNews();

  if (!bridge.isTauri) startPreview();
  if (adminPreview) {
    window.addEventListener("message", onPreviewMessage);
    window.parent.postMessage({ type: "dfs-player-preview-ready" }, window.location.origin);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("message", onPreviewMessage);
  if (countdownTimer != null) window.clearInterval(countdownTimer);
  store.disableStartupMusic();
});

function showUpdaterWindow(): Promise<boolean> {
  if (windowShown.value) return Promise.resolve(true);
  if (windowShowPromise != null) return windowShowPromise;
  windowShowPromise = (async () => {
    entranceStarted.value = true;
    await nextTick();
    try {
      await bridge.window.show();
      windowShown.value = true;
      return true;
    } catch {
      windowShowPromise = null;
      return false;
    }
  })();
  return windowShowPromise;
}

async function completeStartup(): Promise<void> {
  if (startupCompleted.value || !(await showUpdaterWindow())) return;
  if (startupCompleted.value) return;
  startupCompleted.value = true;
  await store.enableStartupMusic();
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
    if (ready) void completeStartup();
  },
  { immediate: true },
);

watch(
  () => store.state.progress,
  (progress) => {
    if (progress != null) void completeStartup();
  },
  { immediate: true },
);

watch(
  () => store.state.error,
  (error) => {
    if (error != null) void showUpdaterWindow();
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

</script>

<template>
  <div
    ref="root"
    class="app-root"
    :class="{ maximized: store.state.maximized, entrance: entranceStarted }"
  >
    <img class="background-image" :src="backgroundUrl" alt="" draggable="false" />
    <HomeBackdrop />
    <div class="image-shade"></div>
    <!-- Content pages sit on a blurred, darkened copy of the background, as in the design. -->
    <div class="content-backdrop" :class="{ visible: !homeVisible }" aria-hidden="true">
      <img :src="backgroundUrl" alt="" draggable="false" />
    </div>
    <div class="canvas">
      <HomeHero
        v-if="homeVisible"
        class="reveal"
        style="--reveal-delay: 130ms; --from-x: -22px; --from-y: 6px"
      />
      <HomeRail
        v-if="homeVisible"
        class="reveal"
        style="--reveal-delay: 200ms; --from-x: 18px; --from-y: 10px"
      />

      <ContentPages />

      <!-- The capsule carries the countdown at home and the title-bar pill elsewhere; only the drawer hides both. -->
      <div
        v-if="store.state.launchNotice != null && homeVisible && store.state.drawerOpen"
        class="launch-notice-layer"
        :class="{
          'drawer-open': store.state.drawerOpen,
          'drawer-expanded': store.state.drawerOpen && store.state.drawerExpanded,
        }"
      >
        <div class="launch-notice">
          <span class="launch-notice-glyph"><AppIcon name="check" /></span>
          <span class="launch-notice-text">{{ store.state.launchNotice }}</span>
          <button
            v-if="store.state.countdownRemaining != null"
            type="button"
            class="launch-notice-action"
            @click="store.keepWindowOpen()"
          >
            保持打开
          </button>
          <span
            v-if="countdownProgress != null"
            class="launch-notice-timer"
            :style="{ transform: `scaleX(${countdownProgress})` }"
            aria-hidden="true"
          ></span>
        </div>
      </div>

      <div v-if="store.state.drawerOpen" class="drawer-scrim" aria-hidden="true" @click="store.hideDrawer()"></div>
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
