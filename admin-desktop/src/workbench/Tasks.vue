<script setup lang="ts">
import { computed,reactive,ref } from 'vue';
import { X,Upload,CircleCheck,CircleAlert,Pause,FileCheck,LoaderCircle,ArrowRight,FileUp } from '@lucide/vue';
import { desk,safely,checkUploads,commitUploads,discardTask,cancelUpload,openView,recheckJob,cancelJob,dismissJob,inspectJob } from './store';
import {backendTaskFinished,backendTaskNeedsReview,backendTaskLabels,backendTaskName,backendTaskReview} from './backend-tasks';
import { bytes,taskFinished,imported,type Task } from './model';
const ready=computed(()=>desk.tasks.filter(t=>t.state==='review').length);
const queued=computed(()=>desk.tasks.some(t=>['queued','error','unknown','expired','needs-file','check-error'].includes(t.state)));
const done=computed(()=>desk.tasks.filter(imported).length);
const labels:Record<string,string>={queued:'等待检查',uploading:'上传中',checking:'检查中',review:'等待确认',importing:'导入中',done:'已导入',error:'需要重试',skipped:'已跳过',unknown:'结果待确认',expired:'暂存过期',undone:'已撤销',
'needs-file':'需要重新选择文件','check-error':'已导入，检查未通过'};
const jobs=computed(()=>desk.jobs.filter(j=>!j.dismissed).sort((a,b)=>Number(backendTaskNeedsReview(b))-Number(backendTaskNeedsReview(a))));
const summary=computed(()=>`${jobs.value.length} 项后台任务 · ${desk.tasks.length} 个导入文件`);
const finished=computed(()=>desk.tasks.some(taskFinished)||jobs.value.some(backendTaskFinished));
const refreshing=reactive(new Set<string>()),feedback=reactive<Record<string,string>>({});
const confirmingDismiss=ref('');
function clearFinished(){desk.tasks=desk.tasks.filter(t=>!taskFinished(t));for(const job of jobs.value)if(backendTaskFinished(job))job.dismissed=true;}
async function refresh(id:string){
 if(refreshing.has(id))return;refreshing.add(id);feedback[id]='正在向服务器查询…';
 try{await recheckJob(id);const job=desk.jobs.find(j=>j.id===id);feedback[id]=job&&backendTaskNeedsReview(job)?'已查询：服务器仍没有最终结果。请点击下方按钮查看实际内容。':job?.state==='SUCCEEDED'?'已查询：服务器确认这项任务已完成。':'已查询：'+(job?.phase||'任务仍在执行')+'。';}
 catch(error){feedback[id]='查询未完成：'+(error instanceof Error?error.message:String(error));}
 finally{refreshing.delete(id);}
}
function review(){desk.tasksOpen=false;safely(()=>openView('changes','changes'));}
</script>
<template><section id="workspace-tasks" class="task-card" role="dialog" aria-label="任务清单">
 <header class="card-header"><span class="header-icon"><FileUp :size="17"/></span><div><strong>任务清单</strong><small>{{summary}}</small></div><button class="text-action" :disabled="desk.uploading||!finished" @click="clearFinished">清除完成项</button><button class="icon-button" aria-label="收起任务清单" title="收起" @click="desk.tasksOpen=false"><X :size="16"/></button></header>
 <div class="task-list"><p v-if="jobs.length" class="task-section-label">后台任务 · 下载程序、发布版本、上传外部托管等操作</p>
  <article v-for="job in jobs" :key="job.id" class="backend-task" :class="{'needs-review':backendTaskNeedsReview(job)}">
   <div class="backend-task-heading"><CircleCheck v-if="job.state==='SUCCEEDED'" :size="18"/><CircleAlert v-else-if="backendTaskNeedsReview(job)||job.state==='FAILED'" :size="18"/><LoaderCircle v-else-if="!backendTaskFinished(job)" class="spin" :size="18"/><strong>{{backendTaskName(job)}}{{job.projectId?' · '+job.projectId:''}}</strong><span>{{backendTaskLabels[job.state]}}</span></div>
   <template v-if="backendTaskNeedsReview(job)"><p>{{backendTaskReview(job).summary}}</p><p>{{backendTaskReview(job).instruction}}</p><p class="muted">“刷新任务状态”只查询结果，不会重新执行。“关闭提醒”只移除提示，不会删除文件或发布更新。</p></template>
   <p v-else>{{job.error||job.phase}}</p><p v-if="feedback[job.id]" class="task-feedback" role="status">{{feedback[job.id]}}</p>
   <div class="backend-task-actions"><button v-if="backendTaskNeedsReview(job)" class="accent" @click="safely(()=>inspectJob(job))">{{backendTaskReview(job).destination.label}}<ArrowRight :size="14"/></button><button v-if="!backendTaskFinished(job)" :disabled="refreshing.has(job.id)" @click="refresh(job.id)"><LoaderCircle v-if="refreshing.has(job.id)" class="spin" :size="14"/>{{refreshing.has(job.id)?'正在查询…':'刷新任务状态'}}</button><button v-if="job.state==='QUEUED'" @click="safely(()=>cancelJob(job))">取消排队</button><button v-if="backendTaskNeedsReview(job)&&confirmingDismiss!==job.id" @click="confirmingDismiss=job.id">我已查看结果，关闭提醒</button><button v-if="backendTaskFinished(job)" @click="job.dismissed=true">移出清单</button></div>
   <div v-if="backendTaskNeedsReview(job)&&confirmingDismiss===job.id" class="task-dismiss-confirm" role="group" aria-label="确认关闭任务提醒"><p>已经查看过上次操作的结果了吗？关闭后可以继续操作；这不会删除文件、重新下载或发布更新。</p><div class="backend-task-actions"><button @click="confirmingDismiss=''">暂不关闭</button><button class="accent" @click="dismissJob(job,true);confirmingDismiss=''">确认关闭提醒</button></div></div>
   <details><summary>技术详情（排查问题时使用）</summary><p>任务编号：{{job.id}}</p><p>{{job.phase}}</p><p v-if="job.error">{{job.error}}</p></details>
  </article><p v-if="desk.tasks.length" class="task-section-label">导入文件 · 先上传检查，再确认放入整合包</p><article v-for="task in desk.tasks" :key="task.id" :class="task.state">
  <span class="task-symbol"><CircleCheck v-if="imported(task)||task.state==='skipped'" :size="18"/><CircleAlert v-else-if="task.state==='error'" :size="18"/><LoaderCircle v-else-if="task.state==='uploading'||task.state==='importing'" class="spin" :size="18"/><Upload v-else :size="17"/></span>
  <div class="task-copy"><strong :title="task.name">{{task.name}}</strong><input v-if="task.state==='queued'||task.state==='error'" v-model="task.path" aria-label="保存路径" title="保存到整合包中的路径"><small v-else :title="task.path">{{task.path}}</small><small v-if="task.error" class="error-copy">{{task.error}}</small><small v-else-if="task.plan?.metadata">{{task.plan.metadata.displayName||task.plan.metadata.componentId}} · {{task.plan.metadata.version}}</small><small v-if="task.plan?.replacements?.length" class="task-note">将归档旧模组：{{task.plan.replacements.map((p:any)=>p.path).join('、')}}</small></div>
  <span class="task-size tabular">{{bytes(task.size)}}</span>
  <div class="task-state"><select v-if="task.state==='review'" v-model="task.action" aria-label="导入动作"><option v-if="!task.plan?.existing&&!task.plan?.replacements?.length" value="ADD">新增</option><option v-if="task.plan?.existing&&!task.plan?.replacements?.length" value="OVERWRITE">归档后覆盖</option><option v-if="task.plan?.replacements?.length" value="REPLACE_MOD">替换旧模组</option><option value="SKIP">跳过</option></select><span v-else class="state-label">{{labels[task.state]}}</span><progress v-if="task.state==='uploading'" :value="task.progress" max="100"/></div>
  <button v-if="!desk.uploading" class="icon-button" :aria-label="`移出清单 ${task.name}`" title="移出清单" @click="safely(()=>discardTask(task))"><X :size="14"/></button>
 </article><div v-if="!desk.tasks.length&&!jobs.length" class="empty-task"><Upload :size="22"/><span>暂无任务。导入文件、下载程序或发布更新后，可以在这里查看进度。</span></div></div>
 <footer v-if="desk.tasks.length" class="card-footer"><span>导入完成后，到“改动与版本”检查并发布。</span><button v-if="done&&!ready&&!queued&&!desk.uploading" @click="review">检查并发布<ArrowRight :size="14"/></button><button v-if="desk.uploading" @click="desk.uploadPaused=true"><Pause :size="14"/>暂停后续任务</button><button v-if="desk.uploading" @click="cancelUpload">取消当前上传</button><button v-else :disabled="!queued" @click="safely(checkUploads)"><FileCheck :size="15"/>上传并检查</button><button class="accent" :disabled="!ready||desk.uploading" @click="safely(commitUploads)">确认导入{{ready?` ${ready} 项`:''}}</button></footer>
</section></template>
