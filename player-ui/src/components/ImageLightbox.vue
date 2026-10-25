<script lang="ts">
export interface LightboxImage {
  key: string;
  url: string;
  title: string;
  author?: string;
  category?: string;
  caption?: string;
  location?: string;
}
</script>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "./AppIcon.vue";

const props = defineProps<{ images: LightboxImage[]; index: number }>();
const emit = defineEmits<{ close: []; "update:index": [index: number] }>();
const closeButton = ref<HTMLButtonElement | null>(null);

const current = computed(() => props.images[props.index] ?? null);
const byline = computed(() =>
  [current.value?.author ? "by " + current.value.author : "", current.value?.category ?? ""].filter(Boolean).join(" · "),
);
const counter = computed(() => {
  const pad = (value: number) => String(value).padStart(2, "0");
  return pad(props.index + 1) + " / " + pad(props.images.length);
});

function step(delta: number): void {
  const count = props.images.length;
  if (count > 0) emit("update:index", (props.index + delta + count) % count);
}

/** Only listens while the lightbox is open. */
function onKey(event: KeyboardEvent): void {
  if (event.key === "Escape") emit("close");
  else if (event.key === "ArrowLeft") step(-1);
  else if (event.key === "ArrowRight") step(1);
}

onMounted(() => {
  window.addEventListener("keydown", onKey);
  closeButton.value?.focus();
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKey);
});
</script>

<template>
  <div v-if="current" class="lightbox" role="dialog" aria-modal="true" :aria-label="current.title" @click.self="emit('close')">
    <button ref="closeButton" type="button" class="lightbox-close" aria-label="关闭大图" @click="emit('close')">
      <AppIcon name="close" />
    </button>
    <button v-if="images.length > 1" type="button" class="lightbox-step lightbox-prev" aria-label="上一张" @click="step(-1)">
      <AppIcon name="chevron" />
    </button>
    <figure class="lightbox-figure">
      <div class="lightbox-image"><img :src="current.url" :alt="current.title" draggable="false" /></div>
      <figcaption class="lightbox-caption">
        <div class="lightbox-copy">
          <div class="lightbox-heading">
            <span class="lightbox-title">{{ current.title }}</span>
            <span v-if="byline" class="lightbox-meta">{{ byline }}</span>
          </div>
          <span v-if="current.caption" class="lightbox-text">{{ current.caption }}</span>
        </div>
        <div class="lightbox-side">
          <span v-if="current.location" class="lightbox-location"><AppIcon name="pin" />{{ current.location }}</span>
          <span v-if="images.length > 1" class="lightbox-count">{{ counter }}</span>
        </div>
      </figcaption>
    </figure>
    <button v-if="images.length > 1" type="button" class="lightbox-step lightbox-next" aria-label="下一张" @click="step(1)">
      <AppIcon name="chevron" />
    </button>
  </div>
</template>
