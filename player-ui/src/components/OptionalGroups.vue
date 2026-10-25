<script setup lang="ts">
import { groupStateText } from "../lib/maintenance";
import type { OptionalGroupView } from "../lib/types";
import { usePlayerStore } from "../stores/player";

const store = usePlayerStore();

function toggle(group: Pick<OptionalGroupView, "id">, event: Event): void {
  store.setGroupChoice(group, (event.target as HTMLInputElement).checked);
}
</script>

<template>
  <div class="optional-page">
    <div v-if="store.state.groups.length === 0" class="drawer-empty">这个整合包没有可选内容</div>
    <div v-else class="optional-list">
      <article
        v-for="group in store.state.groups"
        :key="group.id"
        class="optional-card"
        :class="{ off: !group.enabled }"
      >
        <div class="optional-card-header">
          <div class="optional-card-title">
            <strong>{{ group.title }}</strong>
            <span class="optional-state">{{ groupStateText(group) }}</span>
          </div>
          <label class="optional-switch" :title="group.enabled ? '关闭这组内容' : '开启这组内容'">
            <input
              type="checkbox"
              :checked="group.enabled"
              :aria-label="'开启 ' + group.title"
              @change="toggle(group, $event)"
            />
            <span class="optional-switch-track"></span>
          </label>
        </div>
        <p v-if="group.description" class="optional-description">{{ group.description }}</p>
        <div v-if="group.members.length > 0" class="optional-members">
          <span v-for="member in group.members" :key="member" class="optional-member">{{ member }}</span>
        </div>
        <button
          v-if="group.explicit"
          type="button"
          class="optional-reset"
          @click="store.setGroupChoice(group, null)"
        >
          恢复服主默认（{{ group.defaultInstall ? "开启" : "关闭" }}）
        </button>
      </article>
    </div>
  </div>
</template>
