<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { getBridge } from "../lib/bridge";
import { copyText } from "../lib/clipboard";
import type { IconName } from "../lib/icons";
import { COMMUNITY_LABELS, parseMotd, plainMotd, safeHttpUrl } from "../lib/showcase";
import type { PlayerCommunityKind, PlayerCommunityLink } from "../lib/types";
import { usePlayerStore } from "../stores/player";
import AppIcon from "./AppIcon.vue";

const COPIED_MS = 1800;
/** Community link ids are lowercase words, so this never collides with one. */
const ADDRESS_KEY = "@address";
const LINK_ICONS: Record<PlayerCommunityKind, IconName> = {
  QQ: "users",
  DISCORD: "chat",
  KOOK: "chat",
  BILIBILI: "tv",
  WEBSITE: "globe",
  OTHER: "link",
};

const store = usePlayerStore();
const bridge = getBridge();
const copied = ref<string | null>(null);
let copiedTimer: number | null = null;

const card = computed(() => store.state.branding.serverCard ?? null);
const address = computed(() => store.state.branding.serverAddress?.trim() ?? "");
const status = computed(() => (card.value?.showStatus === false ? null : store.state.serverStatus));
const links = computed(() =>
  (card.value?.links ?? [])
    .filter((link) => safeHttpUrl(link.url) !== "" || Boolean(link.copyText?.trim()))
    .slice(0, 4),
);
const logo = computed(() => {
  if (card.value?.logoFile) {
    const uploaded = store.imageUrl({ imageFile: card.value.logoFile });
    if (uploaded) return uploaded;
  }
  const favicon = status.value?.favicon ?? "";
  return favicon.startsWith("data:image/") ? favicon : "";
});
const initial = computed(() => Array.from(store.state.branding.productName.trim())[0] ?? "?");
const version = computed(() => card.value?.versionLabel?.trim() || status.value?.version?.trim() || "");
const motdSource = computed(() => card.value?.motd?.trim() || status.value?.motd || "");
const motdLines = computed(() => parseMotd(motdSource.value).filter((line) => line.length > 0));
const motdTitle = computed(() => plainMotd(motdSource.value));

/** The owner picks where the address appears; the card shows it unless told otherwise. */
const addressInCard = computed(() =>
  (store.state.branding.addressPlacement ?? "CARD") === "CARD" ? address.value : "");
/** A card the owner added always shows; without one, the card appears only for the address or status. */
const visible = computed(() =>
  card.value?.enabled !== false
    && (card.value != null || addressInCard.value.length > 0 || status.value != null),
);

const latencyTone = computed(() => {
  const latency = status.value?.latencyMs;
  if (latency == null) return "";
  return latency < 80 ? "good" : latency < 150 ? "fair" : "slow";
});

function flashCopied(key: string): void {
  copied.value = key;
  if (copiedTimer != null) window.clearTimeout(copiedTimer);
  copiedTimer = window.setTimeout(() => {
    copied.value = null;
  }, COPIED_MS);
}

async function openLink(link: PlayerCommunityLink): Promise<void> {
  const url = safeHttpUrl(link.url);
  if (url) {
    bridge.openExternal(url);
    return;
  }
  await copyText(link.copyText!.trim());
  flashCopied(link.id);
}

async function copyAddress(): Promise<void> {
  await copyText(addressInCard.value);
  flashCopied(ADDRESS_KEY);
}

function linkLabel(link: PlayerCommunityLink): string {
  return link.label.trim() || COMMUNITY_LABELS[link.kind] || "链接";
}

onBeforeUnmount(() => {
  if (copiedTimer != null) window.clearTimeout(copiedTimer);
});
</script>

<template>
  <section v-if="visible" class="server-card" aria-label="服务器信息">
    <div class="server-card-head">
      <span class="server-logo" aria-hidden="true">
        <img v-if="logo" :src="logo" alt="" draggable="false" />
        <span v-else>{{ initial }}</span>
      </span>
      <div class="server-card-titles">
        <div class="server-card-name-row">
          <h2 class="server-card-name">{{ store.state.branding.productName }}</h2>
          <span v-if="version" class="server-version">{{ version }}</span>
          <!-- When the rail is short on room this takes the place of the status row below. -->
          <span
            v-if="status != null"
            class="server-inline-status"
            :class="'server-status-' + status.state.toLowerCase()"
          >
            <span class="server-status-dot" aria-hidden="true"></span>
            <template v-if="status.state === 'ONLINE'">
              <span v-if="status.onlinePlayers != null" class="server-inline-players">
                <b>{{ status.onlinePlayers }}</b><template v-if="status.maxPlayers != null"> / {{ status.maxPlayers }}</template>
              </span>
              <span v-if="status.latencyMs != null" class="server-inline-latency" :class="'latency-' + latencyTone">
                {{ status.latencyMs }} ms
              </span>
            </template>
            <span v-else>{{ status.state === "CHECKING" ? "检测中" : "连不上" }}</span>
          </span>
        </div>
        <p v-if="motdLines.length > 0" class="server-motd" :title="motdTitle">
          <span v-for="(line, lineIndex) in motdLines" :key="lineIndex" class="server-motd-line">
            <span
              v-for="(segment, index) in line"
              :key="index"
              :style="{
                color: segment.color ?? undefined,
                fontWeight: segment.bold ? 700 : undefined,
                fontStyle: segment.italic ? 'italic' : undefined,
                textDecoration: [segment.underline ? 'underline' : '', segment.strike ? 'line-through' : '']
                  .join(' ').trim() || undefined,
              }"
            >{{ segment.text }}</span>
          </span>
        </p>
      </div>
    </div>

    <button
      v-if="addressInCard"
      type="button"
      class="server-address"
      :class="{ copied: copied === ADDRESS_KEY }"
      :title="'复制服务器地址 ' + addressInCard"
      :aria-label="'复制服务器地址 ' + addressInCard"
      @click="copyAddress"
    >
      <span class="server-address-label">{{ copied === ADDRESS_KEY ? "已复制" : "地址" }}</span>
      <span class="server-address-value">{{ addressInCard }}</span>
      <AppIcon :name="copied === ADDRESS_KEY ? 'check' : 'copy'" />
    </button>

    <div v-if="status != null" class="server-status" :class="'server-status-' + status.state.toLowerCase()">
      <span class="server-status-dot" aria-hidden="true"></span>
      <template v-if="status.state === 'ONLINE'">
        <span class="server-status-label">在线</span>
        <span v-if="status.onlinePlayers != null" class="server-players">
          <b>{{ status.onlinePlayers }}</b><span v-if="status.maxPlayers != null"> / {{ status.maxPlayers }}</span> 人
        </span>
        <span class="server-status-spacer"></span>
        <span v-if="status.latencyMs != null" class="server-latency" :class="'latency-' + latencyTone">
          <AppIcon name="signal" />{{ status.latencyMs }} ms
        </span>
      </template>
      <template v-else>
        <span class="server-status-label">
          {{ status.state === "CHECKING" ? "正在检测服务器…" : "暂时连不上服务器" }}
        </span>
        <span class="server-status-spacer"></span>
      </template>
      <button
        v-if="status.state !== 'CHECKING'"
        type="button"
        class="server-refresh"
        aria-label="重新检测服务器状态"
        title="重新检测"
        @click="store.refreshServerStatus()"
      >
        <AppIcon name="retry" />
      </button>
    </div>
    <p v-else-if="store.state.preview && card?.showStatus !== false" class="server-status server-status-hint">
      {{ address ? "玩家打开时会显示在线人数和延迟" : "填写 Minecraft 服务器地址后，这里会显示在线人数和延迟" }}
    </p>

    <div v-if="links.length > 0" class="server-links" :style="{ '--link-count': links.length }">
      <button
        v-for="link in links"
        :key="link.id"
        type="button"
        class="server-link"
        :class="{ copied: copied === link.id }"
        :title="safeHttpUrl(link.url) ? link.url ?? '' : '复制 ' + (link.copyText ?? '')"
        @click="openLink(link)"
      >
        <AppIcon :name="copied === link.id ? 'check' : LINK_ICONS[link.kind] ?? 'link'" />
        {{ copied === link.id ? "已复制" : linkLabel(link) }}
      </button>
    </div>
  </section>
</template>
