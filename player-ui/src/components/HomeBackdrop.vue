<script setup lang="ts">
import { computed } from "vue";
import { carousel } from "../lib/homeCarousel";
import { homeSlides } from "../lib/showcase";
import { usePlayerStore } from "../stores/player";

const store = usePlayerStore();

/** Slides without their own image let the project background behind them show through. */
const layers = computed(() =>
  homeSlides(store.state.branding, store.state.contentPages)
    .map((slide, index) => ({ id: slide.id, index, url: store.imageUrl(slide.image) }))
    .filter((layer) => layer.url.length > 0),
);
</script>

<template>
  <div class="home-backdrop" aria-hidden="true">
    <div
      v-for="layer in layers"
      :key="layer.id"
      class="home-backdrop-layer"
      :class="{ active: layer.index === carousel.index && store.state.page === 'HOME' }"
    >
      <img :src="layer.url" alt="" draggable="false" />
    </div>
  </div>
</template>
