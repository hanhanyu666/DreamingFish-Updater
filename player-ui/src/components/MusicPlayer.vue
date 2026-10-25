<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { usePlayerStore } from "../stores/player";

const store = usePlayerStore();
const expanded = ref(false);
const root = ref<HTMLElement | null>(null);

const hasMusic = computed(() =>
  store.state.musicTracks.length > 0 || store.state.startupMusicUrl != null,
);

const selectedTrack = computed(() =>
  store.state.musicTracks.find((track) => track.id === store.state.selectedMusicTrackId)
    ?? store.state.musicTracks[0]
    ?? null,
);

const selectedTrackLabel = computed(() => trackLabel(selectedTrack.value));

function trackLabel(track: { title: string; fileName: string } | null): string {
  if (track == null) return "startup-music.mp3";
  return track.title.trim() === "启动音乐" ? track.fileName : track.title;
}

function toggleExpanded(): void {
  expanded.value = !expanded.value;
}

function togglePlayback(): void {
  store.toggleStartupMusic();
}

function pickTrack(id: string): void {
  if (id === store.state.selectedMusicTrackId) {
    togglePlayback();
    return;
  }
  void store.selectMusicTrack(id);
}

function toggleLoop(): void {
  store.toggleMusicLoop();
}

/** The window closes on a click anywhere else or on Escape, like a menu. */
function onPointerDown(event: PointerEvent): void {
  if (root.value != null && !root.value.contains(event.target as Node)) expanded.value = false;
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") expanded.value = false;
}

watch(expanded, (open) => {
  if (open) {
    window.addEventListener("pointerdown", onPointerDown, true);
    window.addEventListener("keydown", onKeydown);
  } else {
    window.removeEventListener("pointerdown", onPointerDown, true);
    window.removeEventListener("keydown", onKeydown);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("pointerdown", onPointerDown, true);
  window.removeEventListener("keydown", onKeydown);
});
</script>

<template>
  <section
    v-if="hasMusic"
    ref="root"
    class="music-player"
    :class="{ expanded }"
    aria-label="音乐播放器"
    @click.stop
  >
    <div class="music-player-pill">
      <button
        type="button"
        class="music-player-collapsed"
        :aria-expanded="expanded"
        :aria-label="expanded ? '收起音乐播放器' : '展开音乐播放器'"
        :title="selectedTrackLabel"
        @click="toggleExpanded"
      >
        <span class="music-player-eq" :class="{ playing: store.state.musicPlaying }" aria-hidden="true">
          <span></span><span></span><span></span><span></span>
        </span>
        <span class="music-player-collapsed-track">{{ selectedTrackLabel }}</span>
      </button>
      <button
        type="button"
        class="music-player-toggle"
        :aria-label="(store.state.musicPlaying ? '暂停音乐：' : '播放音乐：') + selectedTrackLabel"
        :title="store.state.musicPlaying ? '暂停' : '播放'"
        @click="togglePlayback"
      >
        <svg v-if="store.state.musicPlaying" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6v12M15 6v12" /></svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true" class="filled"><path d="M8 5.5v13l10.5-6.5z" /></svg>
      </button>
    </div>

    <div v-if="expanded" class="music-player-expanded" role="dialog" aria-label="音乐">
      <div class="music-player-heading">
        <span class="music-player-heading-icon" :class="{ playing: store.state.musicPlaying }" aria-hidden="true">
          <span class="music-player-eq" :class="{ playing: store.state.musicPlaying }">
            <span></span><span></span><span></span><span></span>
          </span>
        </span>
        <div class="music-player-copy">
          <span class="music-player-label">{{ store.state.musicPlaying ? "正在播放" : "已暂停" }}</span>
          <span class="music-player-track" :title="selectedTrackLabel">
            {{ selectedTrackLabel }}
          </span>
        </div>
        <button
          type="button"
          class="music-player-collapse"
          aria-label="收起音乐播放器"
          title="收起"
          @click="expanded = false"
        >
          <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M2.5 2.5l7 7M9.5 2.5l-7 7" /></svg>
        </button>
      </div>

      <ol v-if="store.state.musicTracks.length > 1" class="music-player-list" aria-label="选择音乐">
        <li v-for="(track, index) in store.state.musicTracks" :key="track.id">
          <button
            type="button"
            class="music-player-track-option"
            :class="{ current: track.id === selectedTrack?.id }"
            :aria-pressed="track.id === selectedTrack?.id"
            :title="trackLabel(track)"
            @click="pickTrack(track.id)"
          >
            <span class="music-player-track-index">
              <span
                v-if="track.id === selectedTrack?.id"
                class="music-player-eq"
                :class="{ playing: store.state.musicPlaying }"
                aria-hidden="true"
              ><span></span><span></span><span></span></span>
              <template v-else>{{ String(index + 1).padStart(2, "0") }}</template>
            </span>
            <span class="music-player-track-name">{{ trackLabel(track) }}</span>
          </button>
        </li>
      </ol>

      <div class="music-player-actions">
        <button
          type="button"
          class="music-player-action music-player-play"
          :aria-label="store.state.musicPlaying ? '暂停音乐' : '播放音乐'"
          :title="store.state.musicPlaying ? '暂停音乐' : '播放音乐'"
          :aria-pressed="store.state.musicPlaying"
          @click="togglePlayback"
        >
          <svg v-if="store.state.musicPlaying" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6v12M15 6v12" /></svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true" class="filled"><path d="M8 5.5v13l10.5-6.5z" /></svg>
          {{ store.state.musicPlaying ? "暂停" : "播放" }}
        </button>
        <button
          v-if="store.state.musicTracks.length > 0"
          type="button"
          class="music-player-action"
          :class="{ active: store.state.musicLoop }"
          :aria-label="store.state.musicLoop ? '关闭循环播放' : '开启循环播放'"
          :title="store.state.musicLoop ? '循环播放：开启' : '循环播放：关闭'"
          :aria-pressed="store.state.musicLoop"
          @click="toggleLoop"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M17 2.5 20.5 6 17 9.5" /><path d="M3.5 11V9a3 3 0 0 1 3-3h14" /><path d="M7 21.5 3.5 18 7 14.5" /><path d="M20.5 13v2a3 3 0 0 1-3 3h-14" /></svg>
          循环
        </button>
      </div>
    </div>
  </section>
</template>
