<script setup lang="ts">
import { computed } from "vue";
import DEFAULT_MARK from "../assets/dreamingfish-mark.svg";
import { getBridge } from "../lib/bridge";
import { chrome, finishedHeading, updateStatusOf } from "../lib/updateStatus";
import { usePlayerStore, type Page } from "../stores/player";
import AppIcon from "./AppIcon.vue";
import MusicPlayer from "./MusicPlayer.vue";
import PixelFace from "./PixelFace.vue";

const store = usePlayerStore();
const bridge = getBridge();

/** The owner's uploaded mark once it has arrived; until then, and by default, the DreamingFish icon. */
const brandMark = computed(() => {
  const file = store.state.branding.brandLogoFile;
  return (file ? store.imageUrl({ imageFile: file }) : "") || DEFAULT_MARK;
});
const onHome = computed(() => store.state.page === "HOME");
const status = computed(() => updateStatusOf(store.state));

/** Away from home the capsule is out of sight, so a pill in the bar says where the update stands. */
const pill = computed(() => {
  const state = store.state;
  switch (status.value) {
    case "working":
      return { tone: "working", text: state.stageTitle + (state.percent.endsWith("%") ? " " + state.percent : "") };
    case "error":
      return { tone: "error", text: state.error?.title || state.stageTitle };
    case "offline":
      return { tone: "offline", text: state.stageTitle };
    default: {
      const version = state.result?.displayVersion;
      const ready = version ? version + " 已就绪" : finishedHeading(state.result);
      if (state.countdownRemaining != null) return { tone: "done", text: ready + " · " + state.countdownRemaining + " 秒后关闭" };
      if (state.launchNotice != null) return { tone: "done", text: ready + " · 窗口保持打开" };
      return { tone: "done", text: ready };
    }
  }
});

function go(page: Page): void {
  store.showPage(page);
}

/** The pill leads back to the capsule; following it also keeps the window from closing under the player. */
function openUpdate(): void {
  if (store.state.countdownRemaining != null) store.keepWindowOpen();
  store.showPage("HOME");
}

function toggleMaximize(): void {
  bridge.window.toggleMaximize();
}

function close(): void {
  store.requestClose();
}

function minimize(): void {
  bridge.window.minimize();
}
</script>

<template>
  <div
    class="title-bar reveal"
    :class="{ 'title-bar-solid': !onHome && chrome.contentScrolled }"
    style="--reveal-delay: 70ms; --from-y: -10px"
    data-tauri-drag-region
    @dblclick.self="toggleMaximize"
  >
    <div
      class="top-brand"
      :aria-label="`${store.state.branding.brandName} ${store.state.branding.brandEnglishName}`"
      data-tauri-drag-region
    >
      <span class="brand-tile" aria-hidden="true" data-tauri-drag-region>
        <img :src="brandMark" alt="" draggable="false" data-tauri-drag-region />
      </span>
      <span class="brand-names" data-tauri-drag-region>
        <span
          class="brand-chinese"
          :title="store.state.branding.brandName"
          data-tauri-drag-region
        >{{ store.state.branding.brandName }}</span>
        <span
          class="brand-english"
          :title="store.state.branding.brandEnglishName"
          data-tauri-drag-region
        >{{ store.state.branding.brandEnglishName }}</span>
      </span>
    </div>
    <div class="top-navigation" data-tauri-drag-region>
      <button
        v-for="entry in store.navigationPages()"
        :key="entry.page"
        type="button"
        class="top-nav-button"
        :class="{ selected: store.state.page === entry.page }"
        :aria-current="store.state.page === entry.page ? 'page' : undefined"
        @click="go(entry.page)"
      >
        {{ entry.label }}
      </button>
    </div>
    <div class="title-spacer" data-tauri-drag-region></div>
    <!-- Music stays reachable on every page; the player card shows at home, the update pill elsewhere. -->
    <MusicPlayer />
    <template v-if="onHome">
      <div class="title-player" :title="'当前玩家 ' + store.state.playerName" data-tauri-drag-region>
        <span class="title-player-avatar" aria-hidden="true" data-tauri-drag-region>
          <PixelFace :name="store.state.playerName" />
        </span>
        <span class="title-player-name" data-tauri-drag-region>{{ store.state.playerName }}</span>
      </div>
    </template>
    <button
      v-else
      type="button"
      class="title-status"
      :class="'title-status-' + pill.tone"
      :title="'回到首页查看更新：' + pill.text"
      @click="openUpdate"
    >
      <span class="title-status-mark" aria-hidden="true">
        <AppIcon :name="pill.tone === 'error' ? 'alert' : pill.tone === 'offline' ? 'offline' : pill.tone === 'working' ? 'retry' : 'check'" />
      </span>
      <span class="title-status-text">{{ pill.text }}</span>
    </button>
    <div class="window-buttons">
      <button type="button" class="window-button minimize-button" title="最小化" aria-label="最小化" @click="minimize">
        <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M1.5 6h9" /></svg>
      </button>
      <button
        type="button"
        class="window-button maximize-button"
        :title="store.state.maximized ? '还原窗口' : '最大化'"
        :aria-label="store.state.maximized ? '还原窗口' : '最大化'"
        @click="toggleMaximize"
      >
        <svg v-if="!store.state.maximized" viewBox="0 0 12 12" aria-hidden="true"><rect x="1.5" y="1.5" width="9" height="9" rx="1.5" /></svg>
        <svg v-else viewBox="0 0 12 12" aria-hidden="true"><rect x="1.5" y="3.5" width="7" height="7" rx="1.2" /><path d="M3.5 3.5V2.7c0-.7.5-1.2 1.2-1.2h4.6c.7 0 1.2.5 1.2 1.2v4.6c0 .7-.5 1.2-1.2 1.2h-.8" /></svg>
      </button>
      <button type="button" class="window-button close-button" title="关闭" aria-label="关闭" @click="close">
        <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M2 2l8 8M10 2l-8 8" /></svg>
      </button>
    </div>
  </div>
</template>
