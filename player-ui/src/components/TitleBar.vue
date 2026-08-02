<script setup lang="ts">
import { getBridge } from "../lib/bridge";
import { PAGE_LABELS, usePlayerStore, type Page } from "../stores/player";

const store = usePlayerStore();
const bridge = getBridge();

function go(page: Page): void {
  store.showPage(page);
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
    style="--reveal-delay: 70ms; --from-y: -10px"
    data-tauri-drag-region
    @dblclick.self="toggleMaximize"
  >
    <div
      class="top-brand"
      :aria-label="`${store.state.branding.brandName} ${store.state.branding.brandEnglishName}`"
      data-tauri-drag-region
    >
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
    </div>
    <div class="top-navigation" data-tauri-drag-region>
      <button
        v-for="(label, page) in PAGE_LABELS"
        :key="page"
        type="button"
        class="top-nav-button"
        :class="{ selected: store.state.page === page }"
        @click="go(page as Page)"
      >
        {{ label }}
      </button>
    </div>
    <div class="title-spacer" data-tauri-drag-region></div>
    <button type="button" class="window-button minimize-button" title="最小化" @click="minimize">
      <span class="window-glyph-line"></span>
    </button>
    <button
      type="button"
      class="window-button maximize-button"
      :title="store.state.maximized ? '还原窗口' : '最大化'"
      @click="toggleMaximize"
    >
      <span v-if="!store.state.maximized" class="window-glyph-box"></span>
      <span v-else class="window-glyph-restore">
        <span class="window-glyph-box"></span>
        <span class="window-glyph-box"></span>
      </span>
    </button>
    <button type="button" class="window-button close-button" title="关闭" @click="close">
      <span class="window-glyph-close">
        <span class="window-glyph-line"></span>
        <span class="window-glyph-line"></span>
      </span>
    </button>
  </div>
</template>
