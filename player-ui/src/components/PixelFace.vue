<script setup lang="ts">
import { computed } from "vue";
import { pixelFace } from "../lib/pixelFace";

const props = defineProps<{ name: string }>();
const pixels = computed(() => pixelFace(props.name));
</script>

<template>
  <svg class="pixel-face" viewBox="0 0 8 8" aria-hidden="true" focusable="false" shape-rendering="crispEdges">
    <rect
      v-for="pixel in pixels"
      :key="pixel.key"
      :x="pixel.x"
      :y="pixel.y"
      :width="pixel.w"
      :height="pixel.h"
      :fill="pixel.fill"
    />
  </svg>
</template>
