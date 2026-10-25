<script setup lang="ts">
import { ref } from "vue";
import { useRailFit } from "../lib/railFit";
import EventCard from "./EventCard.vue";
import ServerCard from "./ServerCard.vue";
import UpdateArea from "./UpdateArea.vue";

const rail = ref<HTMLElement | null>(null);
useRailFit(rail);
</script>

<template>
  <!-- Frosted cards down the right: the server on top, the update at the bottom. -->
  <div ref="rail" class="home-rail">
    <ServerCard />
    <EventCard />
    <UpdateArea />
  </div>
</template>
