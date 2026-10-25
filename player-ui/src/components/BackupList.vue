<script setup lang="ts">
import { formatBytes, formatHistoryTime } from "../lib/format";
import { archiveTitle } from "../lib/maintenance";
import type { ArchivedFileDto } from "../lib/types";
import { usePlayerStore } from "../stores/player";
import AppIcon from "./AppIcon.vue";

const store = usePlayerStore();

function reasonLine(file: ArchivedFileDto): string {
  const parts = [file.reasonText];
  if (file.version) parts.push("版本 " + file.version);
  // Cleanup and duplicate details are paths; the owner's withdrawal or fix reason is worth showing.
  if (file.detail && file.reason !== "CLEANUP" && file.reason !== "DUPLICATE") {
    parts.push(file.detail);
  }
  return parts.join("  ·  ");
}
</script>

<template>
  <div class="backup-page">
    <div v-if="!store.state.archivesLoaded" class="drawer-empty">正在读取备份…</div>
    <div v-else-if="store.state.archives.length === 0" class="drawer-empty">
      还没有备份。更新需要移走你的文件时，会先在这里保存一份。
    </div>
    <div v-else class="backup-list">
      <section v-for="archive in store.state.archives" :key="archive.id" class="backup-card">
        <div class="backup-header">
          <span class="backup-icon" aria-hidden="true"><AppIcon name="archive" /></span>
          <div class="backup-heading">
            <strong>{{ archiveTitle(archive) }}</strong>
            <span>
              {{ formatHistoryTime(archive.createdAt ?? "") }} · {{ archive.files.length }} 个文件 ·
              {{ formatBytes(archive.totalBytes) }}
            </span>
          </div>
          <button type="button" class="backup-action" @click="store.openArchive(archive.id)">打开</button>
          <button type="button" class="backup-action danger" @click="store.deleteArchive(archive.id)">
            删除
          </button>
        </div>
        <div v-for="file in archive.files" :key="file.path" class="backup-file">
          <div class="backup-file-labels">
            <div class="backup-file-path" :title="file.path">{{ file.path }}</div>
            <div class="backup-file-reason" :title="reasonLine(file)">{{ reasonLine(file) }}</div>
          </div>
          <span v-if="file.restoredAt" class="backup-restored">
            已放回 · {{ formatHistoryTime(file.restoredAt) }}
          </span>
          <button
            v-else
            type="button"
            class="backup-action"
            @click="store.restoreArchivedFile(archive.id, file.path)"
          >
            放回游戏
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
