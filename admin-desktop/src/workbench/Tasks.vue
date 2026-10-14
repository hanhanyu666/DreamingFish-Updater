<script setup lang="ts">
import { computed } from 'vue';
import { X,Upload,CircleCheck,CircleAlert,Pause,FileCheck,LoaderCircle,ArrowRight,FileUp } from '@lucide/vue';
import { desk,safely,checkUploads,commitUploads,request,apiPath,openView } from './store';
import { bytes,type Task } from './model';
const ready=computed(()=>desk.tasks.filter(t=>t.state==='review').length);
const queued=computed(()=>desk.tasks.some(t=>t.state==='queued'||t.state==='error'));
const done=computed(()=>desk.tasks.filter(t=>t.state==='done').length);
const labels:Record<string,string>={queued:'等待检查',uploading:'上传中',checking:'检查中',review:'等待确认',importing:'导入中',done:'已导入',error:'需要重试'};
const summary=computed(()=>[`${desk.tasks.length} 项`,ready.value?`${ready.value} 项等待确认`:'',done.value?`${done.value} 项已导入`:''].filter(Boolean).join(' · '));
async function discard(task:Task){if(task.plan?.id&&task.state!=='done')await request(apiPath(`/files/stage?id=${encodeURIComponent(task.plan.id)}`),'DELETE');desk.tasks=desk.tasks.filter(t=>t.id!==task.id);}
function review(){desk.tasksOpen=false;safely(()=>openView('changes','changes'));}
</script>
<template><section class="task-card" role="dialog" aria-label="导入清单">
 <header class="card-header"><span class="header-icon"><FileUp :size="17"/></span><div><strong>导入清单</strong><small>{{summary||'选择或拖入文件后，先检查，再确认导入'}}</small></div><button class="text-action" :disabled="desk.uploading||!done" @click="desk.tasks=desk.tasks.filter(t=>t.state!=='done')">清除完成项</button><button class="icon-button" aria-label="收起导入清单" title="收起" @click="desk.tasksOpen=false"><X :size="16"/></button></header>
 <div class="task-list"><article v-for="task in desk.tasks" :key="task.id" :class="task.state">
  <span class="task-symbol"><CircleCheck v-if="task.state==='done'" :size="18"/><CircleAlert v-else-if="task.state==='error'" :size="18"/><LoaderCircle v-else-if="task.state==='uploading'||task.state==='importing'" class="spin" :size="18"/><Upload v-else :size="17"/></span>
  <div class="task-copy"><strong :title="task.name">{{task.name}}</strong><input v-if="task.state==='queued'||task.state==='error'" v-model="task.path" aria-label="保存路径" title="保存到整合包中的路径"><small v-else :title="task.path">{{task.path}}</small><small v-if="task.error" class="error-copy">{{task.error}}</small><small v-else-if="task.plan?.metadata">{{task.plan.metadata.displayName||task.plan.metadata.componentId}} · {{task.plan.metadata.version}}</small><small v-if="task.plan?.replacements?.length" class="task-note">将归档旧模组：{{task.plan.replacements.map((p:any)=>p.path).join('、')}}</small></div>
  <span class="task-size tabular">{{bytes(task.size)}}</span>
  <div class="task-state"><select v-if="task.state==='review'" v-model="task.action" aria-label="导入动作"><option v-if="!task.plan?.existing&&!task.plan?.replacements?.length" value="ADD">新增</option><option v-if="task.plan?.existing&&!task.plan?.replacements?.length" value="OVERWRITE">归档后覆盖</option><option v-if="task.plan?.replacements?.length" value="REPLACE_MOD">替换旧模组</option><option value="SKIP">跳过</option></select><span v-else class="state-label">{{labels[task.state]}}</span><progress v-if="task.state==='uploading'" :value="task.progress" max="100"/></div>
  <button v-if="!desk.uploading" class="icon-button" :aria-label="`移出清单 ${task.name}`" title="移出清单" @click="safely(()=>discard(task))"><X :size="14"/></button>
 </article><div v-if="!desk.tasks.length" class="empty-task"><Upload :size="22"/><span>选择或拖入文件后，先检查，再确认导入。</span></div></div>
 <footer class="card-footer"><span>导入完成后，到“改动与版本”检查并发布。</span><button v-if="done&&!ready&&!queued&&!desk.uploading" @click="review">检查并发布<ArrowRight :size="14"/></button><button v-if="desk.uploading" @click="desk.uploadPaused=true"><Pause :size="14"/>暂停后续任务</button><button v-else :disabled="!queued" @click="safely(checkUploads)"><FileCheck :size="15"/>上传并检查</button><button class="accent" :disabled="!ready||desk.uploading" @click="safely(commitUploads)">确认导入{{ready?` ${ready} 项`:''}}</button></footer>
</section></template>
