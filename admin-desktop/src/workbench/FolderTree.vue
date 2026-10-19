<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ChevronRight } from '@lucide/vue';
import { desk, navigate,fileIndex,pendingCounts } from './store';
import { nameOf } from './model';
import FileIcon from './FileIcon.vue';
const props=defineProps<{path:string}>();
// Folders open along the current directory so the tree always shows where the list is.
const inside=()=>desk.directory.startsWith(props.path+'/');
const expanded=ref(desk.directory===props.path||inside());
watch(()=>desk.directory,()=>{if(inside())expanded.value=true;});
const children=computed(()=>(fileIndex.value.rows.get(props.path)||[]).filter(entry=>entry.directory).map(entry=>entry.path));
const count=computed(()=>fileIndex.value.folders.get(props.path)?.count||0);
const pending=computed(()=>(pendingCounts.value.get(props.path)||0)>0);
const current=computed(()=>desk.directory===props.path&&!desk.group&&!desk.search.trim()&&desk.filter==='all');
function open(){desk.filter='all';navigate(props.path);}
</script>
<template>
 <li class="folder-node"><div class="tree-row" :class="{current}">
  <button class="tree-expander" :aria-label="`${expanded?'收起':'展开'} ${path}`" :aria-expanded="expanded" :disabled="!children.length" @click="expanded=!expanded"><ChevronRight v-if="children.length" :size="13" :style="{transform:expanded?'rotate(90deg)':''}"/></button>
  <button class="tree-label" :aria-label="`打开文件夹 ${path}`" :aria-current="current?'true':undefined" :title="path" @click="open"><FileIcon directory :open="current" plain :size="17"/><span>{{nameOf(path)}}</span><i v-if="pending" class="tree-pending" title="有待发布的改动"/><small>{{count}}</small></button>
 </div><ul v-if="expanded&&children.length"><FolderTree v-for="child in children" :key="child" :path="child"/></ul></li>
</template>
