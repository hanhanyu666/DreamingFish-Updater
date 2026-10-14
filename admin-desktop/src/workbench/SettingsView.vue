<script setup lang="ts">
import { reactive,watch,ref } from 'vue';
import { FolderOpen,Globe,ShieldCheck,Save,Server,KeyRound,Copy,RotateCcw } from '@lucide/vue';
import { desk,safely,saveProject,request,apiPath,operation,refresh,notify,openBrowse,openView } from './store';
import { viewSections,sectionLabel } from './presentation';
import Segmented from './Segmented.vue';
const project=reactive({displayName:'',sourceDirectory:'',publicBaseUrl:''}),settings=reactive({httpHost:'',httpPort:8080,webHost:'',webPort:18080});
const account=reactive({username:'',password:'',newPassword:'',confirmPassword:'',allowLocalBypass:false});
const address=ref<any>(null);
function resetProject(){Object.assign(project,{displayName:desk.project?.displayName||'',sourceDirectory:desk.project?.sourceDirectory||'',publicBaseUrl:desk.project?.publicBaseUrl||''});desk.settingsDirty=false;}
watch(()=>desk.project,resetProject,{immediate:true});
watch(()=>desk.state.settings,()=>Object.assign(settings,desk.state.settings||{}),{immediate:true});
watch(()=>desk.auth,()=>{account.username=desk.auth.username||'';account.allowLocalBypass=Boolean(desk.auth.localBypass);},{immediate:true});
watch(()=>desk.browseValues['project-source'],value=>{if(value){project.sourceDirectory=value;desk.settingsDirty=true;}});
async function checkAddress(){address.value=await request(apiPath('/check-address'),'POST',{url:project.publicBaseUrl});}
async function saveService(){await operation(async()=>{await request('/api/settings','PUT',settings);await refresh();notify('服务设置已保存');});}
async function saveAccount(){if(account.newPassword!==account.confirmPassword)throw new Error('两次新密码不一致');await operation(async()=>{await request('/api/auth/account','PUT',account);account.password='';account.newPassword='';account.confirmPassword='';desk.auth=await request('/api/auth/status');notify('账户设置已保存');});}
function copy(text:string){void navigator.clipboard?.writeText(text).then(()=>notify('已复制'));}
</script>
<template><div class="view settings-view"><section class="stage">
 <header class="stage-toolbar"><Segmented class="section-tabs" :items="viewSections.settings" :model-value="desk.section" label="设置" @update:model-value="safely(()=>openView('settings',$event))"/><strong class="section-title">{{sectionLabel('settings',desk.section)}}</strong><span class="toolbar-spacer"/><span class="toolbar-note">{{desk.section==='project'?desk.project?.displayName:'当前管理连接'}}</span></header>
 <div class="list-scroll" :class="{'with-selection':desk.settingsDirty&&desk.section==='project'}"><div class="page-column narrow">
  <form v-if="desk.section!=='service'&&desk.section!=='account'" id="project-settings" class="settings-form" @submit.prevent="safely(()=>saveProject(project))" @input="desk.settingsDirty=true">
   <section class="settings-group"><h3>整合包项目</h3><div class="group-card">
    <label class="group-row"><span class="row-label">项目名称</span><input v-model="project.displayName" required></label>
    <label class="group-row"><span class="row-label">整合包标准目录<small>模组与配置从这个目录检查并发布</small></span><div class="field-with-button"><input v-model="project.sourceDirectory" required><button type="button" aria-label="浏览目录" title="浏览目录" @click="openBrowse('directory','project-source',project.sourceDirectory)"><FolderOpen :size="16"/></button></div></label>
    <label class="group-row"><span class="row-label">玩家更新地址<small>玩家端从这里获取发布</small></span><div class="field-with-button"><input v-model="project.publicBaseUrl" type="url" required><button type="button" class="labelled" @click="safely(checkAddress)"><Globe :size="15"/>检测</button></div></label>
   </div><p v-if="address" class="group-note" :class="address.ok?'ok':'bad'">{{address.message||address.summary||address.status||'检测完成'}}{{address.milliseconds?` · ${address.milliseconds} ms`:''}}</p></section>
   <section class="settings-group"><h3>项目信息</h3><div class="group-card"><div class="group-row"><span class="row-label">项目 ID</span><code>{{desk.project?.id}}</code><button type="button" class="icon-button" aria-label="复制项目 ID" title="复制" @click="copy(desk.project?.id||'')"><Copy :size="14"/></button></div><div class="group-row"><span class="row-label">管理数据</span><code>{{desk.state.dataDirectory}}</code><button type="button" class="icon-button" aria-label="复制管理数据目录" title="复制" @click="copy(desk.state.dataDirectory||'')"><Copy :size="14"/></button></div></div></section>
  </form>
  <form v-else-if="desk.section==='service'" class="settings-form" @submit.prevent="safely(saveService)">
   <section class="settings-group"><h3>玩家下载服务</h3><div class="group-card"><div class="group-row"><span class="row-label">运行状态</span><span class="service-state" :class="{on:desk.state.publicService?.running}"><i/>{{desk.state.publicService?.running?'运行中':'已停止'}}</span><button type="button" class="labelled" @click="desk.dialog='service'"><Server :size="15"/>管理服务</button></div><label class="group-row"><span class="row-label">监听地址</span><input v-model="settings.httpHost" required></label><label class="group-row"><span class="row-label">监听端口</span><input v-model.number="settings.httpPort" type="number" min="1" max="65535" required></label></div><p class="group-note">下载地址用于提供更新内容，与管理工作区的地址分别设置。</p></section>
   <section class="settings-group"><h3>网页管理服务</h3><div class="group-card"><label class="group-row"><span class="row-label">网页监听地址</span><input v-model="settings.webHost" required></label><label class="group-row"><span class="row-label">网页端口</span><input v-model.number="settings.webPort" type="number" min="1" max="65535" required></label></div><p class="group-note">桌面工作区使用自动分配的本机端口，原网页配置仍保留。</p></section>
   <div class="form-actions end"><button class="accent" :disabled="desk.busy"><Save :size="15"/>保存服务设置</button></div>
  </form>
  <form v-else class="settings-form" @submit.prevent="safely(saveAccount)">
   <div class="identity-card"><span class="identity-avatar"><ShieldCheck :size="22"/></span><div><strong>{{desk.auth.username||'管理员'}}</strong><small>管理账户 · 修改前需要输入当前密码</small></div></div>
   <section class="settings-group"><h3>管理账户</h3><div class="group-card"><label class="group-row"><span class="row-label">用户名</span><input v-model="account.username" autocomplete="username" required></label><label class="group-row"><span class="row-label">当前密码</span><input v-model="account.password" type="password" autocomplete="current-password" required></label></div></section>
   <section class="settings-group"><h3>修改密码</h3><div class="group-card"><label class="group-row"><span class="row-label">新密码<small>至少 10 个字符</small></span><input v-model="account.newPassword" type="password" autocomplete="new-password" minlength="10"></label><label class="group-row"><span class="row-label">确认新密码</span><input v-model="account.confirmPassword" type="password" autocomplete="new-password"></label><label class="group-row toggle-row"><span class="row-label">允许在管理服务本机免登录</span><input v-model="account.allowLocalBypass" type="checkbox" class="switch"></label></div></section>
   <div class="form-actions end"><button class="accent" :disabled="desk.busy"><KeyRound :size="15"/>保存账户设置</button></div>
  </form>
 </div></div>
 <transition name="rise"><div v-if="desk.settingsDirty&&desk.section!=='service'&&desk.section!=='account'" class="selection-bar save-bar" role="status"><span class="save-state dirty"><i/>有尚未保存的修改</span><span class="bar-divider"/><button class="bar-action" @click="resetProject"><RotateCcw :size="14"/>放弃</button><button class="accent" form="project-settings" :disabled="desk.busy"><Save :size="15"/>保存项目设置</button></div></transition>
</section></div></template>
