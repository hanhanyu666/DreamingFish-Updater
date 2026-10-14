<script setup lang="ts">
import { useId } from 'vue';
// The DreamingFish mark: a small fish whose eye is cut out, so it reads on any background.
withDefaults(defineProps<{variant?:'glyph'|'mark'|'scene';size?:number}>(),{variant:'glyph',size:16});
const id=useId();
const tail='M10.4 16 4.8 11.6c-.7-.6-1.8-.1-1.8.8v7.2c0 .9 1.1 1.4 1.8.8l5.6-4.4Z';
const body='M9 16c2.8-4.6 6.6-7 10.6-7 4 0 7 2.6 8.4 7-1.4 4.4-4.4 7-8.4 7-4 0-7.8-2.4-10.6-7Zm14.7-1.6a1.5 1.5 0 1 0-3 0 1.5 1.5 0 0 0 3 0Z';
</script>
<template>
 <svg v-if="variant==='glyph'" class="fish-glyph" :width="size" :height="size" viewBox="0 0 32 32" aria-hidden="true"><path :d="tail" fill="currentColor"/><path :d="body" fill="currentColor" fill-rule="evenodd"/></svg>
 <span v-else-if="variant==='mark'" class="fish-mark" :style="{width:`${size}px`,height:`${size}px`}" aria-hidden="true"><svg viewBox="0 0 32 32"><path :d="tail" fill="#fff"/><path :d="body" fill="#fff" fill-rule="evenodd"/><path d="M15.2 12.8c1 .9 1.6 2 1.6 3.2s-.6 2.3-1.6 3.2" fill="none" stroke="rgba(7,70,62,.45)" stroke-width="1.4" stroke-linecap="round"/><circle cx="27.6" cy="7.4" r="1.6" fill="none" stroke="rgba(255,255,255,.8)" stroke-width="1.2"/></svg></span>
 <svg v-else class="fish-scene" viewBox="0 0 120 96" aria-hidden="true">
  <defs><linearGradient :id="`${id}-body`" x1="0" y1="0" x2="1" y2="1"><stop offset="0" style="stop-color:var(--fish-a)"/><stop offset="1" style="stop-color:var(--fish-b)"/></linearGradient></defs>
  <circle cx="58" cy="50" r="38" class="scene-water"/>
  <path d="M16 74c6-4 12-4 18 0s12 4 18 0 12-4 18 0 12 4 18 0 12-4 18 0" class="scene-wave"/>
  <g class="scene-fish"><g transform="translate(18 15) scale(2.05)"><path :d="tail" :fill="`url(#${id}-body)`"/><path :d="body" :fill="`url(#${id}-body)`" fill-rule="evenodd"/><path d="M15.2 12.8c1 .9 1.6 2 1.6 3.2s-.6 2.3-1.6 3.2" fill="none" stroke="rgba(255,255,255,.55)" stroke-width="1.3" stroke-linecap="round"/></g></g>
  <circle cx="88" cy="30" r="4" class="scene-bubble"/><circle cx="95" cy="19" r="2.6" class="scene-bubble b2"/><circle cx="86" cy="12" r="1.8" class="scene-bubble b3"/>
 </svg>
</template>
