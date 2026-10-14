<script setup lang="ts">
import { computed } from 'vue';
import { Folder,FolderOpen,Blocks,FileArchive,FileCog,Braces,Image,Database,Music,FileText } from '@lucide/vue';
// Files get a tinted tile by kind, so mods, configs and packs can be told apart at a glance.
const props=withDefaults(defineProps<{name?:string;directory?:boolean;open?:boolean;size?:number;plain?:boolean}>(),{name:'',size:30});
const kinds:[RegExp,string,any][]=[
 [/\.jar$/i,'mod',Blocks],[/\.(zip|rar|7z)$/i,'pack',FileArchive],[/\.(toml|json5?|cfg|conf|properties|ini|ya?ml|txt|snbt|options)$/i,'config',FileCog],
 [/\.(js|ts|zs|lua|mcfunction|groovy)$/i,'script',Braces],[/\.(png|jpe?g|webp|gif|svg)$/i,'image',Image],[/\.(dat|nbt|db|mca)$/i,'data',Database],[/\.(ogg|mp3|wav|flac)$/i,'audio',Music],
];
const kind=computed(()=>props.directory?{tone:'folder',icon:props.open?FolderOpen:Folder}:(([,tone,icon])=>({tone,icon}))(kinds.find(([pattern])=>pattern.test(props.name))||[null,'file',FileText]));
</script>
<template>
 <component :is="kind.icon" v-if="plain" class="file-glyph" :class="`tone-${kind.tone}`" :size="size" aria-hidden="true"/>
 <span v-else class="file-icon" :class="`tone-${kind.tone}`" :style="{width:`${size}px`,height:`${size}px`}" aria-hidden="true"><component :is="kind.icon" :size="Math.round(size*.54)"/></span>
</template>
