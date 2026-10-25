<script setup lang="ts">
import { computed, nextTick, ref, watch, type DeepReadonly } from "vue";
import { selectRequestedArticle, type NewsArticle } from "../lib/news";
import { backgroundImage, categoryTone, chineseDate, dateParts, readingTime } from "../lib/showcase";
import { usePlayerStore } from "../stores/player";
import type { PlayerContentPage } from "../lib/types";
import AppIcon from "./AppIcon.vue";
import MarkdownBody from "./MarkdownBody.vue";

interface Story extends NewsArticle {
  category: string;
  author: string;
  date: string;
  tone: string;
  read: string;
}

const store = usePlayerStore();
const props = defineProps<{ page: DeepReadonly<PlayerContentPage> }>();
const stories = computed<Story[]>(() => (props.page.articles ?? []).map((article) => ({
  id: article.id,
  title: article.title,
  summary: article.summary,
  publishedOn: article.publishedOn,
  cover: article.coverUrl,
  markdown: article.markdown,
  category: article.category?.trim() ?? "",
  author: article.author?.trim() ?? "",
  date: chineseDate(article.publishedOn),
  tone: categoryTone(article.category),
  read: readingTime(article.markdown),
})).sort((left, right) => right.publishedOn.localeCompare(left.publishedOn)));
const loadError = computed(() => store.state.newsLoadError);
const fallbackCover = computed(() => backgroundImage(store.state.backgroundUrl));
const filter = ref<string | null>(null);
const selected = ref<Story | null>(null);
const articleScroll = ref<HTMLElement | null>(null);

/** Categories in the order they first appear, newest first, each with its count. */
const filters = computed(() => {
  const counts = new Map<string, number>();
  for (const story of stories.value) if (story.category) counts.set(story.category, (counts.get(story.category) ?? 0) + 1);
  return [...counts].map(([name, count]) => ({ name, count }));
});
const visible = computed(() => stories.value.filter((story) => filter.value == null || story.category === filter.value));
const featured = computed(() => visible.value[0] ?? null);
const rest = computed(() => visible.value.slice(1));
const related = computed(() => stories.value.filter((story) => story.id !== selected.value?.id).slice(0, 2));
const outline = computed(() =>
  (selected.value?.markdown ?? "")
    .split(/\r?\n/)
    .map((line) => /^(##)\s+(.+?)\s*#*$/.exec(line.trim()))
    .filter((match): match is RegExpExecArray => match != null)
    .map((match, index) => ({ index, text: match[2], num: String(index + 1).padStart(2, "0") })),
);

function jumpToHeading(index: number): void {
  articleScroll.value?.querySelectorAll(".markdown-h2")[index]
    ?.scrollIntoView({ behavior: "smooth", block: "start" });
}

watch(
  () => store.state.newsRequest.seq,
  () => {
    const request = store.state.newsRequest;
    if (request.pageId != null && request.pageId !== props.page.id) return;
    if (request.kind === "list") {
      showList();
    } else {
      const article = selectRequestedArticle(stories.value, request.articleId);
      if (article != null) showArticle(article);
    }
  },
  { immediate: true },
);

function showList(): void {
  selected.value = null;
}

function showArticle(article: Story): void {
  store.keepWindowOpen();
  selected.value = article;
  void nextTick(() => articleScroll.value?.scrollTo({ top: 0 }));
}
</script>

<template>
  <div class="news-page-root">
    <main v-if="selected == null" class="showcase-screen news-list-view">
      <header class="screen-head">
        <div class="screen-head-copy">
          <span class="eyebrow">{{ page.eyebrow || "SERVER NEWS" }}</span>
          <h1 class="page-title screen-title">{{ page.title }}</h1>
        </div>
        <div v-if="filters.length > 0" class="pill-filters" role="group" aria-label="按分类筛选">
          <button type="button" class="pill-filter" :aria-pressed="filter == null" @click="filter = null">
            全部<span>{{ stories.length }}</span>
          </button>
          <button
            v-for="item in filters"
            :key="item.name"
            type="button"
            class="pill-filter"
            :aria-pressed="filter === item.name"
            @click="filter = item.name"
          >{{ item.name }}<span>{{ item.count }}</span></button>
        </div>
      </header>

      <div v-if="featured == null" class="news-empty">
        {{ loadError == null || loadError.length === 0 ? "还没有发布公告" : "公告暂时无法载入，不影响游戏更新与启动" }}
      </div>
      <div v-else class="news-board">
        <button type="button" class="news-feature" :aria-label="'阅读：' + featured.title" @click="showArticle(featured)">
          <span class="news-feature-cover">
            <img :src="featured.cover || fallbackCover" alt="" draggable="false" />
            <span class="news-feature-badges">
              <span class="news-badge-latest">最新</span>
              <span v-if="featured.category" class="news-badge-category">{{ featured.category }}</span>
            </span>
          </span>
          <span class="news-feature-text">
            <span class="news-meta">
              <span>{{ featured.date }}</span>
              <template v-if="featured.author"><span aria-hidden="true">·</span><span>{{ featured.author }}</span></template>
              <span aria-hidden="true">·</span><span>{{ featured.read }}</span>
            </span>
            <span class="news-feature-title">{{ featured.title }}</span>
            <span class="news-feature-summary">{{ featured.summary }}</span>
            <span class="news-feature-spacer"></span>
            <span class="news-read-more">阅读全文<AppIcon name="arrowRight" /></span>
          </span>
        </button>

        <section class="news-list" :aria-label="'更多' + page.navigationLabel">
          <button
            v-for="story in rest"
            :key="story.id"
            type="button"
            class="news-row"
            @click="showArticle(story)"
          >
            <span class="news-date-block">
              <span class="news-date-day">{{ dateParts(story.publishedOn).day }}</span>
              <span class="news-date-month">{{ dateParts(story.publishedOn).month }}</span>
            </span>
            <span class="news-row-text">
              <span v-if="story.category" class="category-chip" :class="'chip-' + story.tone">{{ story.category }}</span>
              <span class="news-row-title">{{ story.title }}</span>
              <span class="news-row-summary">{{ story.summary }}</span>
            </span>
            <AppIcon name="chevron" class="news-row-chevron" />
          </button>
          <div v-if="rest.length === 0" class="news-list-empty">
            {{ filter == null ? "只有左边这一篇" + page.navigationLabel : "这个分类只有左边这一篇" + page.navigationLabel }}
          </div>
        </section>
      </div>
    </main>

    <main v-else class="news-reader">
      <div ref="articleScroll" class="news-reader-scroll">
        <button type="button" class="ghost-button news-back-button" @click="showList">
          <AppIcon name="arrowLeft" />返回{{ page.navigationLabel }}列表
        </button>
        <article class="news-article">
          <div class="news-article-cover">
            <img :src="selected.cover || fallbackCover" alt="" draggable="false" />
          </div>
          <div class="news-article-head">
            <div class="news-meta">
              <span v-if="selected.category" class="category-chip" :class="'chip-' + selected.tone">{{ selected.category }}</span>
              <span>{{ selected.date }}</span>
              <template v-if="selected.author"><span aria-hidden="true">·</span><span>{{ selected.author }}</span></template>
              <span aria-hidden="true">·</span><span>{{ selected.read }}</span>
            </div>
            <h1 class="news-article-title">{{ selected.title }}</h1>
          </div>
          <p v-if="selected.summary" class="news-article-lead">{{ selected.summary }}</p>
          <MarkdownBody :markdown="selected.markdown" />
        </article>
      </div>
      <aside class="news-reader-aside">
        <nav v-if="outline.length > 0" class="news-outline" aria-label="本文目录">
          <span class="eyebrow muted">CONTENTS</span>
          <button
            v-for="heading in outline"
            :key="heading.index"
            type="button"
            class="news-outline-item"
            @click="jumpToHeading(heading.index)"
          ><span class="index-number">{{ heading.num }}</span>{{ heading.text }}</button>
        </nav>
        <section v-if="related.length > 0" class="news-related" aria-label="相关公告">
          <span class="news-aside-title">接着看</span>
          <button
            v-for="story in related"
            :key="story.id"
            type="button"
            class="news-related-item"
            @click="showArticle(story)"
          >
            <span class="news-related-thumb"><img :src="story.cover || fallbackCover" alt="" draggable="false" /></span>
            <span class="news-related-copy">
              <span class="news-related-meta" :class="'chip-text-' + story.tone">{{ [story.category, story.date].filter(Boolean).join(" · ") }}</span>
              <span class="news-related-title">{{ story.title }}</span>
            </span>
          </button>
        </section>
      </aside>
    </main>
  </div>
</template>
