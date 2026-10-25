<script setup lang="ts">
import { computed } from "vue";
import { getBridge } from "../lib/bridge";
import { modSourceText, modToggleLabel } from "../lib/maintenance";
import { usePlayerStore } from "../stores/player";
import type { LocalModEntry } from "../lib/types";
import AppIcon from "./AppIcon.vue";

const props = defineProps<{ entry: LocalModEntry }>();
const store = usePlayerStore();
const bridge = getBridge();

const locked = computed(() => props.entry.forced && props.entry.lockReason != null);
const detail = computed(() =>
  [modSourceText(props.entry, !locked.value), props.entry.path]
    .filter((part) => part.length > 0).join(" · "));
const lockTitle = computed(() =>
  props.entry.forced
    ? props.entry.lockReason ?? "服主设为强制同步，不能在本机停用"
    : "",
);
/** The tile colour says where the mod comes from, as in the design: required, optional group, pack or player. */
const source = computed(() => {
  if (!props.entry.managed) return "player";
  if (props.entry.forced) return props.entry.group ? "group" : "required";
  return "pack";
});
const initial = computed(() => {
  const letters = props.entry.displayName.replace(/[^A-Za-z]/g, "");
  return letters.length > 0 ? letters.slice(0, 2) : Array.from(props.entry.displayName.trim())[0] ?? "?";
});

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
  <div class="mod-row" :class="{ withdrawn: entry.withdrawnReason, off: entry.disabled }">
    <span class="mod-tile" :class="'mod-tile-' + source" aria-hidden="true">{{ initial }}</span>
    <div class="mod-labels" :title="entry.displayName">
      <div class="mod-name-row">
        <strong class="mod-name">{{ entry.displayName }}</strong>
        <span v-if="entry.version" class="mod-version">{{ entry.version }}</span>
      </div>
      <div class="mod-detail">{{ detail }}</div>
      <div v-if="entry.withdrawnReason" class="mod-withdrawn">
        服主撤回了这个版本：{{ entry.withdrawnReason }}
      </div>
      <div v-else-if="entry.forced && entry.lockReason" class="mod-lock"><AppIcon name="lock" />{{ entry.lockReason }}</div>
    </div>
    <label class="mod-toggle" :class="{ disabled: entry.forced }" :title="lockTitle">
      <span>{{ modToggleLabel(entry) }}</span>
      <input
        type="checkbox"
        class="dfs-switch"
        role="switch"
        :checked="!entry.disabled"
        :disabled="entry.forced"
        :aria-label="'启用 ' + entry.displayName"
        @change="onToggle"
      />
    </label>
  </div>
</template>
