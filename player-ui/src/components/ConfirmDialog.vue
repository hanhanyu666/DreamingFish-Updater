<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { usePlayerStore } from "../stores/player";

const store = usePlayerStore();
const request = computed(() => store.state.confirm);
const pane = ref<HTMLElement | null>(null);
const cancelButton = ref<HTMLButtonElement | null>(null);
let previouslyFocused: HTMLElement | null = null;

function accept(): void {
  store.answerConfirm(true);
}

function cancel(): void {
  store.answerConfirm(false);
}

function restoreFocus(): void {
  if (previouslyFocused?.isConnected) previouslyFocused.focus();
  previouslyFocused = null;
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    cancel();
    return;
  }
  if (event.key !== "Tab" || pane.value == null) return;
  const focusable = Array.from(
    pane.value.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  );
  if (focusable.length === 0) {
    event.preventDefault();
    pane.value.focus();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

watch(request, async (next, previous) => {
  if (next != null) {
    if (previous == null) previouslyFocused = document.activeElement as HTMLElement | null;
    await nextTick();
    cancelButton.value?.focus();
  } else if (previous != null) {
    await nextTick();
    restoreFocus();
  }
});

onBeforeUnmount(restoreFocus);
</script>

<template>
  <div
    v-if="request"
    class="dfs-dialog-backdrop"
    @mousedown.self="cancel"
  >
    <div
      ref="pane"
      class="dfs-dialog-pane"
      :class="'dfs-dialog-' + request.tone.toLowerCase()"
      role="dialog"
      aria-modal="true"
      aria-labelledby="dfs-confirm-heading"
      aria-describedby="dfs-confirm-message"
      tabindex="-1"
      @keydown="onDialogKeydown"
    >
      <div class="dfs-dialog-header">
        <div class="dfs-dialog-accent" aria-hidden="true"></div>
        <div class="dfs-dialog-title">{{ request.title }}</div>
        <div id="dfs-confirm-heading" class="dfs-dialog-heading">{{ request.heading }}</div>
      </div>
      <div id="dfs-confirm-message" class="dfs-dialog-message">{{ request.message }}</div>
      <div class="dfs-dialog-actions">
        <button ref="cancelButton" class="dfs-dialog-secondary" type="button" @click="cancel">
          {{ request.cancelText }}
        </button>
        <button class="dfs-dialog-primary" type="button" @click="accept">
          {{ request.actionText }}
        </button>
      </div>
    </div>
  </div>
</template>
