<script setup lang="ts">
import { computed, ref } from 'vue';
import { ChevronRight } from '@lucide/vue';
import { desk, navigate } from './store';
import { nameOf } from './model';
const props=defineProps<{path:string}>();
const expanded=ref(true);
const children=computed(()=>desk.directories.filter(p=>p.startsWith(props.path+'/')&&!p.slice(props.path.length+1).includes('/')));
const count=computed(()=>desk.files.filter(f=>f.path.startsWith(props.path+'/')).length);
</script>
<template>
 <li class="folder-node"><div class="tree-row" :class="{current:desk.directory===path}">
  <button class="tree-expander" :aria-label="`${expanded?'收起':'展开'} ${path}`" :disabled="!children.length" @click="expanded=!expanded"><ChevronRight v-if="children.length" :size="12" :style="{transform:expanded?'rotate(90deg)':''}"/></button>
  <button class="tree-label" :aria-label="`打开文件夹 ${path}`" :title="path" @click="navigate(path)"><img src="/icons/folder.svg" alt=""><span>{{nameOf(path)}}</span><small>{{count}}</small></button>
 </div><ul v-if="expanded&&children.length"><FolderTree v-for="child in children" :key="child" :path="child"/></ul></li>
</template>
