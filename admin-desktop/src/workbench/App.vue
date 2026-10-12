<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref,watch,nextTick } from 'vue';
import { Files,GitCompareArrows,Package,Palette,Settings2,Search,Plus,ChevronDown,FolderOpen,Layers,Activity,Check,Sun,Moon,PanelLeftClose,PanelLeft,LogOut,ShieldCheck,Server,LoaderCircle,Command,Wifi,History,LayoutTemplate } from '@lucide/vue';
import { desk,boot,authenticate,safely,openView,switchProject,request,operation,setTheme,navigate } from './store';
import { bytes,authRequiresForm } from './model';
import {viewSections} from './presentation';
import {isDesktop,openLegacy} from './host';
import FolderTree from './FolderTree.vue';
import FilesView from './FilesView.vue';
import ChangesView from './ChangesView.vue';
import DeliveryView from './DeliveryView.vue';
import AppearanceView from './AppearanceView.vue';
import SettingsView from './SettingsView.vue';
import Forms from './Forms.vue';
import Tasks from './Tasks.vue';
const nav=[{id:'files',label:'文件',icon:Files},{id:'changes',label:'改动与版本',icon:GitCompareArrows},{id:'delivery',label:'发放',icon:Package},{id:'appearance',label:'玩家端设计',icon:Palette},{id:'settings',label:'设置',icon:Settings2}];
const roots=computed(()=>desk.directories.filter(p=>!p.includes('/')));
const groups=computed(()=>desk.project?.maintenance?.optionalGroups||[]);
const pending=computed(()=>!desk.project?.releases?.length?'未发布':desk.project?.previewStale||!desk.project.preview?'待检查':desk.project.preview.changes?.length||desk.project.preview.policyChanges?.length?'待发布':desk.files.some(f=>!f.published)?'待检查':'已发布');
const jobs=computed(()=>desk.tasks.filter(t=>t.state!=='done').length);
const login=ref({username:'',password:'',confirmPassword:'',allowLocalBypass:false});
const loginBusy=ref(false);
function windowName(){return window.__DFS_DESKTOP_META__?.name||'管理工作区';}
async function signIn(){loginBusy.value=true;try{await authenticate(login.value);login.value.password='';login.value.confirmPassword='';}catch(e){desk.error=String(e instanceof Error?e.message:e);}finally{loginBusy.value=false;}}
async function logout(){await request('/api/auth/logout','POST',{});desk.ready=false;desk.auth=await request('/api/auth/status');desk.menu='';}
function changeProject(event:Event){const selector=event.target as HTMLSelectElement;safely(async()=>{try{await switchProject(selector.value);}finally{selector.value=desk.projectId;}});}
const title=computed(()=>nav.find(n=>n.id===desk.view)?.label||'工作区');
const commandQuery=ref(''),commands=computed(()=>[...nav,{id:'history',label:'操作记录',icon:History,view:'changes',section:'operations'}].filter(item=>item.label.includes(commandQuery.value)));
const sections=computed(()=>viewSections[desk.view]||[]);
function command(item:any){desk.palette=false;safely(()=>openView(item.view||item.id,item.section));}
function resize(){if(!isDesktop&&window.innerWidth<760){desk.sidebar=false;desk.inspector=false;}}
watch(()=>desk.palette,value=>{if(value){commandQuery.value='';void nextTick(()=>document.querySelector<HTMLInputElement>('.command-palette input')?.focus());}});
function key(event:KeyboardEvent){if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='k'&&!desk.dialog){event.preventDefault();desk.palette=!desk.palette;return;}if(event.key==='Escape'){desk.menu='';desk.palette=false;}if((event.target as HTMLElement)?.closest('input,textarea,select,[contenteditable]')||desk.dialog)return;if(event.altKey&&event.key==='ArrowUp'){event.preventDefault();navigate(desk.directory.split('/').slice(0,-1).join('/'));}}
onMounted(()=>{if(!isDesktop)localStorage.setItem('dfs-admin-interface','workbench');resize();safely(boot);window.addEventListener('keydown',key);window.addEventListener('resize',resize);});onUnmounted(()=>{window.removeEventListener('keydown',key);window.removeEventListener('resize',resize);});
watch(()=>[desk.ready,desk.auth.registered,desk.auth.authenticated],()=>{if(window.__DFS_DESKTOP_META__?.check&&(desk.ready||authRequiresForm(desk.auth)))void nextTick(()=>{document.title='DFS_WORKBENCH_READY';});});
</script>
<template>
 <main v-if="!desk.ready" class="connect-screen">
  <div v-if="Object.keys(desk.auth).length===0||!authRequiresForm(desk.auth)" class="opening"><template v-if="desk.error"><strong>{{desk.error}}</strong><button @click="desk.error='';safely(boot)">重新连接</button></template><template v-else><LoaderCircle class="spin" :size="22"/><strong>正在打开工作区</strong></template></div>
  <form v-else class="sign-in" @submit.prevent="signIn"><span class="overline">工作区访问</span><h1>{{desk.auth.registered?'登录管理端':'创建管理账户'}}</h1><p>{{windowName()}} · 使用管理账户继续</p>
   <label>用户名<input v-model="login.username" autocomplete="username" required></label><label>密码<input v-model="login.password" type="password" :autocomplete="desk.auth.registered?'current-password':'new-password'" required></label>
   <template v-if="!desk.auth.registered"><label>确认密码<input v-model="login.confirmPassword" type="password" autocomplete="new-password" required></label><label class="check"><input v-model="login.allowLocalBypass" type="checkbox">允许在服务本机免登录</label></template>
   <p v-if="desk.error" class="error-copy">{{desk.error}}</p><button class="accent" :disabled="loginBusy">{{loginBusy?'正在连接…':'进入工作区'}}</button>
  </form>
 </main>
 <div v-else class="desktop-workbench" :class="{compact:!desk.sidebar,'has-tasks':desk.tasksOpen}">
  <nav class="activity-rail" aria-label="工作区视图">
   <div class="rail-top"><span v-if="!isDesktop" class="web-brand-mark" title="梦鱼更新管理">DF</span><button v-for="item in nav" :key="item.id" :class="{active:desk.view===item.id}" :aria-label="item.label" :title="item.label" @click="safely(()=>openView(item.id))"><component :is="item.icon" :size="20"/><i v-if="item.id==='changes'&&pending!=='已发布'"/></button></div>
   <div class="rail-bottom"><button aria-label="命令菜单" title="命令菜单 · Ctrl K" @click="desk.palette=true"><Command :size="19"/></button><button :aria-label="desk.theme==='light'?'切换深色主题':'切换浅色主题'" :title="desk.theme==='light'?'深色主题':'浅色主题'" @click="setTheme(desk.theme==='light'?'dark':'light')"><Sun v-if="desk.theme==='dark'" :size="19"/><Moon v-else :size="19"/></button>
    <button class="account-avatar" :title="`账户 · ${desk.auth.username||'管理员'}`" aria-label="账户菜单" @click="desk.menu=desk.menu==='account'?'':'account'">{{(desk.auth.username||'A').slice(0,1).toUpperCase()}}</button></div>
  </nav>
  <aside v-if="desk.sidebar" class="project-navigator">
   <div class="project-heading"><span>项目</span><button class="icon-button" title="新建项目" aria-label="新建项目" @click="desk.dialog='project-create';desk.dialogData={}"><Plus :size="16"/></button></div>
   <div class="project-switch"><select aria-label="当前项目" :value="desk.projectId" :disabled="desk.busy||desk.uploading||jobs>0" @change="changeProject($event)"><option v-if="!desk.projectId" value="">选择项目</option><option v-for="project in desk.state.projects||[]" :key="project.id" :value="project.id">{{project.displayName}}</option></select><ChevronDown :size="13"/></div>
   <div v-if="desk.project" class="project-meta"><span class="small-dot"/><span>{{desk.project.id}}</span><small>{{pending}}</small></div>
   <div v-if="desk.view==='files'" class="navigator-content"><button class="root-folder" :class="{current:!desk.directory&&!desk.group}" @click="navigate('')"><FolderOpen :size="16"/>整合包文件<span>{{desk.files.length}}</span></button><ul class="folder-tree"><FolderTree v-for="root in roots" :key="root" :path="root"/></ul>
    <div class="navigator-label"><span>可选内容</span><button class="icon-button" aria-label="新建可选内容" @click="desk.dialog='group';desk.dialogData={}"><Plus :size="14"/></button></div><button v-for="group in groups" :key="group.id" class="nav-context" :class="{current:desk.group===group.id}" @click="desk.group=group.id;desk.selection=[]"><Layers :size="15"/><span>{{group.title}}</span></button>
    <button class="nav-context" @click="safely(()=>openView('changes','problems'))"><ShieldCheck :size="15"/><span>历史问题文件</span></button>
   </div>
   <div v-else class="navigator-content"><button v-for="item in sections" :key="item.id" class="nav-context" :class="{current:desk.section===item.id}" @click="safely(()=>openView(desk.view,item.id))">{{item.label}}</button></div>
   <div v-if="desk.project" class="project-version"><span>当前发布</span><strong>{{desk.state.projects?.find((p:any)=>p.id===desk.projectId)?.latestRelease?.displayVersion||'尚未发布'}}</strong></div>
  </aside>
  <section class="work-area"><header class="workspace-commandbar"><button class="icon-button" :aria-label="desk.sidebar?'收起项目导航':'展开项目导航'" @click="desk.sidebar=!desk.sidebar"><PanelLeftClose v-if="desk.sidebar" :size="17"/><PanelLeft v-else :size="17"/></button><strong>{{title}}</strong><span class="view-separator"/><span v-if="desk.sidebar" class="subtle">{{desk.project?.displayName||'选择一个项目开始'}}</span><select v-else class="compact-project-picker" aria-label="当前项目" :value="desk.projectId" :disabled="desk.busy||desk.uploading||jobs>0" @change="changeProject($event)"><option v-if="!desk.projectId" value="">选择项目</option><option v-for="project in desk.state.projects||[]" :key="project.id" :value="project.id">{{project.displayName}}</option></select><div class="commandbar-spacer"/><button v-if="desk.project" class="history-shortcut" aria-label="操作记录" title="操作记录" @click="safely(()=>openView('changes','operations'))"><History :size="14"/><span>操作记录</span></button><button v-if="!isDesktop" class="interface-switch" aria-label="旧版界面" title="旧版界面" @click="openLegacy"><LayoutTemplate :size="14"/><span>旧版界面</span></button><button class="command-search" aria-label="搜索命令" title="搜索命令 · Ctrl K" @click="desk.palette=true"><Search :size="14"/><span>搜索命令</span><kbd>Ctrl K</kbd></button></header>
   <nav v-if="!desk.sidebar&&sections.length" class="view-tabs" aria-label="当前视图页面"><button v-for="section in sections" :key="section.id" :class="{active:desk.section===section.id}" @click="safely(()=>openView(desk.view,section.id))">{{section.label}}</button></nav>
   <div v-if="!desk.project&&desk.view!=='settings'" class="empty-workspace"><FolderOpen :size="46"/><h2>开始维护你的整合包</h2><p>创建项目，把模组和配置放进同一个工作区。</p><button class="accent" @click="desk.dialog='project-create';desk.dialogData={}"><Plus :size="16"/>创建项目</button></div>
   <FilesView v-else-if="desk.view==='files'"/><ChangesView v-else-if="desk.view==='changes'"/><DeliveryView v-else-if="desk.view==='delivery'"/><AppearanceView v-else-if="desk.view==='appearance'"/><SettingsView v-else/>
  </section>
  <Tasks v-if="desk.tasksOpen"/>
  <footer class="desktop-statusbar"><button @click="desk.dialog='service'"><Wifi :size="13"/><span>{{windowName()}}</span><span class="status-live">已连接</span></button><span class="status-divider"/><span>{{desk.files.length}} 个文件</span><span>{{bytes(desk.files.reduce((n,f)=>n+f.size,0))}}</span><span class="status-spacer"/><span v-if="desk.busy" class="status-task"><LoaderCircle class="spin" :size="13"/>{{desk.busyText}}</span><button @click="desk.tasksOpen=!desk.tasksOpen"><Activity :size="14"/>任务<span v-if="jobs" class="task-number">{{jobs}}</span></button><span>0.2.0</span></footer>
  <div v-if="desk.menu==='account'" class="account-popover"><strong>{{desk.auth.username||'管理员'}}</strong><span>{{windowName()}}</span><button @click="desk.menu='';desk.view='settings';desk.section='account'"><ShieldCheck :size="15"/>账户与安全</button><button @click="safely(logout)"><LogOut :size="15"/>退出账户</button></div>
 </div>
 <div v-if="desk.notice" class="toast"><Check :size="15"/>{{desk.notice}}</div><div v-if="desk.error&&desk.ready" class="error-toast"><span>{{desk.error}}</span><button @click="desk.error=''">关闭</button></div>
 <Forms/>
 <div v-if="desk.palette" class="modal-backdrop" @click.self="desk.palette=false"><section class="command-palette"><header><Search :size="18"/><input v-model="commandQuery" placeholder="搜索命令" aria-label="搜索工作区命令"><kbd>Esc</kbd></header><button v-for="item in commands" :key="item.id" @click="command(item)"><component :is="item.icon" :size="18"/><span>{{item.label}}</span></button><button v-if="!commandQuery||'创建新项目'.includes(commandQuery)" @click="desk.palette=false;desk.dialog='project-create';desk.dialogData={}"><Plus :size="18"/>创建新项目</button></section></div>
</template>
