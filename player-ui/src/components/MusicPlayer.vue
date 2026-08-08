<script setup lang="ts">
import { computed, ref } from "vue";
import { usePlayerStore } from "../stores/player";

const store = usePlayerStore();
const expanded = ref(false);

const hasMusic = computed(() =>
  store.state.musicTracks.length > 0 || store.state.startupMusicUrl != null,
);

const selectedTrack = computed(() =>
  store.state.musicTracks.find((track) => track.id === store.state.selectedMusicTrackId)
    ?? store.state.musicTracks[0]
    ?? null,
);

function toggleExpanded(): void {
  expanded.value = !expanded.value;
}

function togglePlayback(): void {
  store.toggleStartupMusic();
}

function selectTrack(event: Event): void {
  const value = (event.target as HTMLSelectElement).value;
  void store.selectMusicTrack(value);
}

function toggleLoop(): void {
  store.toggleMusicLoop();
}
</script>

<template>
  <section
    v-if="hasMusic"
    class="music-player"
    :class="{ expanded }"
    aria-label="音乐播放器"
    @click.stop
  >
    <button
      v-if="!expanded"
      type="button"
      class="music-player-collapsed"
      aria-label="展开音乐播放器"
      title="展开音乐播放器"
      @click="toggleExpanded"
    >
      <span class="music-player-note" aria-hidden="true">{{ store.state.musicPlaying ? '♪' : '♫' }}</span>
      <span class="music-player-collapsed-copy">
        <span class="music-player-collapsed-label">音乐</span>
        <span class="music-player-collapsed-track" :title="selectedTrack?.title ?? '启动音乐'">
          {{ selectedTrack?.title ?? '启动音乐' }}
        </span>
      </span>
      <span class="music-player-expand-glyph" aria-hidden="true">⌃</span>
      <span v-if="store.state.musicPlaying" class="music-player-pulse" aria-hidden="true"></span>
    </button>

    <div v-else class="music-player-expanded">
      <div class="music-player-heading">
        <span class="music-player-heading-icon" aria-hidden="true">♫</span>
        <div class="music-player-copy">
          <span class="music-player-label">正在播放</span>
          <span class="music-player-track" :title="selectedTrack?.title ?? '启动音乐'">
            {{ selectedTrack?.title ?? '启动音乐' }}
          </span>
        </div>
        <button
          type="button"
          class="music-player-collapse"
          aria-label="收起音乐播放器"
          title="收起"
          @click="expanded = false"
        >×</button>
      </div>

      <select
        v-if="store.state.musicTracks.length > 0"
        class="music-player-select"
        :value="store.state.selectedMusicTrackId ?? ''"
        aria-label="选择音乐"
        @change="selectTrack"
      >
        <option v-for="track in store.state.musicTracks" :key="track.id" :value="track.id">
          {{ track.title }}
        </option>
      </select>

      <div class="music-player-actions">
        <button
          type="button"
          class="music-player-action music-player-play"
          :aria-label="store.state.musicPlaying ? '暂停音乐' : '播放音乐'"
          :title="store.state.musicPlaying ? '暂停音乐' : '播放音乐'"
          :aria-pressed="store.state.musicPlaying"
          @click="togglePlayback"
        >
          <span aria-hidden="true">{{ store.state.musicPlaying ? 'Ⅱ' : '▶' }}</span>
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
        >↻</button>
      </div>
    </div>
  </section>
</template>
