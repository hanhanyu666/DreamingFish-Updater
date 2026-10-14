<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref,watch,nextTick } from 'vue';
import { Files,GitCompareArrows,Package,Palette,Settings2,Search,Plus,Check,LoaderCircle,History,Upload,X,CornerDownLeft,Server,Sun,Moon,ShieldCheck,LogOut,Info,PanelLeft,PanelLeftClose,ChevronsLeft,ChevronsRight,LayoutTemplate } from '@lucide/vue';
import { desk,boot,authenticate,safely,openView,navigate,request,setTheme,publishState,latestVersion } from './store';
import { authRequiresForm,bytes } from './model';
import { isDesktop,openLegacy } from './host';
import { useIndicator } from './indicator';
import Sidebar from './Sidebar.vue';
import ProjectPicker from './ProjectPicker.vue';
import FilesView from './FilesView.vue';
import ChangesView from './ChangesView.vue';
import DeliveryView from './DeliveryView.vue';
import AppearanceView from './AppearanceView.vue';
import SettingsView from './SettingsView.vue';
import Forms from './Forms.vue';
import Tasks from './Tasks.vue';
import Fish from './Fish.vue';
const nav=[{id:'files',label:'文件',icon:Files},{id:'changes',label:'改动与版本',icon:GitCompareArrows},{id:'delivery',label:'发放',icon:Package},{id:'appearance',label:'玩家端设计',icon:Palette},{id:'settings',label:'设置',icon:Settings2}];
const title=computed(()=>nav.find(item=>item.id===desk.view)?.label||'');
// Narrow windows open the project sidebar over the content, so it starts closed there and is not remembered.
const narrow=window.matchMedia('(max-width:900px)');
if(narrow.matches)desk.sidebar=false;
watch(()=>desk.sidebar,value=>{if(!narrow.matches)localStorage.setItem('dfs-workbench-sidebar',value?'on':'off');});
const jobs=computed(()=>desk.tasks.filter(t=>t.state!=='done').length);
const locked=computed(()=>desk.busy||desk.uploading||jobs.value>0);
const login=ref({username:'',password:'',confirmPassword:'',allowLocalBypass:false});
const loginBusy=ref(false);
// The rail shows icons by default; the person can pin labels open, and the choice is remembered.
const railExpanded=ref(localStorage.getItem('dfs-workbench-rail')==='expanded');
watch(railExpanded,value=>localStorage.setItem('dfs-workbench-rail',value?'expanded':'compact'));
const rail=ref<HTMLElement|null>(null),railIndicator=useIndicator(rail,()=>[desk.view,desk.ready,desk.tasks.length>0,railExpanded.value],true);
const running=computed(()=>Boolean(desk.state.publicService?.running));
// Status bar: what would ship with the next release, and the size of the modpack.
const pendingCount=computed(()=>new Set([...desk.files.filter(f=>!f.published).map(f=>f.path),...(desk.project?.preview?.changes||[]).map((c:any)=>c.path)]).size+(desk.project?.preview?.policyChanges?.length||0));
const totalSize=computed(()=>desk.files.reduce((n,f)=>n+(f.size||0),0));
const initial=(name='')=>[...name.trim()][0]?.toUpperCase()||'A';
async function logout(){await request('/api/auth/logout','POST',{});desk.ready=false;desk.auth=await request('/api/auth/status');desk.menu='';}
function windowName(){return window.__DFS_DESKTOP_META__?.name||'管理工作区';}
async function signIn(){loginBusy.value=true;try{await authenticate(login.value);login.value.password='';login.value.confirmPassword='';}catch(e){desk.error=String(e instanceof Error?e.message:e);}finally{loginBusy.value=false;}}
const commandQuery=ref(''),commandIndex=ref(0);
const commands=computed(()=>[...nav.map((n,i)=>({...n,hint:`Ctrl ${i+1}`})),{id:'history',label:'操作记录',icon:History,view:'changes',section:'operations',hint:''},{id:'import',label:'导入文件',icon:Upload,view:'files',hint:''},{id:'create',label:'创建新项目',icon:Plus,hint:''}].filter(item=>item.label.includes(commandQuery.value.trim())));
function command(item:any){desk.palette=false;if(item.id==='create'){desk.dialog='project-create';desk.dialogData={};return;}if(item.id==='import'){safely(async()=>{await openView('files');await nextTick();document.querySelector<HTMLButtonElement>('[data-import-files]')?.click();});return;}safely(()=>openView(item.view||item.id,item.section));}
function paletteKey(event:KeyboardEvent){if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();const n=commands.value.length;if(n)commandIndex.value=(commandIndex.value+(event.key==='ArrowDown'?1:-1)+n)%n;}if(event.key==='Enter'&&commands.value[commandIndex.value]){event.preventDefault();command(commands.value[commandIndex.value]);}}
watch(commandQuery,()=>{commandIndex.value=0;});
watch(()=>desk.palette,value=>{if(value){commandQuery.value='';commandIndex.value=0;void nextTick(()=>document.querySelector<HTMLInputElement>('.command-palette input')?.focus());}});
function key(event:KeyboardEvent){
 if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='k'&&!desk.dialog){event.preventDefault();desk.palette=!desk.palette;return;}
 // Esc closes the innermost floating layer first, so views do not also clear their selection.
 if(event.key==='Escape'&&(desk.menu||desk.palette)){desk.menu='';desk.palette=false;event.stopImmediatePropagation();return;}
 if(!desk.ready||desk.dialog||desk.palette)return;
 if((event.ctrlKey||event.metaKey)&&!event.shiftKey&&!event.altKey&&/^[1-5]$/.test(event.key)){event.preventDefault();safely(()=>openView(nav[Number(event.key)-1].id));return;}
 if((event.target as Element|null)?.closest?.('input,textarea,select,[contenteditable]'))return;
 if(event.altKey&&event.key==='ArrowUp'&&desk.view==='files'){event.preventDefault();navigate(desk.directory.split('/').slice(0,-1).join('/'));}
}
function outside(event:PointerEvent){const target=event.target as HTMLElement|null;if(desk.menu&&!target?.closest?.('[data-menu-root]'))desk.menu='';if(narrow.matches&&desk.sidebar&&!target?.closest?.('.sidebar,[data-sidebar-toggle],.menu,.modal-backdrop'))desk.sidebar=false;}
onMounted(()=>{if(!isDesktop)localStorage.setItem('dfs-admin-interface','workbench');safely(boot);window.addEventListener('keydown',key);window.addEventListener('pointerdown',outside,true);});
onUnmounted(()=>{window.removeEventListener('keydown',key);window.removeEventListener('pointerdown',outside,true);});
watch(()=>[desk.ready,desk.auth.registered,desk.auth.authenticated],()=>{if(window.__DFS_DESKTOP_META__?.check&&(desk.ready||authRequiresForm(desk.auth)))void nextTick(()=>{document.title='DFS_WORKBENCH_READY';});});
</script>
<template>
 <main v-if="!desk.ready" class="connect-screen">
  <div v-if="Object.keys(desk.auth).length===0||!authRequiresForm(desk.auth)" class="opening"><template v-if="desk.error"><strong>{{desk.error}}</strong><button class="accent" @click="desk.error='';safely(boot)">重新连接</button></template><template v-else><LoaderCircle class="spin" :size="22"/><strong>正在打开工作区</strong></template></div>
  <form v-else class="sign-in" @submit.prevent="signIn"><Fish variant="mark" :size="48" class="brand-fish"/><h1>{{desk.auth.registered?'登录管理端':'创建管理账户'}}</h1><p>{{windowName()}} · 使用管理账户继续</p>
   <label>用户名<input v-model="login.username" autocomplete="username" required></label><label>密码<input v-model="login.password" type="password" :autocomplete="desk.auth.registered?'current-password':'new-password'" required></label>
   <template v-if="!desk.auth.registered"><label>确认密码<input v-model="login.confirmPassword" type="password" autocomplete="new-password" required></label><label class="check"><input v-model="login.allowLocalBypass" type="checkbox">允许在服务本机免登录</label></template>
   <p v-if="desk.error" class="error-copy">{{desk.error}}</p><button class="accent large full" :disabled="loginBusy">{{loginBusy?'正在连接…':'进入工作区'}}</button>
  </form>
 </main>
 <div v-else class="shell" :class="{'sidebar-on':desk.sidebar,'rail-expanded':railExpanded,'has-selection':desk.view==='files'&&desk.selection.length>0&&!desk.inspector}">
  <nav ref="rail" class="nav-rail" :class="{expanded:railExpanded}" aria-label="工作区视图">
   <span class="rail-indicator" :style="railIndicator" aria-hidden="true"/>
   <span v-if="!isDesktop" class="rail-brand" title="梦鱼更新管理"><Fish variant="mark" :size="32"/></span>
   <button v-for="(item,i) in nav.slice(0,4)" :key="item.id" class="rail-item" :class="{active:desk.view===item.id}" :data-active="desk.view===item.id" :data-tip="`${item.label} · Ctrl ${i+1}`" :aria-label="item.label" :aria-current="desk.view===item.id?'page':undefined" @click="safely(()=>openView(item.id))"><component :is="item.icon" :size="20"/><span class="rail-label">{{item.label}}</span><i v-if="item.id==='changes'&&desk.project&&publishState!=='已发布'" class="rail-dot" :title="publishState"/></button>
   <span class="rail-spacer"/>
   <button v-if="desk.tasks.length" class="rail-item rail-tasks" :class="{open:desk.tasksOpen}" :data-tip="desk.tasksOpen?'收起导入清单':'打开导入清单'" aria-label="导入清单" @click="desk.tasksOpen=!desk.tasksOpen"><Upload :size="19"/><span class="rail-label">导入清单<small>{{jobs?`${jobs} 项待处理`:'已全部完成'}}</small></span><b v-if="jobs">{{jobs}}</b></button>
   <button class="rail-item rail-utility" :data-tip="running?'下载服务运行中':'下载服务已停止'" aria-label="下载服务" @click="desk.dialog='service'"><span class="rail-icon"><Server :size="19"/><i class="service-dot" :class="{on:running}"/></span><span class="rail-label">下载服务<small>{{running?'运行中':'已停止'}}</small></span></button>
   <button class="rail-item rail-utility" :data-tip="desk.theme==='light'?'切换到深色':'切换到浅色'" :aria-label="desk.theme==='light'?'切换深色主题':'切换浅色主题'" @click="setTheme(desk.theme==='light'?'dark':'light')"><Moon v-if="desk.theme==='light'" :size="19"/><Sun v-else :size="19"/><span class="rail-label">{{desk.theme==='light'?'深色模式':'浅色模式'}}</span></button>
   <button class="rail-item" :class="{active:desk.view==='settings'}" :data-active="desk.view==='settings'" data-tip="设置 · Ctrl 5" aria-label="设置" :aria-current="desk.view==='settings'?'page':undefined" @click="safely(()=>openView('settings'))"><Settings2 :size="19"/><span class="rail-label">设置</span></button>
   <div class="menu-anchor rail-account" data-menu-root><button class="rail-item" :data-tip="desk.auth.username||'账户'" aria-label="账户菜单" aria-haspopup="menu" :aria-expanded="desk.menu==='account'" @click="desk.menu=desk.menu==='account'?'':'account'"><span class="bar-avatar">{{initial(desk.auth.username)}}</span><span class="rail-label">{{desk.auth.username||'管理员'}}<small>{{windowName()}}</small></span></button>
    <div v-if="desk.menu==='account'" class="menu rail-menu" role="menu" aria-label="账户"><div class="menu-identity"><span class="bar-avatar">{{initial(desk.auth.username)}}</span><span class="menu-copy"><strong>{{desk.auth.username||'管理员'}}</strong><small>{{windowName()}}</small></span></div><hr><button role="menuitem" @click="desk.menu='';safely(()=>openView('settings','account'))"><ShieldCheck :size="15"/>账户与安全</button><button role="menuitem" class="compact-only" @click="desk.menu='';desk.dialog='service'"><Server :size="15"/>下载服务</button><button role="menuitem" class="compact-only" @click="desk.menu='';setTheme(desk.theme==='light'?'dark':'light')"><Moon v-if="desk.theme==='light'" :size="15"/><Sun v-else :size="15"/>{{desk.theme==='light'?'深色模式':'浅色模式'}}</button><button role="menuitem" @click="safely(logout)"><LogOut :size="15"/>退出账户</button><hr><span class="menu-footnote"><Info :size="13"/>管理端 0.2.0</span></div></div>
   <button class="rail-item rail-toggle" :data-tip="railExpanded?'只显示图标':'显示文字'" :aria-label="railExpanded?'只显示图标':'显示文字'" :aria-pressed="railExpanded" @click="railExpanded=!railExpanded"><ChevronsLeft v-if="railExpanded" :size="19"/><ChevronsRight v-else :size="19"/><span class="rail-label">只显示图标</span></button>
  </nav>
  <Sidebar v-if="desk.sidebar" :locked="locked"/>
  <section class="work-area">
  <header class="commandbar" :inert="Boolean(desk.dialog||desk.palette)">
   <button class="icon-button" data-sidebar-toggle :aria-label="desk.sidebar?'收起项目栏':'展开项目栏'" :title="desk.sidebar?'收起项目栏':'展开项目栏'" :aria-pressed="desk.sidebar" @click="desk.sidebar=!desk.sidebar"><PanelLeftClose v-if="desk.sidebar" :size="17"/><PanelLeft v-else :size="17"/></button>
   <strong class="commandbar-title">{{title}}</strong><span class="commandbar-sep"/>
   <span v-if="desk.sidebar" class="commandbar-project">{{desk.project?.displayName||'选择一个项目开始'}}</span><ProjectPicker v-else :locked="locked" compact/>
   <span class="commandbar-spacer"/>
   <button v-if="desk.project" class="commandbar-link" title="操作记录" @click="safely(()=>openView('changes','operations'))"><History :size="15"/><span>操作记录</span></button>
   <button v-if="!isDesktop" class="commandbar-link" title="切换到旧版界面" @click="openLegacy"><LayoutTemplate :size="15"/><span>旧版界面</span></button>
   <button class="commandbar-search" aria-label="搜索命令" title="搜索命令 · Ctrl K" @click="desk.palette=true"><Search :size="14"/><span>搜索命令</span><kbd>Ctrl K</kbd></button>
  </header>
  <main class="workspace-main">
   <div v-if="!desk.project&&desk.view!=='settings'" class="stage empty-workspace"><Fish variant="scene" class="empty-fish large"/><h2>开始维护你的整合包</h2><p>创建项目，把模组和配置放进同一个工作区。</p><button class="accent large" @click="desk.dialog='project-create';desk.dialogData={}"><Plus :size="17"/>创建项目</button></div>
   <FilesView v-else-if="desk.view==='files'"/><ChangesView v-else-if="desk.view==='changes'"/><DeliveryView v-else-if="desk.view==='delivery'"/><AppearanceView v-else-if="desk.view==='appearance'"/><SettingsView v-else/>
  </main>
  </section>
  <footer class="status-bar" :inert="Boolean(desk.dialog||desk.palette)">
   <template v-if="desk.project"><button class="status-item" :class="{waiting:pendingCount||desk.project.previewStale}" title="打开待发布改动" @click="safely(()=>openView('changes','changes'))"><Fish v-if="!pendingCount&&!desk.project.previewStale" class="status-fish" :size="14"/><i v-else class="status-dot"/>{{pendingCount?`${pendingCount} 项待发布`:desk.project.previewStale?'有改动待检查':'与当前发布一致'}}</button><span class="status-item">{{latestVersion?`当前版本 ${latestVersion}`:'尚未发布'}}</span><span class="status-item">{{desk.files.length}} 个文件 · {{bytes(totalSize)}}</span></template>
   <span class="status-spacer"/>
   <button v-if="desk.tasks.length" class="status-item" :class="{active:desk.tasksOpen}" :title="desk.tasksOpen?'收起导入清单':'打开导入清单'" @click="desk.tasksOpen=!desk.tasksOpen"><Upload :size="13"/>{{jobs?`导入清单 · ${jobs} 项待处理`:'导入清单 · 已完成'}}</button>
   <button class="status-item" title="下载服务" @click="desk.dialog='service'"><i class="status-dot" :class="{off:!running}"/>下载服务{{running?'运行中':'已停止'}}</button>
   <span class="status-item">管理端 0.2.0</span>
  </footer>
  <Tasks v-if="desk.tasksOpen"/>
  <div class="toast-stack" aria-live="polite"><div v-if="desk.busy" class="toast busy"><Fish class="swim" :size="18"/>{{desk.busyText||'正在处理…'}}</div><div v-if="desk.notice" class="toast"><Check :size="15"/>{{desk.notice}}</div><div v-if="desk.error" class="toast error" role="alert"><span>{{desk.error}}</span><button class="icon-button" aria-label="关闭错误提示" @click="desk.error=''"><X :size="14"/></button></div></div>
 </div>
 <Forms/>
 <div v-if="desk.palette" class="modal-backdrop palette-backdrop" @click.self="desk.palette=false"><section class="command-palette" role="dialog" aria-label="命令菜单"><header><Search :size="18"/><input v-model="commandQuery" placeholder="搜索命令或页面" aria-label="搜索工作区命令" @keydown="paletteKey"><kbd>Esc</kbd></header><div class="palette-results"><button v-for="(item,i) in commands" :key="item.id" :class="{active:i===commandIndex}" @mouseenter="commandIndex=i" @click="command(item)"><span class="palette-icon"><component :is="item.icon" :size="17"/></span><span>{{item.label}}</span><kbd v-if="item.hint">{{item.hint}}</kbd><CornerDownLeft v-else-if="i===commandIndex" :size="14" class="palette-enter"/></button><p v-if="!commands.length" class="palette-empty"><Fish :size="18"/>没有匹配的命令</p></div></section></div>
</template>
