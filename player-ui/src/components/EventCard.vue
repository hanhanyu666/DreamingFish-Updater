<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { countdownLabel, eventTimeLabel, nextEvent, usableAction } from "../lib/showcase";
import { usePlayerStore } from "../stores/player";
import AppIcon from "./AppIcon.vue";

const store = usePlayerStore();
const now = ref(Date.now());
let timer: number | null = null;

onMounted(() => {
  timer = window.setInterval(() => {
    now.value = Date.now();
  }, 1000);
});

onBeforeUnmount(() => {
  if (timer != null) window.clearInterval(timer);
});

const upcoming = computed(() => nextEvent(store.state.branding.events, now.value));
const action = computed(() =>
  upcoming.value == null ? null : usableAction(upcoming.value.event.action, store.state.contentPages),
);

function open(): void {
  if (action.value != null) store.followLink(action.value);
}
</script>

<template>
  <component
    :is="action != null ? 'button' : 'section'"
    v-if="upcoming != null"
    :type="action != null ? 'button' : undefined"
    class="event-card"
    :class="{ 'event-card-action': action != null, ongoing: upcoming.ongoing }"
    :aria-label="action != null ? upcoming.event.title + '，' + action.label : undefined"
    @click="open"
  >
    <span class="event-card-icon" aria-hidden="true"><AppIcon name="calendar" /></span>
    <span class="event-card-copy">
      <span class="event-card-label">
        {{ upcoming.ongoing ? "正在进行" : "下一场活动" }} ·
        <time :datetime="upcoming.startsAt.toISOString()">{{ eventTimeLabel(upcoming.startsAt) }}</time>
      </span>
      <span class="event-card-title">{{ upcoming.event.title }}</span>
    </span>
    <span class="event-countdown">
      {{ upcoming.ongoing ? "进行中" : countdownLabel(upcoming.startsAt.getTime() - now) }}
    </span>
  </component>
</template>
