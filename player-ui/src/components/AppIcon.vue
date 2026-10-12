<script setup lang="ts">
import { ICON_PATHS, type IconName } from "../lib/icons";

defineProps<{ name: IconName }>();
</script>

<template>
  <svg class="app-icon" viewBox="0 0 20 20" aria-hidden="true" focusable="false">
    <path :d="ICON_PATHS[name]" />
  </svg>
</template>
