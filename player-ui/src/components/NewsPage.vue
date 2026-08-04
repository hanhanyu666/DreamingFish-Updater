<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { getBridge } from "../lib/bridge";
import { newsDateLabel, selectRequestedArticle, type NewsArticle } from "../lib/news";
import { usePlayerStore } from "../stores/player";
import MarkdownBody from "./MarkdownBody.vue";

const store = usePlayerStore();
const bridge = getBridge();
const articles = computed(() => store.state.newsArticles);
const loadError = computed(() => store.state.newsLoadError);
const showingArticle = ref(false);
const selected = ref<NewsArticle | null>(null);

watch(
  () => store.state.newsRequest.seq,
  () => {
    const request = store.state.newsRequest;
    if (request.kind === "list") {
      showList();
    } else {
      const article = selectRequestedArticle(articles.value, request.articleId);
      if (article != null) showArticle(article);
    }
  },
  { immediate: true },
);

function showList(): void {
  showingArticle.value = false;
  selected.value = null;
}

function showArticle(article: NewsArticle): void {
  store.keepWindowOpen();
  showingArticle.value = true;
  selected.value = article;
}

function openArticle(article: NewsArticle): void {
  showArticle(article);
}

function onCardKeydown(event: KeyboardEvent, article: NewsArticle): void {
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    showArticle(article);
  }
}

function openExternal(uri: string): void {
  bridge.openExternal(uri);
}
</script>

<template>
  <div class="news-page-root">
    <template v-if="!showingArticle">
      <div class="content-page-alignment">
        <div class="content-page news-page">
          <div class="page-eyebrow">{{ store.state.branding.brandEnglishName }} NEWS</div>
          <div class="page-title">{{ store.state.branding.brandName }}新闻</div>
          <div class="page-lead">这里记录服务器动态、版本消息和想与玩家分享的内容。</div>
          <div class="page-divider"></div>
          <div class="news-card-list">
            <div v-if="articles.length === 0" class="news-empty">
              {{
                loadError == null || loadError.length === 0
                  ? "还没有发布新闻"
                  : "新闻暂时无法载入，不影响游戏更新与启动"
              }}
            </div>
            <div
              v-for="article in articles"
              :key="article.id"
              class="news-card"
              tabindex="0"
              @click="openArticle(article)"
              @keydown="onCardKeydown($event, article)"
            >
              <div v-if="article.cover" class="news-card-cover">
                <img class="news-cover-image" :src="article.cover" :alt="article.title" />
                <span v-if="false" class="news-cover-missing">图片无法显示</span>
              </div>
              <div class="news-card-text">
                <div class="news-card-date">NEWS · {{ newsDateLabel(article.publishedOn) }}</div>
                <div class="news-card-title">{{ article.title }}</div>
                <div class="news-card-summary">{{ article.summary }}</div>
                <button type="button" class="news-read-button" @click.stop="openArticle(article)">
                  阅读全文 ›
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
    <template v-else-if="selected != null">
      <div class="content-page-alignment">
        <div class="content-page news-page">
          <button type="button" class="news-back-button" @click="showList">‹ 返回新闻</button>
          <div v-if="selected.cover" class="news-article-cover">
            <img class="news-cover-image" :src="selected.cover" :alt="selected.title" />
          </div>
          <div class="page-eyebrow">
            {{ store.state.branding.brandEnglishName }} NEWS · {{ newsDateLabel(selected.publishedOn) }}
          </div>
          <div class="page-title">{{ selected.title }}</div>
          <div class="page-lead">{{ selected.summary }}</div>
          <div class="page-divider"></div>
          <MarkdownBody :markdown="selected.markdown" />
        </div>
      </div>
    </template>
  </div>
</template>
