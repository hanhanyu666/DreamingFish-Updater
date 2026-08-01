<script setup lang="ts">
import { computed } from "vue";
import { getBridge } from "../lib/bridge";
import { usePlayerStore } from "../stores/player";
import type { LocalModEntry } from "../lib/types";

const props = defineProps<{ entry: LocalModEntry }>();
const store = usePlayerStore();
const bridge = getBridge();

const detail = computed(() => {
  let source = props.entry.forced
    ? "服务器强制同步"
    : props.entry.managed
      ? "整合包管理"
      : "玩家添加";
  if (props.entry.disabled && !props.entry.forced) {
    source += props.entry.active ? " · 等待停用" : " · 已停用";
  }
  return source + "  ·  " + props.entry.path;
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
  <div class="mod-row">
    <div class="mod-labels" :title="entry.displayName">
      <div class="mod-name">{{ entry.displayName }}</div>
      <div class="mod-detail">{{ detail }}</div>
    </div>
    <label
      class="mod-toggle"
      :class="{ disabled: entry.forced }"
      :title="entry.forced ? '管理端强制同步目录中的模组不能在本机停用' : ''"
    >
      <input
        type="checkbox"
        :checked="entry.forced || !entry.disabled"
        :disabled="entry.forced"
        @change="onToggle"
      />
      <span>{{ entry.forced ? "强制启用" : "启用" }}</span>
    </label>
  </div>
</template>
