<script setup lang="ts">
import { computed } from "vue";
import { getBridge } from "../lib/bridge";
import { modSourceText, modToggleLabel } from "../lib/maintenance";
import { usePlayerStore } from "../stores/player";
import type { LocalModEntry } from "../lib/types";

const props = defineProps<{ entry: LocalModEntry }>();
const store = usePlayerStore();
const bridge = getBridge();

const locked = computed(() => props.entry.forced && props.entry.lockReason != null);
const detail = computed(() =>
  [modSourceText(props.entry, !locked.value), props.entry.path]
    .filter((part) => part.length > 0).join("  ·  "));
const lockTitle = computed(() =>
  props.entry.forced
    ? props.entry.lockReason ?? "服主设为强制同步，不能在本机停用"
    : "",
);

async function onToggle(event: Event): Promise<void> {
  const checked = (event.target as HTMLInputElement).checked;
  if (!checked && !props.entry.forced) {
    const accepted = await store.confirmDisableMod(props.entry);
    if (!accepted) {
      (event.target as HTMLInputElement).checked = true;
      return;
    }
  }
  bridge.sendCommand({ command: "toggle-mod", entry: props.entry, disabled: !checked });
}
</script>

<template>
  <div class="mod-row" :class="{ withdrawn: entry.withdrawnReason }">
    <div class="mod-labels" :title="entry.displayName">
      <div class="mod-name">{{ entry.displayName }}</div>
      <div class="mod-detail">{{ detail }}</div>
      <div v-if="entry.withdrawnReason" class="mod-withdrawn">
        服主撤回了这个版本：{{ entry.withdrawnReason }}
      </div>
      <div v-else-if="entry.forced && entry.lockReason" class="mod-lock">{{ entry.lockReason }}</div>
    </div>
    <label class="mod-toggle" :class="{ disabled: entry.forced }" :title="lockTitle">
      <input
        type="checkbox"
        :checked="!entry.disabled"
        :disabled="entry.forced"
        @change="onToggle"
      />
      <span>{{ modToggleLabel(entry) }}</span>
    </label>
  </div>
</template>
