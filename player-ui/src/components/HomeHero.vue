<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { copyText } from "../lib/clipboard";
import { carousel, showSlide, SLIDE_MS } from "../lib/homeCarousel";
import { homeSlides, type HomeSlide } from "../lib/showcase";
import { usePlayerStore } from "../stores/player";
import AppIcon from "./AppIcon.vue";

/** Up to this many slides each get a named bar; more switch to the compact pager. */
const MAX_DOTS = 6;

const store = usePlayerStore();
const slides = computed(() => homeSlides(store.state.branding, store.state.contentPages));
/** Reading or pointing at a slide holds it in place. */
const paused = ref(false);
/** Only when the owner puts the address beside the spotlight button; by default it lives in the server card. */
const address = computed(() =>
  store.state.branding.addressPlacement === "HERO" ? store.state.branding.serverAddress?.trim() ?? "" : "");
const copied = ref(false);
let timer: number | null = null;
let copiedTimer: number | null = null;
let lastTick = 0;

watch(
  () => slides.value.length,
  (count) => {
    if (carousel.index >= count) showSlide(0, count);
  },
  { immediate: true },
);

function tick(): void {
  const now = performance.now();
  const elapsed = Math.min(250, now - lastTick);
  lastTick = now;
  if (paused.value || slides.value.length < 2 || store.state.page !== "HOME" || document.hidden) return;
  carousel.progress = Math.min(1, carousel.progress + elapsed / SLIDE_MS);
  if (carousel.progress >= 1) showSlide(carousel.index + 1, slides.value.length);
}

onMounted(() => {
  lastTick = performance.now();
  timer = window.setInterval(tick, 100);
});

onBeforeUnmount(() => {
  if (timer != null) window.clearInterval(timer);
  if (copiedTimer != null) window.clearTimeout(copiedTimer);
});

function pick(index: number): void {
  showSlide(index, slides.value.length);
}

function follow(slide: HomeSlide): void {
  if (slide.action != null) store.followLink(slide.action);
}

async function copyAddress(): Promise<void> {
  await copyText(address.value);
  copied.value = true;
  if (copiedTimer != null) window.clearTimeout(copiedTimer);
  copiedTimer = window.setTimeout(() => {
    copied.value = false;
  }, 1800);
}
</script>

<template>
  <section
    class="home-hero"
    aria-roledescription="carousel"
    aria-label="首页焦点"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
    @focusin="paused = true"
    @focusout="paused = false"
  >
    <div class="home-hero-slides">
      <article
        v-for="(slide, index) in slides"
        :key="slide.id"
        class="home-slide"
        :class="['home-slide-' + slide.kind.toLowerCase(), { active: index === carousel.index }]"
        aria-roledescription="slide"
        :aria-label="index + 1 + ' / ' + slides.length"
        :aria-hidden="index !== carousel.index"
        :inert="index !== carousel.index"
      >
        <div v-if="slide.tag || slide.eyebrow" class="home-slide-meta">
          <span v-if="slide.tag" class="home-slide-tag">{{ slide.tag }}</span>
          <span v-if="slide.eyebrow" class="home-slide-eyebrow">{{ slide.eyebrow }}</span>
        </div>
        <h1 class="home-slide-title">{{ slide.title }}</h1>
        <p v-if="slide.lead" class="home-slide-lead">{{ slide.lead }}</p>
        <div v-if="slide.action || address" class="home-slide-actions">
          <button v-if="slide.action" type="button" class="home-slide-action" @click="follow(slide)">
            {{ slide.action.label }}<AppIcon name="arrowRight" />
          </button>
          <button
            v-if="address"
            type="button"
            class="home-slide-copy"
            :class="{ copied }"
            :title="'复制服务器地址 ' + address"
            :aria-label="'复制服务器地址 ' + address"
            @click="copyAddress"
          >
            <AppIcon :name="copied ? 'check' : 'copy'" />
            <span class="home-slide-copy-text">{{ copied ? "已复制地址" : address }}</span>
          </button>
        </div>
      </article>
    </div>

    <!-- Past a handful of slides the named bars get too narrow; a pager takes their place. -->
    <div v-if="slides.length > MAX_DOTS" class="home-slide-pager" role="group" aria-label="切换首页焦点">
      <button type="button" class="home-slide-step" aria-label="上一张" title="上一张" @click="pick(carousel.index - 1)">
        <AppIcon name="chevron" class="flipped" />
      </button>
      <span class="home-slide-count" aria-live="polite">
        <b>{{ String(carousel.index + 1).padStart(2, "0") }}</b> / {{ String(slides.length).padStart(2, "0") }}
      </span>
      <span class="home-slide-pager-bar" aria-hidden="true">
        <span :style="{ transform: `scaleX(${carousel.progress})` }"></span>
      </span>
      <span class="home-slide-pager-label">{{ slides[carousel.index]?.label }}</span>
      <button type="button" class="home-slide-step" aria-label="下一张" title="下一张" @click="pick(carousel.index + 1)">
        <AppIcon name="chevron" />
      </button>
    </div>
    <div v-else-if="slides.length > 1" class="home-slide-dots" role="group" aria-label="切换首页焦点">
      <button
        v-for="(slide, index) in slides"
        :key="slide.id"
        type="button"
        class="home-slide-dot"
        :class="{ active: index === carousel.index }"
        :aria-label="'第 ' + (index + 1) + ' 张：' + slide.title"
        :aria-current="index === carousel.index ? 'true' : undefined"
        @click="pick(index)"
      >
        <span class="home-slide-dot-text">
          <span class="home-slide-dot-index">{{ String(index + 1).padStart(2, "0") }}</span>
          <span class="home-slide-dot-label">{{ slide.label }}</span>
        </span>
        <span class="home-slide-dot-bar" aria-hidden="true">
          <span :style="{ transform: `scaleX(${index === carousel.index ? carousel.progress : 0})` }"></span>
        </span>
      </button>
    </div>
  </section>
</template>
