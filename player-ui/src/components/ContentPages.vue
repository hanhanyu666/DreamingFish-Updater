<script setup lang="ts">
import { computed } from "vue";
import { usePlayerStore } from "../stores/player";
import { getBridge } from "../lib/bridge";
import NewsPage from "./NewsPage.vue";
import MarkdownBody from "./MarkdownBody.vue";

const store = usePlayerStore();
const bridge = getBridge();
const VERSION = "0.1.27";
const REPOSITORY_URL = "https://github.com/hanhanyu666/DreamingFish-Updater";
const currentPage = computed(() => {
  const id = store.state.page === "NEWS" ? "news"
    : store.state.page === "CUSTOM" ? "custom"
      : store.state.page.startsWith("CONTENT:")
        ? store.state.page.substring("CONTENT:".length) : null;
  if (id == null) return null;
  return store.state.contentPages.find((page) => page.id === id) ?? null;
});

function openRepository(): void {
  bridge.openExternal(REPOSITORY_URL);
}
</script>

<template>
  <div class="content-page-layer" :class="{ visible: store.state.page !== 'HOME' }">
    <div
      v-if="currentPage?.announcementPage"
      :key="'announcements-' + currentPage.id + '-' + store.state.newsRequest.seq"
      class="content-page-host page-reveal"
    >
      <NewsPage :page="currentPage" />
    </div>
    <div
      v-else-if="currentPage"
      :key="'content-' + currentPage.id"
      class="content-page-host page-reveal"
    >
      <div class="content-page-alignment">
        <div class="content-page">
          <div v-if="currentPage.eyebrow" class="page-eyebrow">{{ currentPage.eyebrow }}</div>
          <div class="page-title">{{ currentPage.title }}</div>
          <div v-if="currentPage.lead" class="page-lead">{{ currentPage.lead }}</div>
          <div class="page-divider"></div>
          <MarkdownBody :markdown="currentPage.markdown" />
        </div>
      </div>
    </div>
    <div
      v-else-if="store.state.page === 'ABOUT'"
      :key="'about'"
      class="content-page-host page-reveal"
    >
      <div class="content-page-alignment">
        <div class="content-page">
          <div class="page-eyebrow">DREAMINGFISH UPDATER</div>
          <div class="page-version">玩家端程序版本 {{ VERSION }}</div>
          <div class="page-title">关于更新器</div>
          <div class="page-lead">为 Minecraft 整合包准备的一套开源自动更新工具</div>
          <div class="page-divider"></div>
          <div class="page-copy">梦鱼更新器由管理端、玩家端和启动引导程序组成。服主在管理端发布整合包文件，玩家端会在游戏启动前检查变化并完成更新。</div>
          <div class="page-copy">这是一个公开在 GitHub 上的开源项目。你可以查看源代码、了解最新进展，也可以提交使用中遇到的问题。</div>
          <div class="about-actions">
            <button type="button" class="about-github-link" @click="openRepository">
              在 GitHub 查看项目 <span aria-hidden="true">↗</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
