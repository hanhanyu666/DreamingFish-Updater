<script setup lang="ts">
import { computed } from "vue";
import { getBridge } from "../lib/bridge";
import { markdownClickHandler, renderMarkdown } from "../lib/markdown";

const props = defineProps<{ markdown: string }>();
const bridge = getBridge();

const html = computed(() => renderMarkdown(props.markdown));

function onClick(event: MouseEvent): void {
  markdownClickHandler(event, { openExternalLink: (uri) => bridge.openExternal(uri) });
}
</script>

<template>
  <div class="markdown-body" v-html="html" @click="onClick"></div>
</template>
