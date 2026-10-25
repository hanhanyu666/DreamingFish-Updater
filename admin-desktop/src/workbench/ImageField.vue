<script setup lang="ts">
import { computed } from 'vue';
import { ChevronDown } from '@lucide/vue';
import type {Data} from './model';
// Picks an uploaded showcase image or a public address for one image slot.
const props=defineProps<{target:Data;images:Data[];label?:string;fileKey?:string;noUrl?:boolean;emptyLabel?:string}>();
const key=computed(()=>props.fileKey||'imageFile');
// A name from an imported file may not be uploaded yet; keep it selectable so it is not lost.
const missing=computed(()=>{const name=props.target[key.value];return name&&!props.images.some(i=>i.fileName===name)?String(name):'';});
function pick(event:Event){props.target[key.value]=(event.target as HTMLSelectElement).value||null;}
</script>
<template><div class="image-field" :class="{'single':noUrl}"><label>{{label||'图片'}}<div class="select-capsule wide"><select :value="target[key]||''" :aria-label="(label||'图片')+'文件'" @change="pick"><option value="">{{emptyLabel||'不使用上传图片'}}</option><option v-if="missing" :value="missing">{{missing}}（尚未上传）</option><option v-for="image in images" :key="image.fileName" :value="image.fileName">{{image.fileName}}</option></select><ChevronDown :size="13"/></div></label><label v-if="!noUrl">或公开图片地址<input v-model="target.imageUrl" type="url" placeholder="https://" :aria-label="(label||'图片')+'地址'"></label></div></template>
