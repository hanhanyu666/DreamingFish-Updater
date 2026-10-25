<script lang="ts">
/**
 * The DreamingFish moon fish from the admin app (admin-desktop/src/brand-logo.ts), with a face per update state:
 * swim dreams while it swims (updating), happy (done), worried (stopped), sleep (offline), wow (just petted).
 */
export type FishMood = "swim" | "happy" | "worried" | "sleep" | "wow";
</script>

<script setup lang="ts">
import { computed, useId } from "vue";

const props = withDefaults(defineProps<{ mood?: FishMood; size?: number }>(), {
  mood: "swim",
  size: 30,
});

const FISH =
  "M182 244c1-50 43-84 98-84 70 0 119 49 119 109 0 60-48 100-108 100-56 0-99-37-109-85l-46 27c-8 5-15-1-11-10l16-32-16-34c-4-9 2-15 11-10Z";
const STAR = "M382 116c2 6 4 8 10 10-6 2-8 4-10 10-2-6-4-8-10-10 6-2 8-4 10-10Z";

const id = useId();
/** Fish units per screen pixel, so the face keeps a readable line weight at small sizes. */
const units = computed(() => 312 / props.size);
const eyeWidth = computed(() => Math.max(1.5, props.size * 0.022) * units.value);
const eyeRadius = computed(() => (Math.max(2.6, props.size * 0.06) / 2) * units.value);
const blush = computed(() => ({
  rx: Math.max(13, 2.4 * units.value),
  ry: Math.max(8, 1.5 * units.value),
}));
const showMouth = computed(() => props.size >= 56);
const showDetail = computed(() => props.size >= 48);
/** Extras such as bubbles and Zz grow at small sizes so they still read. */
const extrasScale = computed(() => Math.max(1, Math.min(2.4, 64 / props.size)));

function around(x: number, y: number, scale: number): string {
  return `translate(${x} ${y}) scale(${scale.toFixed(3)}) translate(${-x} ${-y})`;
}
</script>

<template>
  <svg
    class="moon-fish"
    :class="'moon-fish-' + mood"
    viewBox="100 110 312 312"
    :width="size"
    :height="size"
    aria-hidden="true"
    focusable="false"
  >
    <defs>
      <linearGradient :id="`${id}-body`" x1="230" y1="164" x2="314" y2="371" gradientUnits="userSpaceOnUse">
        <stop stop-color="#FFFCF1" />
        <stop offset="1" stop-color="#FAEFD9" />
      </linearGradient>
      <clipPath :id="`${id}-body-clip`"><path :d="FISH" /></clipPath>
    </defs>
    <g class="moon-fish-motion">
      <g transform="rotate(-14 256 264)">
        <path :d="FISH" :fill="`url(#${id}-body)`" />
        <path
          d="M174 295c72 50 159 49 229-18l25 108-228 26Z"
          fill="#EAE3F1"
          opacity=".78"
          :clip-path="`url(#${id}-body-clip)`"
        />
        <template v-if="showDetail">
          <path
            d="M212 216c18-20 45-31 72-30"
            fill="none"
            stroke="#FFF"
            stroke-opacity=".7"
            :stroke-width="Math.max(6, eyeWidth * 0.8)"
            stroke-linecap="round"
          />
          <path
            d="m138 242 18 20m-18 31 19-18"
            fill="none"
            stroke="#D9D1EB"
            stroke-opacity=".65"
            stroke-width="4"
            stroke-linecap="round"
          />
        </template>
        <path class="moon-fish-fin" d="M257 254c-22-3-36 7-30 19 5 11 22 5 30-7" fill="#C8BDEA" />
        <g fill="none" stroke="#615579" stroke-linecap="round" stroke-linejoin="round" :stroke-width="eyeWidth">
          <path v-if="mood === 'swim' || mood === 'sleep'" d="M326 247q10 12 20 0" />
          <path v-else-if="mood === 'happy'" d="M325 253q11 -14 22 0" />
          <path v-else-if="mood === 'wow'" d="M328 238l16 10-16 10" />
        </g>
        <g v-if="mood === 'worried'">
          <circle cx="336" cy="249" :r="eyeRadius" fill="#615579" />
          <circle cx="332.5" cy="245.5" :r="eyeRadius * 0.34" fill="#FFF" />
          <path
            d="M321 231q12 -10 26 -3"
            fill="none"
            stroke="#615579"
            :stroke-width="eyeWidth * 0.68"
            stroke-linecap="round"
          />
        </g>
        <ellipse cx="335" cy="277" :rx="blush.rx" :ry="blush.ry" fill="#EFB3C4" />
        <g v-if="showMouth" fill="none" stroke="#615579" stroke-linecap="round" :stroke-width="eyeWidth * 0.68">
          <path v-if="mood === 'happy' || mood === 'wow'" d="M366 271q8 11 17 0" />
          <path v-else-if="mood === 'worried'" d="M367 281q6 -6 12 0" />
          <path v-else d="M369 274q5 6 10 0" />
        </g>
      </g>
    </g>

    <g v-if="mood === 'swim'" :transform="around(398, 222, extrasScale)" fill="none" stroke="#E6DFFB">
      <circle class="moon-fish-rise" cx="398" cy="228" r="9" stroke-width="4" />
      <circle class="moon-fish-rise moon-fish-late" cx="406" cy="212" r="6" stroke-width="3.5" />
      <circle class="moon-fish-rise moon-fish-later" cx="394" cy="220" r="4.5" stroke-width="3" />
    </g>
    <g
      v-else-if="mood === 'sleep'"
      :transform="around(348, 140, extrasScale)"
      fill="none"
      stroke="#E6DFFB"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path class="moon-fish-z" d="M330 128h24l-24 24h24" stroke-width="7" />
      <path class="moon-fish-z moon-fish-later" d="M364 100h16l-16 16h16" stroke-width="6" />
    </g>
    <g v-else-if="mood === 'worried'" :transform="around(388, 176, extrasScale)">
      <g class="moon-fish-drip">
        <path d="M388 160c8 11 12 17 12 23a12 12 0 0 1-24 0c0-6 4-12 12-23Z" fill="#9FD8FF" />
        <path d="M383 182q0 5 4 7" fill="none" stroke="#FFF" stroke-opacity=".8" stroke-width="3" stroke-linecap="round" />
      </g>
    </g>
    <g v-else-if="mood === 'wow'" :transform="around(380, 150, extrasScale)">
      <path
        class="moon-fish-heart"
        d="M380 166c-15-10-22-17-22-25a10 10 0 0 1 22-5 10 10 0 0 1 22 5c0 8-7 15-22 25Z"
        fill="#FF8FB1"
      />
    </g>
    <g v-else-if="mood === 'happy'" fill="#FFE6AD">
      <g :transform="`translate(158 166) scale(${(1.7 * extrasScale).toFixed(3)}) translate(-382 -126)`">
        <path class="moon-fish-twinkle" :d="STAR" />
      </g>
      <g :transform="`translate(398 322) scale(${(1.1 * extrasScale).toFixed(3)}) translate(-382 -126)`">
        <path class="moon-fish-twinkle moon-fish-late" :d="STAR" />
      </g>
    </g>
  </svg>
</template>
