<script setup lang="ts">
import { computed } from 'vue';
import { Plus,FolderOpen,Layers,History,ShieldAlert } from '@lucide/vue';
import { desk,safely,openView,navigate,places,groups,publishState,latestVersion } from './store';
import { viewSections } from './presentation';
import FolderTree from './FolderTree.vue';
import ProjectPicker from './ProjectPicker.vue';
defineProps<{locked:boolean}>();
const states:Record<string,string>={'未发布':'draft','待检查':'check','待发布':'pending','已发布':'done'};
const sections=computed(()=>viewSections[desk.view]||[]);
const flat=computed(()=>Boolean(desk.search.trim()||desk.group||desk.filter!=='all'));
function root(){desk.filter='all';desk.group='';navigate('');}
function chooseGroup(id:string){desk.group=desk.group===id?'':id;desk.selection=[];}
</script>
<template>
 <aside class="sidebar" aria-label="项目导航">
  <div class="sidebar-head"><span>项目</span><button class="icon-button" aria-label="新建项目" title="新建项目" @click="desk.dialog='project-create';desk.dialogData={}"><Plus :size="15"/></button></div>
  <ProjectPicker :locked="locked"/>
  <button v-if="desk.project" class="project-state" :class="states[publishState]" :title="`${publishState} · 打开待发布改动`" @click="safely(()=>openView('changes','changes'))"><i/><span>{{desk.project.id}}</span><small>{{publishState}}</small></button>
  <div class="sidebar-scroll">
   <template v-if="desk.view==='files'&&desk.project">
    <button class="tree-root" :class="{current:!flat&&!desk.directory}" @click="root"><FolderOpen :size="16"/>整合包文件<small>{{desk.files.length}}</small></button>
    <ul class="folder-tree"><FolderTree v-for="item in places" :key="item.path" :path="item.path"/></ul>
    <div class="pane-label"><span>可选内容</span><button class="icon-button" aria-label="新建可选内容" title="新建可选内容" @click="desk.dialog='group';desk.dialogData={}"><Plus :size="14"/></button></div>
    <button v-for="item in groups" :key="item.id" class="pane-item" :class="{current:desk.group===item.id}" :title="item.description||item.title" @click="chooseGroup(item.id)"><Layers :size="15"/><span>{{item.title}}</span><small>{{item.count}}</small></button>
    <p v-if="!groups.length" class="pane-empty">把光影、小地图等整理成玩家可选的一组内容。</p>
    <div class="pane-label"><span>维护</span></div>
    <button class="pane-item" @click="safely(()=>openView('changes','operations'))"><History :size="15"/><span>操作记录</span></button>
    <button class="pane-item" @click="safely(()=>openView('changes','problems'))"><ShieldAlert :size="15"/><span>历史问题文件</span></button>
   </template>
   <template v-else>
    <button v-for="item in sections" :key="item.id" class="pane-item" :class="{current:desk.section===item.id}" :aria-current="desk.section===item.id?'page':undefined" @click="safely(()=>openView(desk.view,item.id))"><span>{{item.label}}</span></button>
   </template>
  </div>
  <footer v-if="desk.project" class="sidebar-foot"><span>当前发布</span><strong>{{latestVersion||'尚未发布'}}</strong></footer>
 </aside>
</template>
