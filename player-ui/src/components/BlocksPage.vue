<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, type DeepReadonly } from "vue";
import { copyText } from "../lib/clipboard";
import type { IconName } from "../lib/icons";
import { backgroundImage, usableAction } from "../lib/showcase";
import type { PlayerBlock, PlayerBlockKind, PlayerContentPage, PlayerLinkTarget } from "../lib/types";
import { usePlayerStore } from "../stores/player";
import AppIcon from "./AppIcon.vue";
import ImageLightbox, { type LightboxImage } from "./ImageLightbox.vue";
import MarkdownBody from "./MarkdownBody.vue";
import PixelFace from "./PixelFace.vue";

const props = defineProps<{ page: DeepReadonly<PlayerContentPage> }>();
const store = usePlayerStore();
const scroller = ref<HTMLElement | null>(null);
const activeId = ref<string | null>(null);
const lightbox = ref<{ images: LightboxImage[]; index: number } | null>(null);
const openFaq = ref<Record<string, number | null>>({});
const copied = ref<string | null>(null);
let copiedTimer: number | null = null;
let observer: IntersectionObserver | null = null;

const KNOWN_ICONS = new Set<string>([
  "star", "shield", "users", "chat", "globe", "calendar", "pin", "image", "sparkle", "sword",
  "pickaxe", "heart", "home", "gift", "trophy", "book", "clock", "check", "info", "signal",
]);
/** Event-like icons sit on the gold tile, as the design's 周六活动夜 card does. */
const WARM_ICONS = new Set(["calendar", "gift", "trophy", "heart"]);
const TONES = new Set(["DEFAULT", "ACCENT", "SECONDARY", "WARM", "MUTED"]);
const KIND_EYEBROWS: Record<PlayerBlockKind, string> = {
  TEXT: "ABOUT", CARDS: "FEATURES", STEPS: "HOW TO JOIN", LIST: "RULES", TEAM: "TEAM",
  FAQ: "FAQ", STATS: "NUMBERS", CALLOUT: "NOTE", IMAGE: "PHOTO", GALLERY: "GALLERY",
};
const OWNER_ROLE = /服主|腐竹|站长|owner/i;

const address = computed(() => store.state.branding.serverAddress?.trim() ?? "");
const cover = computed(() => store.imageUrl(props.page) || backgroundImage(store.state.backgroundUrl));
const tags = computed(() => (props.page.tags ?? []).map((tag) => tag.trim()).filter(Boolean).slice(0, 6));

function actionsOf(actions: readonly DeepReadonly<PlayerLinkTarget>[] | null | undefined): PlayerLinkTarget[] {
  return (actions ?? [])
    .map((action) => usableAction(action, store.state.contentPages))
    .filter((action): action is PlayerLinkTarget => action != null)
    .slice(0, 3);
}

function defaultColumns(kind: PlayerBlock["kind"], count: number): number {
  if (kind === "TEAM" || kind === "STATS") return Math.min(4, Math.max(1, count));
  if (kind === "STEPS") return Math.min(3, Math.max(1, count));
  return kind === "GALLERY" ? 3 : 2;
}

const blocks = computed(() => {
  let number = 0;
  return (props.page.blocks ?? []).map((block: DeepReadonly<PlayerBlock>) => {
    const items = (block.items ?? []).slice(0, 24);
    const nav = (block.navLabel || block.title || "").trim();
    const titled = Boolean(block.title?.trim());
    if (titled) number += 1;
    return {
      block,
      anchor: "block-" + block.id,
      nav,
      number: titled ? String(number).padStart(2, "0") : "",
      eyebrow: (block.eyebrow?.trim() || KIND_EYEBROWS[block.kind] || "").toUpperCase(),
      tone: "tone-" + (TONES.has(block.tone ?? "") ? block.tone! : "DEFAULT").toLowerCase(),
      columns: Math.min(4, Math.max(1, Math.round(block.columns ?? 0) || defaultColumns(block.kind, items.length))),
      image: store.imageUrl(block),
      actions: actionsOf(block.actions),
      items: items.map((item) => ({
        item,
        icon: (KNOWN_ICONS.has(item.icon ?? "") ? item.icon : null) as IconName | null,
        warm: WARM_ICONS.has(item.icon ?? ""),
        image: store.imageUrl(item),
        action: usableAction(item.action, store.state.contentPages),
        owner: OWNER_ROLE.test(item.meta ?? ""),
      })),
    };
  });
});
const navigation = computed(() => blocks.value.filter((entry) => entry.nav.length > 0));

function jump(anchor: string): void {
  activeId.value = anchor;
  scroller.value?.querySelector<HTMLElement>("#" + CSS.escape(anchor))
    ?.scrollIntoView({ behavior: "smooth", block: "start" });
}

function faqOpen(blockId: string, index: number): boolean {
  return (openFaq.value[blockId] ?? 0) === index;
}

function toggleFaq(blockId: string, index: number): void {
  openFaq.value = { ...openFaq.value, [blockId]: faqOpen(blockId, index) ? null : index };
}

async function copy(key: string, text: string): Promise<void> {
  await copyText(text);
  copied.value = key;
  if (copiedTimer != null) window.clearTimeout(copiedTimer);
  copiedTimer = window.setTimeout(() => {
    copied.value = null;
  }, 1800);
}

function openGallery(entry: (typeof blocks.value)[number], index: number): void {
  const images = entry.items
    .filter(({ image }) => image.length > 0)
    .map(({ item, image }) => ({ key: item.id, url: image, title: item.title, author: item.meta ?? undefined, caption: item.text ?? undefined }));
  lightbox.value = { images, index };
}

onMounted(async () => {
  await nextTick();
  activeId.value = navigation.value[0]?.anchor ?? null;
  if (scroller.value == null || typeof IntersectionObserver === "undefined") return;
  observer = new IntersectionObserver(
    (entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting)
        .sort((left, right) => left.boundingClientRect.top - right.boundingClientRect.top)[0];
      if (visible) activeId.value = visible.target.id;
    },
    { root: scroller.value, rootMargin: "-25% 0px -60% 0px" },
  );
  scroller.value.querySelectorAll(".guide-block").forEach((element) => observer?.observe(element));
});

onBeforeUnmount(() => {
  observer?.disconnect();
  if (copiedTimer != null) window.clearTimeout(copiedTimer);
});
</script>

<template>
  <div ref="scroller" class="guide-page showcase-page" data-solid-after="180">
    <section class="guide-hero" :aria-label="page.title">
      <img class="guide-hero-image" :src="cover" alt="" draggable="false" />
      <div class="guide-hero-copy">
        <span class="eyebrow">{{ page.eyebrow || "SERVER GUIDE" }}</span>
        <h1 class="page-title guide-title">{{ page.title }}</h1>
        <p v-if="page.lead" class="guide-lead">{{ page.lead }}</p>
        <div v-if="tags.length > 0" class="guide-tags">
          <span v-for="tag in tags" :key="tag" class="guide-tag">{{ tag }}</span>
        </div>
      </div>
      <div v-if="address" class="guide-address">
        <span class="guide-address-text">
          <span class="guide-address-caption">{{ copied === "hero" ? "已复制到剪贴板" : "服务器地址" }}</span>
          <span class="guide-address-value">{{ address }}</span>
        </span>
        <button type="button" class="accent-button" @click="copy('hero', address)">
          {{ copied === "hero" ? "已复制" : "复制地址" }}
        </button>
      </div>
    </section>

    <div class="guide-body" :class="{ 'without-nav': navigation.length < 2 }">
      <nav v-if="navigation.length > 1" class="guide-nav" aria-label="页面目录">
        <button
          v-for="(entry, index) in navigation"
          :key="entry.anchor"
          type="button"
          class="guide-nav-item showcase-nav-item"
          :aria-current="activeId === entry.anchor ? 'true' : undefined"
          @click="jump(entry.anchor)"
        ><span class="index-number">{{ String(index + 1).padStart(2, "0") }}</span>{{ entry.nav }}</button>
      </nav>

      <div class="guide-sections">
        <section
          v-for="entry in blocks"
          :id="entry.anchor"
          :key="entry.anchor"
          class="guide-block showcase-block"
          :class="['kind-' + entry.block.kind.toLowerCase(), entry.tone]"
          :style="{ '--columns': entry.columns }"
        >
          <header v-if="entry.block.title && entry.block.kind !== 'CALLOUT'" class="block-header">
            <span class="eyebrow">{{ entry.number }} · {{ entry.eyebrow }}</span>
            <h2 class="block-title">{{ entry.block.title }}</h2>
            <p v-if="entry.block.lead && entry.block.kind !== 'IMAGE'" class="block-lead">{{ entry.block.lead }}</p>
          </header>

          <MarkdownBody v-if="entry.block.kind === 'TEXT'" :markdown="entry.block.markdown ?? ''" />

          <div v-else-if="entry.block.kind === 'CARDS'" class="block-grid">
            <article v-for="{ item, icon, warm, image, action } in entry.items" :key="item.id" class="block-card">
              <img v-if="image" class="block-card-image" :src="image" :alt="item.title" loading="lazy" />
              <span v-else-if="icon" class="block-card-icon" :class="{ warm }" aria-hidden="true"><AppIcon :name="icon" /></span>
              <span class="block-card-copy">
                <h3 class="block-card-title">{{ item.title }}</h3>
                <p v-if="item.text" class="block-card-text">{{ item.text }}</p>
                <button v-if="action" type="button" class="block-link" @click="store.followLink(action)">
                  {{ action.label }}<AppIcon name="chevron" />
                </button>
              </span>
            </article>
          </div>

          <ol v-else-if="entry.block.kind === 'STEPS'" class="block-steps">
            <li v-for="({ item }, index) in entry.items" :key="item.id" class="block-step">
              <span class="block-step-number" aria-hidden="true">{{ String(index + 1).padStart(2, "0") }}</span>
              <h3 class="block-step-title">{{ item.title }}</h3>
              <p v-if="item.text" class="block-step-text">{{ item.text }}</p>
              <button v-if="item.meta" type="button" class="block-step-copy" @click="copy(item.id, item.meta)">
                <span>{{ item.meta }}</span><span class="block-step-copy-label">{{ copied === item.id ? "已复制" : "复制" }}</span>
              </button>
            </li>
          </ol>

          <ol v-else-if="entry.block.kind === 'LIST'" class="block-list">
            <li v-for="({ item }, index) in entry.items" :key="item.id" class="block-list-row">
              <span class="block-list-number" aria-hidden="true">{{ String(index + 1).padStart(2, "0") }}</span>
              <span class="block-list-copy">
                <strong class="block-list-title">{{ item.title }}</strong>
                <span v-if="item.text" class="block-list-text">{{ item.text }}</span>
              </span>
              <span v-if="item.meta" class="block-list-badge">{{ item.meta }}</span>
            </li>
          </ol>

          <div v-else-if="entry.block.kind === 'TEAM'" class="block-grid">
            <article v-for="{ item, image, owner } in entry.items" :key="item.id" class="block-member">
              <span class="block-member-avatar">
                <img v-if="image" :src="image" :alt="item.title" loading="lazy" />
                <PixelFace v-else :name="item.title" />
              </span>
              <span class="block-member-names">
                <h3 class="block-member-name">{{ item.title }}</h3>
                <span v-if="item.meta" class="block-member-role" :class="{ owner }">{{ item.meta }}</span>
              </span>
              <p v-if="item.text" class="block-member-text">{{ item.text }}</p>
            </article>
          </div>

          <div v-else-if="entry.block.kind === 'FAQ'" class="block-faq">
            <div
              v-for="({ item }, index) in entry.items"
              :key="item.id"
              class="block-faq-item"
              :class="{ open: faqOpen(entry.block.id, index) }"
            >
              <button
                type="button"
                class="block-faq-question"
                :aria-expanded="faqOpen(entry.block.id, index)"
                @click="toggleFaq(entry.block.id, index)"
              ><span>{{ item.title }}</span><AppIcon name="plus" /></button>
              <p v-if="faqOpen(entry.block.id, index)" class="block-faq-answer">{{ item.text }}</p>
            </div>
          </div>

          <div v-else-if="entry.block.kind === 'STATS'" class="block-grid block-stats">
            <div v-for="{ item } in entry.items" :key="item.id" class="block-stat">
              <span class="block-stat-value">{{ item.title }}</span>
              <span v-if="item.text" class="block-stat-label">{{ item.text }}</span>
              <span v-if="item.meta" class="block-stat-meta">{{ item.meta }}</span>
            </div>
          </div>

          <div v-else-if="entry.block.kind === 'CALLOUT'" class="block-callout" :class="{ 'with-image': entry.image }">
            <img v-if="entry.image" class="block-callout-image" :src="entry.image" alt="" />
            <div class="block-callout-copy">
              <h2 v-if="entry.block.title" class="block-title">{{ entry.block.title }}</h2>
              <p v-if="entry.block.lead" class="block-lead">{{ entry.block.lead }}</p>
              <MarkdownBody v-if="entry.block.markdown" :markdown="entry.block.markdown" />
            </div>
            <div v-if="entry.actions.length > 0" class="block-actions">
              <button
                v-for="(action, index) in entry.actions"
                :key="index"
                type="button"
                class="block-action"
                :class="{ primary: index === 0 }"
                @click="store.followLink(action)"
              >{{ action.label }}</button>
            </div>
          </div>

          <figure v-else-if="entry.block.kind === 'IMAGE' && entry.image" class="block-figure">
            <img :src="entry.image" :alt="entry.block.title ?? ''" loading="lazy" />
            <figcaption v-if="entry.block.lead">{{ entry.block.lead }}</figcaption>
          </figure>

          <div v-else-if="entry.block.kind === 'GALLERY'" class="block-grid block-gallery">
            <button
              v-for="({ item, image }, index) in entry.items.filter(({ image }) => image)"
              :key="item.id"
              type="button"
              class="gallery-tile"
              :aria-label="'查看大图：' + item.title"
              @click="openGallery(entry, index)"
            >
              <img :src="image" :alt="item.title" loading="lazy" draggable="false" />
              <span class="gallery-tile-caption">
                <span class="gallery-tile-title">{{ item.title }}</span>
                <span v-if="item.meta" class="gallery-tile-author">by {{ item.meta }}</span>
              </span>
            </button>
          </div>

          <div v-if="entry.block.kind !== 'CALLOUT' && entry.actions.length > 0" class="block-actions">
            <button
              v-for="(action, index) in entry.actions"
              :key="index"
              type="button"
              class="block-action"
              :class="{ primary: index === 0 }"
              @click="store.followLink(action)"
            >{{ action.label }}</button>
          </div>
        </section>
      </div>
    </div>

    <ImageLightbox
      v-if="lightbox != null"
      v-model:index="lightbox.index"
      :images="lightbox.images"
      @close="lightbox = null"
    />
  </div>
</template>
