<script setup lang="ts">
import { computed } from "vue";
import { usePlayerStore } from "../stores/player";

const store = usePlayerStore();
const request = computed(() => store.state.confirm);

function accept(): void {
  store.answerConfirm(true);
}

function cancel(): void {
  store.answerConfirm(false);
}
</script>

<template>
  <div
    v-if="request"
    class="dfs-dialog-backdrop"
    @mousedown.self="cancel"
  >
    <div
      class="dfs-dialog-pane"
      :class="'dfs-dialog-' + request.tone.toLowerCase()"
      role="dialog"
      aria-modal="true"
    >
      <div class="dfs-dialog-accent"></div>
      <div class="dfs-dialog-title">{{ request.title }}</div>
      <div class="dfs-dialog-heading">{{ request.heading }}</div>
      <div class="dfs-dialog-message">{{ request.message }}</div>
      <div class="dfs-dialog-actions">
        <button class="dfs-dialog-secondary" type="button" @click="cancel">
          {{ request.cancelText }}
        </button>
        <button class="dfs-dialog-primary" type="button" @click="accept">
          {{ request.actionText }}
        </button>
      </div>
    </div>
  </div>
</template>
