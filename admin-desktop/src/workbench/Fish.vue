<script setup lang="ts">
import { useId } from 'vue';
import { brandLogo, fishPath } from '../brand-logo';
// The DreamingFish moon fish: a round sleeping fish. The glyph cuts its closed eye out, so it reads on any background;
// the mark is the app icon itself.
withDefaults(defineProps<{variant?:'glyph'|'mark'|'scene';size?:number}>(),{variant:'glyph',size:16});
const id=useId();
const pose='rotate(-14 256 264)';
const glyph=`translate(16 16.5) scale(.111) translate(-265 -257) ${pose}`;
const scene=`translate(57 50) scale(.245) translate(-265 -257) ${pose}`;
</script>
<template>
 <svg v-if="variant==='glyph'" class="fish-glyph" :width="size" :height="size" viewBox="0 0 32 32" aria-hidden="true">
  <defs><mask :id="`${id}-eye`"><rect width="512" height="512" fill="#fff"/><path d="M326 247q10 12 20 0" fill="none" stroke="#000" stroke-width="16" stroke-linecap="round"/></mask></defs>
  <path :transform="glyph" :d="fishPath" fill="currentColor" :mask="`url(#${id}-eye)`"/>
 </svg>
 <span v-else-if="variant==='mark'" class="fish-mark" :style="{width:`${size}px`,height:`${size}px`}" aria-hidden="true" v-html="brandLogo(id)"></span>
 <svg v-else class="fish-scene" viewBox="0 0 120 96" aria-hidden="true">
  <defs><linearGradient :id="`${id}-body`" x1="0" y1="0" x2="1" y2="1"><stop offset="0" style="stop-color:var(--fish-a)"/><stop offset="1" style="stop-color:var(--fish-b)"/></linearGradient><clipPath :id="`${id}-fish`"><path :d="fishPath"/></clipPath></defs>
  <circle cx="58" cy="50" r="38" class="scene-water"/>
  <path d="M16 74c6-4 12-4 18 0s12 4 18 0 12-4 18 0 12 4 18 0 12-4 18 0" class="scene-wave"/>
  <g class="scene-fish"><g :transform="scene">
   <path :d="fishPath" :fill="`url(#${id}-body)`"/>
   <path d="M174 295c72 50 159 49 229-18l25 108-228 26Z" fill="#fff" opacity=".2" :clip-path="`url(#${id}-fish)`"/>
   <path d="M212 216c18-20 45-31 72-30" fill="none" stroke="#fff" stroke-opacity=".55" stroke-width="9" stroke-linecap="round"/>
   <path d="M326 247q10 12 20 0" fill="none" stroke="rgba(6,48,43,.72)" stroke-width="9" stroke-linecap="round"/>
   <ellipse cx="335" cy="277" rx="15" ry="9" fill="#ffb3c4" opacity=".75"/>
  </g></g>
  <circle cx="95" cy="25" r="4" class="scene-bubble"/><circle cx="102" cy="14" r="2.6" class="scene-bubble b2"/><circle cx="94" cy="7" r="1.8" class="scene-bubble b3"/>
 </svg>
</template>
