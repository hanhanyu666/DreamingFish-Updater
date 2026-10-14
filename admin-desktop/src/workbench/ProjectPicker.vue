<script setup lang="ts">
import { computed } from 'vue';
import { ChevronDown,Check,Plus } from '@lucide/vue';
import { desk,safely,switchProject } from './store';
defineProps<{locked:boolean;compact?:boolean}>();
const projects=computed(()=>desk.state.projects||[]);
const initial=(name='')=>[...name.trim()][0]?.toUpperCase()||'项';
// Stable, curated tints keep project avatars distinguishable without muddy colours.
const hues=[0,35,70,110,145,290],hue=(id='')=>({'--avatar-hue':`${hues[[...id].reduce((n,c)=>(n*31+c.charCodeAt(0))%997,7)%hues.length]}deg`});
function choose(id:string){desk.menu='';if(id!==desk.projectId)safely(()=>switchProject(id));}
</script>
<template>
 <div class="menu-anchor project-picker" :class="{compact}" data-menu-root>
  <button class="project-button" :disabled="locked" :title="locked?'任务进行时不能切换项目':'切换项目'" aria-haspopup="menu" :aria-expanded="desk.menu==='project'" @click="desk.menu=desk.menu==='project'?'':'project'"><span class="project-avatar" :style="hue(desk.projectId)">{{initial(desk.project?.displayName)}}</span><span class="project-name">{{desk.project?.displayName||'选择项目'}}</span><ChevronDown :size="14"/></button>
  <div v-if="desk.menu==='project'" class="menu project-menu" role="menu" aria-label="整合包项目"><span class="menu-label">整合包项目</span><button v-for="project in projects" :key="project.id" role="menuitemradio" :aria-checked="project.id===desk.projectId" @click="choose(project.id)"><span class="project-avatar" :style="hue(project.id)">{{initial(project.displayName)}}</span><span class="menu-copy"><strong>{{project.displayName}}</strong><small>{{project.id}} · {{project.latestRelease?.displayVersion?`当前 ${project.latestRelease.displayVersion}`:'尚未发布'}}</small></span><Check v-if="project.id===desk.projectId" :size="15" class="menu-check"/></button><hr><button role="menuitem" @click="desk.menu='';desk.dialog='project-create';desk.dialogData={}"><Plus :size="15"/>新建项目</button></div>
 </div>
</template>
