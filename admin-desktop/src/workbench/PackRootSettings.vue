<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {FolderOpen,Folder,File,ChevronDown,MapPin} from '@lucide/vue';
import {desk,request,apiPath,operation,safely,loadProject,openView,navigate,notify} from './store';
import {packRootPreview,relativeRootForLevels,defaultLayout,parseLayout} from './pack-root-preview';
const relativeRoot=ref('../../..'),layoutText=ref(''),layoutEdited=ref(false);
const preview=computed(()=>packRootPreview(relativeRoot.value));
const locked=computed(()=>Boolean(desk.project?.packRoot));
const layout=computed(()=>parseLayout(layoutText.value,preview.value.levels));
watch(()=>desk.project?.packRoot,root=>{relativeRoot.value=root?.relativeRoot||'../../..';layoutEdited.value=false;layoutText.value=(root?.layout||defaultLayout(preview.value.levels||3)).join('/');},{immediate:true});
watch(()=>preview.value.levels,levels=>{if(!locked.value&&!layoutEdited.value&&levels)layoutText.value=defaultLayout(levels).join('/');});
async function save(){if(!preview.value.relativeRoot)throw new Error('请选择向上 1 至 8 级');if(!layout.value)throw new Error(`目录结构需要填写 ${preview.value.levels} 个文件夹名称，用 / 分隔`);await operation(async()=>{await request(apiPath('/pack-root'),'POST',{relativeRoot:preview.value.relativeRoot,expectedId:desk.project?.packRoot?.id||'',layout:layout.value});await loadProject();desk.dialog='';await files();notify('外层目录已启用。导入要更新的文件，检查并发布后，玩家下次启动时生效');});}
async function files(){const path=desk.project?.packRoot?.id?`@pack/${desk.project.packRoot.id}`:'';desk.dialog='';if(desk.view!=='files')await openView('files','files');navigate(path);}
</script>
<template>
 <div class="pack-root-settings">
  <p class="field-help">用来更新启动器旁的教程、背景等文件。只更新你在“外层目录”里导入的文件，不会把整个上级目录纳入同步；mods 和 config 仍在游戏实例里更新。</p>
  <div v-if="locked" class="notice-card compact"><MapPin :size="18" aria-hidden="true"/><div><strong>已启用 · 位置固定</strong><p>为了让历史备份恢复到原来的位置，启用后不能直接更换目录。添加或修改外层文件，请点击“管理外层文件”。</p></div><button @click="safely(files)">管理外层文件</button></div>
  <div class="pack-root-position"><label>从游戏实例向上几级<div class="select-capsule wide"><select :value="preview.levels??''" :disabled="locked" @change="relativeRoot=relativeRootForLevels(Number(($event.target as HTMLSelectElement).value))"><option value="" disabled>请选择</option><option v-for="level in 8" :key="level" :value="level">向上 {{level}} 级</option></select><ChevronDown :size="14" aria-hidden="true"/></div></label><p v-if="preview.levels" class="pack-root-result" role="status"><MapPin :size="18" aria-hidden="true"/><span>示例中的更新位置<strong>{{preview.rootName}}</strong><code>{{preview.relativeRoot}}</code></span></p></div>
  <div class="pack-root-diagram" role="group" aria-label="更新目录位置示意"><p class="pack-tree-caption">{{locked?'目录示例 · 已保存的更新位置（只读）':'目录示例 · 点击文件夹选择更新位置'}}</p><ol class="pack-tree"><li v-for="row in preview.rows" :key="row.id" :style="{'--depth':row.depth}"><component :is="row.levels&&row.levels>0?'button':'div'" :type="row.levels&&row.levels>0?'button':undefined" class="pack-tree-node" :class="{selected:row.selected,origin:row.origin}" :disabled="row.levels&&row.levels>0?locked:undefined" :aria-pressed="row.levels&&row.levels>0?row.selected:undefined" :aria-label="row.levels&&row.levels>0?`选择 ${row.name}，从游戏实例向上 ${row.levels} 级`:undefined" @click="!locked&&row.levels&&row.levels>0&&(relativeRoot=relativeRootForLevels(row.levels))"><span class="pack-tree-branch" aria-hidden="true">{{row.branch}}</span><File v-if="row.id==='launcher'" :size="17" aria-hidden="true"/><FolderOpen v-else-if="row.selected||row.origin" :size="18" aria-hidden="true"/><Folder v-else :size="18" aria-hidden="true"/><span class="pack-tree-name">{{row.name}}</span><span v-if="row.selected" class="pack-tree-badge">更新根目录</span><span v-if="row.origin" class="pack-tree-badge origin">游戏实例 · 从这里出发</span></component></li></ol></div>
  <p class="field-help">上图以“整合包/.minecraft/versions/游戏版本”为例，不是玩家电脑的实际文件列表。请按玩家整合包的真实层级核对。</p>
  <details class="disclosure-inline pack-root-advanced"><summary>{{locked?'查看路径和目录结构':'直接填写路径和目录结构'}}</summary>
   <label>从游戏实例到更新根目录<input v-model="relativeRoot" placeholder="../../.." :readonly="locked" :aria-invalid="preview.levels===null" aria-describedby="pack-root-path-help"></label><p id="pack-root-path-help" class="field-help" :class="{'error-copy':preview.levels===null}">../ 表示向上一级，../../.. 表示向上三级。只支持上方 1 至 8 级，不填写服务器的绝对路径。</p>
   <label>玩家的目录结构<input v-model="layoutText" :readonly="locked" :aria-invalid="layout===null" aria-describedby="pack-root-layout-help" spellcheck="false" @input="layoutEdited=true"></label><p id="pack-root-layout-help" class="field-help" :class="{'error-copy':layout===null}">从更新根目录往下到游戏实例的每一层文件夹名称，* 表示任意名称。玩家端会先核对，一致才更新外层文件；不一致时只跳过外层文件，模组照常更新。</p>
  </details>
  <template v-if="!locked"><p class="field-help">启用并发布后，已有玩家下次启动时自动开始更新外层文件，不需要重新分发整合包或替换文件。需要玩家端 0.3.3 或更新版本；启用后位置固定，请先核对层级。</p><div class="form-actions"><button class="accent" :disabled="desk.busy||preview.levels===null||layout===null" @click="safely(save)">启用外层目录</button></div></template>
  <details v-else class="disclosure-inline"><summary>管理端存放外层文件的位置</summary><p class="field-help">{{desk.project?.packRootSourceDirectory}}</p></details>
 </div>
</template>
