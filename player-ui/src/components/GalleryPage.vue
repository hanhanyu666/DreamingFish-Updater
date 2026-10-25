<script setup lang="ts">
import { computed, ref, type DeepReadonly } from "vue";
import type { PlayerContentPage } from "../lib/types";
import { usePlayerStore } from "../stores/player";
import ImageLightbox, { type LightboxImage } from "./ImageLightbox.vue";

const props = defineProps<{ page: DeepReadonly<PlayerContentPage> }>();
const store = usePlayerStore();
const category = ref<string | null>(null);
const opened = ref<number | null>(null);

const items = computed(() =>
  (props.page.gallery ?? [])
    .map((item) => ({ item, url: store.imageUrl(item) }))
    .filter((entry) => entry.url.length > 0),
);
const categories = computed(() => {
  const counts = new Map<string, number>();
  for (const { item } of items.value) {
    const name = item.category.trim();
    if (name) counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  return [...counts].map(([name, count]) => ({ name, count }));
});
const lightboxImages = computed<LightboxImage[]>(() =>
  items.value.map(({ item, url }) => ({
    key: item.id,
    url,
    title: item.title,
    author: item.author || undefined,
    category: item.category || undefined,
    caption: item.caption ?? undefined,
    location: item.location ?? undefined,
  })),
);

/**
 * The design's mosaic, seven tiles to three rows: a large tile, a tall one on the right,
 * small ones between them and a wide strip underneath. Further images repeat it below.
 */
const MOSAIC = [
  { column: "1 / span 2", row: 1, rows: 2, large: true },
  { column: "3", row: 1, rows: 1 },
  { column: "4", row: 1, rows: 2 },
  { column: "3", row: 2, rows: 1 },
  { column: "1 / span 2", row: 3, rows: 1, large: true },
  { column: "3", row: 3, rows: 1 },
  { column: "4", row: 3, rows: 1 },
];

function tile(index: number): { style: Record<string, string>; large: boolean } {
  const place = MOSAIC[index % MOSAIC.length];
  const row = Math.floor(index / MOSAIC.length) * 3 + place.row;
  return {
    style: { gridColumn: place.column, gridRow: place.rows > 1 ? `${row} / span ${place.rows}` : String(row) },
    large: place.large === true && place.row === 1,
  };
}

function matches(item: { category: string }): boolean {
  return category.value == null || item.category.trim() === category.value;
}
</script>

<template>
  <main class="showcase-screen gallery-view">
    <header class="screen-head">
      <div class="screen-head-copy">
        <span class="eyebrow">{{ page.eyebrow || "PLAYER GALLERY" }}</span>
        <div class="screen-title-row">
          <h1 class="page-title screen-title">{{ page.title }}</h1>
          <span v-if="page.lead" class="screen-lead">{{ page.lead }}</span>
        </div>
      </div>
      <div v-if="categories.length > 1" class="pill-filters gallery-filters" role="group" aria-label="按分类筛选">
        <button type="button" class="pill-filter gallery-filter" :aria-pressed="category == null" @click="category = null">
          全部<span>{{ items.length }}</span>
        </button>
        <button
          v-for="entry in categories"
          :key="entry.name"
          type="button"
          class="pill-filter gallery-filter"
          :aria-pressed="category === entry.name"
          @click="category = entry.name"
        >{{ entry.name }}<span>{{ entry.count }}</span></button>
      </div>
    </header>

    <div v-if="items.length > 0" class="gallery-frame">
      <div class="gallery-grid">
        <button
          v-for="({ item, url }, index) in items"
          :key="item.id"
          type="button"
          class="gallery-tile"
          :class="{ 'tile-large': tile(index).large, dimmed: !matches(item) }"
          :style="tile(index).style"
          :aria-label="'查看大图：' + item.title + (item.author ? '，作者 ' + item.author : '')"
          @click="opened = index"
        >
          <img :src="url" :alt="item.title" loading="lazy" draggable="false" />
          <span v-if="item.featured" class="gallery-tile-badge">本周精选</span>
          <span class="gallery-tile-caption">
            <span class="gallery-tile-title">{{ item.title }}</span>
            <span v-if="item.author" class="gallery-tile-author">by {{ item.author }}</span>
          </span>
        </button>
      </div>
    </div>
    <p v-else class="news-empty">还没有放进画廊的作品</p>

    <ImageLightbox
      v-if="opened != null"
      v-model:index="opened"
      :images="lightboxImages"
      @close="opened = null"
    />
  </main>
</template>
