<script setup lang="ts">
import { computed,nextTick,onMounted,onUnmounted,ref,watch } from 'vue';
import { ArrowLeft,ArrowRight,ArrowUp,ChevronRight,ChevronDown,Search,Plus,Upload,FolderPlus,RefreshCw,Info,Ellipsis,Shield,ShieldAlert,Trash2,Layers,FolderTree as TreeIcon,FolderOpen,History,Undo2,X,Server,ListFilter,Check,Pencil,CornerLeftUp } from '@lucide/vue';
import { desk,fileIndex,pendingCounts,navigate,travel,safely,places,groups,loadProject,addUploads,applyPreset,request,apiPath,notify,getHistoryFiles,operation,openView,restorePreview } from './store';
import { indexEntries,pageEntries,selectionVisibility,bytes,nameOf,parentOf,presets,type Entry } from './model';
import { presetSourceLabel } from './presentation';
import FolderTree from './FolderTree.vue';
import FileIcon from './FileIcon.vue';
import Fish from './Fish.vue';
const uploads=ref<HTMLInputElement|null>(null),folders=ref<HTMLInputElement|null>(null),searchInput=ref<HTMLInputElement|null>(null);
const sort=ref('name'),ascending=ref(true),anchor=ref(''),dragging=ref(0),context=ref({x:0,y:0});
const maintenance=['SYNC','REQUIRED','INITIAL'];
const filters:Record<string,string>={all:'全部文件',pending:'待发布',REQUIRED:'强制同步',INITIAL:'首次提供'};
const changeKinds=computed(()=>new Map<string,string>((desk.project?.preview?.changes||[]).map((c:any)=>[c.path,c.kind])));
const isPending=(path:string,published=true)=>!published||changeKinds.value.has(path);
const flat=computed(()=>Boolean(desk.search.trim()||desk.group||desk.filter!=='all'));
const effective=(entry:Entry)=>entry.preset||[...(desk.project?.maintenance?.presets||[])].filter(r=>r.directory?(entry.path===r.path||entry.path.startsWith(r.path+'/')):entry.path===r.path).sort((a,b)=>Number(a.directory)-Number(b.directory)||b.path.length-a.path.length)[0]?.preset||'SYNC';
const visible=computed(()=>{
 let rows:Entry[]=flat.value?desk.files.map(f=>({...f,name:nameOf(f.path),directory:false})):indexEntries(fileIndex.value,desk.directory);
 const query=desk.search.trim().toLowerCase();
 if(query)rows=rows.filter(e=>`${e.path} ${e.displayName||''} ${e.componentId||''}`.toLowerCase().includes(query));
 if(desk.group)rows=rows.filter(e=>e.optionalGroup===desk.group);
 if(desk.filter==='pending')rows=rows.filter(e=>isPending(e.path,e.published));else if(desk.filter!=='all')rows=rows.filter(e=>e.preset===desk.filter);
 const direction=ascending.value?1:-1;
 return rows.sort((a,b)=>Number(b.directory)-Number(a.directory)||direction*(sort.value==='size'?a.size-b.size:sort.value==='version'?String(a.version||'').localeCompare(String(b.version||''),'zh-CN',{numeric:true}):sort.value==='preset'?effective(a).localeCompare(effective(b)):(a.displayName||a.name).localeCompare(b.displayName||b.name,'zh-CN',{numeric:true})));
});
const page=ref(0),pageSize=200;
const paged=computed(()=>pageEntries(visible.value,page.value,pageSize));
const pageCount=computed(()=>Math.max(1,Math.ceil(visible.value.length/pageSize)));
const hiddenSelection=computed(()=>selectionVisibility(desk.selection,visible.value,paged.value));
watch(visible,()=>{page.value=0;anchor.value='';});
const group=computed(()=>groups.value.find((g:any)=>g.id===desk.group));
const selected=computed(()=>desk.selection.length===1?desk.selection[0]:null);
const selectedPaths=computed(()=>new Set(desk.selection.map(e=>e.path)));
const selectionSize=computed(()=>desk.selection.reduce((n,e)=>n+e.size,0));
const commonPreset=computed(()=>{const found=new Set(desk.selection.map(effective));return found.size===1?[...found][0]:'';});
const memberGroup=computed(()=>desk.group||selected.value?.optionalGroup||'');
const explicitRule=computed(()=>desk.selection.some(e=>(desk.project?.maintenance?.presets||[]).some((r:any)=>r.path===e.path&&Boolean(r.directory)===e.directory)));
const breadcrumbs=computed(()=>desk.directory?desk.directory.split('/').map((name,i,parts)=>({name,path:parts.slice(0,i+1).join('/')})):[]);
const showInspector=computed(()=>desk.inspector&&desk.selection.length>0);
const pendingIn=(path:string)=>pendingCounts.value.get(path)||0;
function tag(entry:Entry){if(entry.directory)return null;const kind=changeKinds.value.get(entry.path);if(!entry.published||kind==='ADDED')return {label:'新增',tone:'added'};if(kind==='MODIFIED')return {label:'更新',tone:'modified'};return kind?{label:'待发布',tone:'policy'}:null;}
function subline(entry:Entry){if(entry.directory){const waiting=pendingIn(entry.path);return `${entry.count} 个文件${waiting?` · ${waiting} 项待发布`:''}`;}if(flat.value)return entry.path;return entry.componentId?entry.name:'';}
function kindLabel(entry:Entry){return entry.directory?'文件夹':entry.componentId?'Minecraft 模组':'托管文件';}
function groupTitle(id?:string|null){return groups.value.find((g:any)=>g.id===id)?.title||id||'';}
watch(()=>desk.inspector,value=>{localStorage.setItem('dfs-workbench-inspector',value?'on':'off');});
watch(()=>desk.directory,()=>{if(desk.menu==='tree')desk.menu='';document.querySelector('.file-scroll')?.scrollTo({top:0});});
function select(entry:Entry,event:MouseEvent){const rows=visible.value,start=rows.findIndex(e=>e.path===anchor.value);if(event.shiftKey&&start>=0){const end=rows.findIndex(e=>e.path===entry.path);desk.selection=rows.slice(Math.min(start,end),Math.max(start,end)+1);}else if(event.ctrlKey||event.metaKey){desk.selection=selectedPaths.value.has(entry.path)?desk.selection.filter(e=>e.path!==entry.path):[...desk.selection,entry];anchor.value=entry.path;}else{desk.selection=[entry];anchor.value=entry.path;}}
function check(entry:Entry,on:boolean){desk.selection=on?[...desk.selection.filter(e=>e.path!==entry.path),entry]:desk.selection.filter(e=>e.path!==entry.path);anchor.value=entry.path;}
function open(entry:Entry){if(entry.directory)navigate(entry.path);else{desk.selection=[entry];desk.inspector=true;}}
function order(key:string){if(sort.value===key)ascending.value=!ascending.value;else{sort.value=key;ascending.value=true;}}
function arrow(key:string){return sort.value===key?(ascending.value?'↑':'↓'):'';}
function filesChanged(event:Event,folder=false){const files=[...(event.target as HTMLInputElement).files||[]];addUploads(files,folder&&!desk.directory);(event.target as HTMLInputElement).value='';}
function dragEnter(event:DragEvent){if(event.dataTransfer?.types.includes('Files'))dragging.value++;}
function dropped(event:DragEvent){dragging.value=0;const files=[...event.dataTransfer?.files||[]];if(files.length)addUploads(files,true);}
function openContext(entry:Entry,x:number,y:number){if(!selectedPaths.value.has(entry.path)){desk.selection=[entry];anchor.value=entry.path;}context.value={x:Math.max(8,Math.min(x,innerWidth-262)),y:Math.max(8,Math.min(y,innerHeight-430))};desk.menu='context';}
function rowMenu(entry:Entry,event:MouseEvent){const rect=(event.currentTarget as HTMLElement).getBoundingClientRect();openContext(entry,rect.right-250,rect.bottom+6);}
async function setPreset(preset:string){desk.menu='';if(!desk.selection.length)return;if(desk.selection.some(e=>e.directory)){desk.dialog='preset-confirm';desk.dialogData={preset};return;}if(commonPreset.value===preset)return;await applyPreset(preset);}
async function leaveGroup(){desk.menu='';const id=memberGroup.value;await request(apiPath('/maintenance/group-members'),'POST',{groupId:id,items:desk.selection.map(e=>({path:e.path,directory:e.directory,modId:e.componentId})),add:false});await loadProject();notify('已从可选内容移出，检查并发布后生效');}
async function deleteGroup(){if(!confirm(`解散“${group.value?.title}”？文件会继续按维护方式同步。`))return;await operation(async()=>{await request(apiPath('/maintenance/group-delete'),'POST',{id:desk.group});desk.group='';await loadProject();notify('可选内容已解散，检查并发布后生效');});}
async function repair(){desk.menu='';if(!selected.value||selected.value.directory)return;await getHistoryFiles();desk.dialogData={path:selected.value.path,mode:'ONCE',reason:'',badSha256:[]};desk.dialog='correct';}
function pick(path:string){desk.filter='all';navigate(path);}
const inPlace=(path:string)=>!flat.value&&(desk.directory===path||desk.directory.startsWith(path+'/'));
function toggleInspector(){desk.inspector=!desk.inspector;if(desk.inspector&&!desk.selection.length)notify('选择文件后显示详情');}
function move(event:KeyboardEvent,index:number){const nextIndex=index+(event.key==='ArrowDown'?1:-1),next=visible.value[nextIndex];if(!next)return;event.preventDefault();if(event.shiftKey)check(next,true);else{desk.selection=[next];anchor.value=next.path;}page.value=Math.floor(nextIndex/pageSize);void nextTick(()=>{const row=document.querySelectorAll<HTMLElement>('.file-table tbody tr')[nextIndex%pageSize];row?.focus();row?.scrollIntoView({block:'nearest'});});}
function key(event:KeyboardEvent){
 if(desk.dialog||desk.palette)return;
 const typing=(event.target as Element|null)?.closest?.('input,textarea,select,[contenteditable]'),ctrl=event.ctrlKey||event.metaKey;
 if(ctrl&&event.key.toLowerCase()==='f'){event.preventDefault();searchInput.value?.focus();searchInput.value?.select();return;}
 if(typing)return;
 if(ctrl&&event.key.toLowerCase()==='a'){event.preventDefault();desk.selection=[...visible.value];return;}
 if(ctrl&&event.key.toLowerCase()==='i'){event.preventDefault();desk.inspector=!desk.inspector;return;}
 if(event.key==='Escape'&&!desk.menu&&desk.selection.length){desk.selection=[];return;}
 if(event.key==='Delete'&&desk.selection.length){event.preventDefault();desk.dialog='remove';return;}
 if(event.key==='Backspace'&&desk.directory&&!flat.value){event.preventDefault();navigate(parentOf(desk.directory));}
}
onMounted(()=>window.addEventListener('keydown',key));onUnmounted(()=>window.removeEventListener('keydown',key));
</script>
<template>
 <div class="view files-view">
  <section class="stage files-stage" @dragenter.prevent="dragEnter" @dragover.prevent @dragleave="dragging=Math.max(0,dragging-1)" @drop.prevent="dropped">
   <div class="files-main">
   <header class="stage-toolbar">
    <div class="capsule-group" role="group" aria-label="目录导航"><button class="icon-button" aria-label="后退" title="后退" :disabled="desk.historyIndex===0" @click="travel(-1)"><ArrowLeft :size="16"/></button><button class="icon-button" aria-label="前进" title="前进" :disabled="desk.historyIndex>=desk.historyPaths.length-1" @click="travel(1)"><ArrowRight :size="16"/></button><button class="icon-button" aria-label="返回上一级" title="返回上一级 · Alt ↑" :disabled="!desk.directory" @click="navigate(parentOf(desk.directory))"><ArrowUp :size="16"/></button></div>
    <div class="capsule-group crumbs menu-anchor" data-menu-root>
     <button class="icon-button tree-popover" aria-label="文件夹树" title="文件夹树" :aria-expanded="desk.menu==='tree'" @click="desk.menu=desk.menu==='tree'?'':'tree'"><TreeIcon :size="15"/></button>
     <nav class="path-bar" aria-label="当前目录"><button :class="{current:!desk.directory}" @click="pick('')">整合包</button><template v-for="part in breadcrumbs" :key="part.path"><ChevronRight :size="13"/><button :class="{current:part.path===desk.directory}" :title="part.path" @click="pick(part.path)">{{part.name}}</button></template></nav>
     <div v-if="desk.menu==='tree'" class="menu tree-menu" role="dialog" aria-label="文件夹树"><button class="tree-root" :class="{current:!desk.directory}" @click="pick('')"><FolderOpen :size="15"/>整合包<small>{{desk.files.length}}</small></button><ul class="folder-tree"><FolderTree v-for="root in places" :key="root.path" :path="root.path"/></ul><p v-if="!places.length" class="menu-empty">还没有文件夹</p></div>
    </div>
    <span class="toolbar-spacer"/>
    <label class="search-capsule" :class="{active:desk.search}"><Search :size="15"/><input ref="searchInput" v-model="desk.search" placeholder="搜索文件、模组或路径" aria-label="搜索文件"><button v-if="desk.search" class="icon-button" aria-label="清除搜索" @click="desk.search=''"><X :size="13"/></button><kbd v-else>Ctrl F</kbd></label>
    <div class="menu-anchor" data-menu-root><button class="capsule-button filter-capsule" :class="{tinted:desk.filter!=='all'}" :title="`筛选：${filters[desk.filter]}`" aria-haspopup="menu" :aria-expanded="desk.menu==='filter'" @click="desk.menu=desk.menu==='filter'?'':'filter'"><ListFilter :size="15"/><span>{{filters[desk.filter]}}</span><ChevronDown :size="13"/></button>
     <div v-if="desk.menu==='filter'" class="menu align-end" role="menu"><button v-for="(label,id) in filters" :key="id" role="menuitemradio" :aria-checked="desk.filter===id" @click="desk.filter=id;desk.menu='';desk.selection=[]">{{label}}<Check v-if="desk.filter===id" :size="15" class="menu-check"/></button></div></div>
    <div class="capsule-group menu-anchor" data-menu-root><button class="icon-button" :class="{toggled:desk.inspector}" :aria-pressed="desk.inspector" aria-label="文件详情" title="选中时显示详情 · Ctrl I" @click="toggleInspector"><Info :size="16"/></button><button class="icon-button" aria-label="更多" title="更多" :aria-expanded="desk.menu==='more'" @click="desk.menu=desk.menu==='more'?'':'more'"><Ellipsis :size="16"/></button>
     <div v-if="desk.menu==='more'" class="menu align-end" role="menu"><button role="menuitem" @click="desk.menu='';safely(loadProject)"><RefreshCw :size="15"/>刷新文件列表</button><hr><button role="menuitem" @click="desk.menu='';safely(()=>openView('changes','operations'))"><History :size="15"/>操作记录</button><button role="menuitem" :disabled="desk.busy||!desk.project?.releases?.length" @click="desk.menu='';safely(()=>restorePreview())"><Undo2 :size="15"/>恢复版本设置</button><button role="menuitem" @click="desk.menu='';safely(()=>openView('changes','problems'))"><ShieldAlert :size="15"/>历史问题文件</button></div></div>
    <div class="split-capsule menu-anchor" data-menu-root><button class="accent" data-import-files @click="uploads?.click()"><Upload :size="15"/><span>导入文件</span></button><button class="accent split-toggle" aria-label="更多导入方式" :aria-expanded="desk.menu==='import'" @click="desk.menu=desk.menu==='import'?'':'import'"><ChevronDown :size="14"/></button>
     <div v-if="desk.menu==='import'" class="menu align-end" role="menu"><button role="menuitem" @click="folders?.click();desk.menu=''"><FolderPlus :size="15"/>导入文件夹</button><button role="menuitem" @click="desk.menu='';desk.dialog='import-server';desk.dialogData={}"><Server :size="15"/>从服务器导入</button><hr><button role="menuitem" @click="desk.menu='';desk.dialog='directory';desk.dialogData={path:[desk.directory,'新建文件夹'].filter(Boolean).join('/')}"><Plus :size="15"/>新建文件夹</button></div></div>
    <input ref="uploads" type="file" multiple hidden @change="filesChanged($event)"><input ref="folders" type="file" multiple webkitdirectory hidden @change="filesChanged($event,true)">
   </header>
   <div class="scope-bar" role="toolbar" aria-label="快速位置">
    <div class="chip-row"><button class="chip" :class="{active:!flat&&!desk.directory}" @click="pick('')"><FolderOpen :size="14"/>整合包<small>{{desk.files.length}}</small></button><button v-for="item in places" :key="item.path" class="chip" :class="{active:inPlace(item.path)}" :title="item.path" @click="pick(item.path)">{{item.path}}<small>{{item.count}}</small></button><span class="chip-divider"/><button v-for="item in groups" :key="item.id" class="chip group-chip" :class="{active:desk.group===item.id}" :title="item.description||item.title" @click="desk.group=desk.group===item.id?'':item.id;desk.selection=[]"><Layers :size="14"/>{{item.title}}<small>{{item.count}}</small></button><button class="chip ghost" title="新建可选内容" @click="desk.dialog='group';desk.dialogData={}"><Plus :size="14"/>可选内容</button></div>
       </div>
   <div v-if="group" class="group-banner"><span class="banner-icon"><Layers :size="17"/></span><div><strong>{{group.title}}</strong><small>{{group.defaultInstall?'默认安装':'默认不安装'}} · {{group.count}} 项<template v-if="group.description"> · {{group.description}}</template></small></div><button @click="desk.dialog='group';desk.dialogData={...group}"><Pencil :size="14"/>编辑</button><button class="danger-text" @click="safely(deleteGroup)">解散</button><button class="icon-button" aria-label="显示全部文件" title="显示全部文件" @click="desk.group=''"><X :size="15"/></button></div>
   <div v-if="desk.sourceError" class="source-banner" role="alert"><ShieldAlert :size="17"/><p>{{desk.sourceError}}</p><button @click="safely(()=>openView('settings','project'))"><FolderOpen :size="14"/>更换整合包目录</button></div>
   <div class="file-scroll" :class="{'with-selection':desk.selection.length&&!showInspector}" @click.self="desk.selection=[]">
    <table class="file-table"><thead><tr><th class="check-cell"><input type="checkbox" aria-label="选择全部文件" :checked="visible.length>0&&visible.every(entry=>selectedPaths.has(entry.path))" :indeterminate="visible.some(entry=>selectedPaths.has(entry.path))&&!visible.every(entry=>selectedPaths.has(entry.path))" @change="desk.selection=($event.target as HTMLInputElement).checked?[...visible]:[]"></th><th class="sortable" :aria-sort="sort==='name'?(ascending?'ascending':'descending'):'none'" @click="order('name')">名称<span>{{arrow('name')}}</span></th><th class="sortable rule-col" @click="order('preset')">维护<span>{{arrow('preset')}}</span></th><th class="sortable right size-col" @click="order('size')">大小<span>{{arrow('size')}}</span></th><th class="sortable version-col" @click="order('version')">版本<span>{{arrow('version')}}</span></th><th class="row-menu-col"><span class="sr-only">操作</span></th></tr></thead>
     <tbody><tr v-for="(entry,index) in paged" :key="entry.path" :class="{selected:selectedPaths.has(entry.path)}" tabindex="0" @click="select(entry,$event)" @dblclick="open(entry)" @keydown.enter="open(entry)" @keydown.down="move($event,page*pageSize+index)" @keydown.up="move($event,page*pageSize+index)" @contextmenu.prevent="openContext(entry,$event.clientX,$event.clientY)">
      <td class="check-cell"><input type="checkbox" :aria-label="`选择 ${entry.path}`" :checked="selectedPaths.has(entry.path)" @click.stop @change="check(entry,($event.target as HTMLInputElement).checked)"></td>
      <td><div class="file-name"><FileIcon :name="entry.name" :directory="entry.directory"/><div><strong :title="entry.path">{{entry.displayName||entry.name}}<em v-if="tag(entry)" class="change-tag" :class="tag(entry)!.tone">{{tag(entry)!.label}}</em></strong><small v-if="subline(entry)">{{subline(entry)}}</small></div></div></td>
      <td class="rule-col"><span class="rule-tag" :class="effective(entry).toLowerCase()">{{presets[effective(entry)]?.label||'普通同步'}}</span><Layers v-if="entry.optionalGroup" :size="13" class="group-mark" :title="`可选内容：${groupTitle(entry.optionalGroup)}`"/></td>
      <td class="right subtle tabular size-col">{{bytes(entry.size)}}</td><td class="subtle version-col" :title="entry.version||''">{{entry.version||'—'}}</td>
      <td class="row-menu-col"><button class="icon-button row-menu" :aria-label="`更多操作 ${entry.name}`" @click.stop="rowMenu(entry,$event)"><Ellipsis :size="15"/></button><ChevronRight v-if="entry.directory" :size="15" class="row-chevron"/></td>
     </tr></tbody></table>
    <nav v-if="pageCount>1" class="file-pagination" aria-label="文件列表分页"><button :disabled="page===0" @click="page--">上一页</button><span>第 {{page+1}} / {{pageCount}} 页 · 每页 {{pageSize}} 项</span><button :disabled="page+1>=pageCount" @click="page++">下一页</button></nav>
    <p v-if="desk.selection.length&&(hiddenSelection.otherPages||hiddenSelection.outsideFilter)" class="list-footnote" role="status">选择包含其他页 {{hiddenSelection.otherPages}} 项、筛选外 {{hiddenSelection.outsideFilter}} 项；批量操作会一起处理。</p>
    <p v-if="visible.length" class="list-footnote">{{flat?`筛选出 ${visible.length} 项`:`${visible.length} 项`}} · 整合包共 {{desk.files.length}} 个文件 · {{bytes(desk.files.reduce((n,f)=>n+f.size,0))}}</p>
    <div v-if="!visible.length&&!desk.sourceError" class="empty-list"><Fish variant="scene" class="empty-fish"/><strong>{{flat?'没有找到匹配的文件':'这个目录还没有文件'}}</strong><span>{{flat?'换一个名称、路径或筛选条件试试':'把文件拖到这里，或使用右上角的“导入文件”'}}</span><button v-if="flat" @click="desk.search='';desk.filter='all';desk.group=''">清除搜索与筛选</button><button v-else-if="desk.directory" @click="navigate(parentOf(desk.directory))"><CornerLeftUp :size="14"/>返回上一级</button></div>
   </div>
   <transition name="rise"><div v-if="desk.selection.length&&!showInspector" class="selection-bar" role="toolbar" aria-label="所选内容操作">
    <button class="icon-button" aria-label="取消选择" title="取消选择 · Esc" @click="desk.selection=[]"><X :size="15"/></button>
    <div class="selection-count"><strong>已选 {{desk.selection.length}} 项</strong><small>{{bytes(selectionSize)}}</small></div><span class="bar-divider"/>
    <div class="segmented compact" role="group" aria-label="维护方式"><button v-for="key in maintenance" :key="key" :class="{active:commonPreset===key}" :aria-pressed="commonPreset===key" :title="presets[key].description" @click="safely(()=>setPreset(key))">{{presets[key].label}}</button></div><span class="bar-divider"/>
    <button class="bar-action" title="加入可选内容" @click="desk.dialog='group-members';desk.dialogData={}"><Layers :size="15"/><span>可选内容</span></button><button class="bar-action" title="详情 · Ctrl I" :class="{toggled:desk.inspector}" :aria-pressed="desk.inspector" @click="desk.inspector=!desk.inspector"><Info :size="15"/><span>详情</span></button><button class="bar-action danger" title="移出整合包" @click="desk.dialog='remove'"><Trash2 :size="15"/><span>移出</span></button>
   </div></transition>
   </div>
   <div v-if="dragging" class="drop-overlay"><div><Upload :size="30"/><strong>松开以导入</strong><span>保存到 {{desk.directory?desk.directory+'/':'整合包根目录'}}，先检查再确认</span></div></div>
  </section>
  <transition name="slide"><aside v-if="showInspector" class="side-card inspector" aria-label="文件详情">
   <header class="card-header"><FileIcon :name="selected?.name" :directory="selected?.directory" :size="40"/><div><strong>{{selected?(selected.displayName||selected.name):`${desk.selection.length} 项内容`}}</strong><small>{{selected?kindLabel(selected):'批量设置'}}</small></div><button class="icon-button" aria-label="关闭文件详情" title="关闭详情 · Ctrl I" @click="desk.inspector=false"><X :size="16"/></button></header>
   <div class="card-scroll">
    <dl class="fact-list"><template v-if="selected"><dt>路径</dt><dd class="mono">{{selected.path.replaceAll('/','/\u200b')}}</dd><template v-if="selected.directory"><dt>文件</dt><dd>{{selected.count}} 个 · {{pendingIn(selected.path)}} 项待发布</dd></template><dt>大小</dt><dd>{{bytes(selected.size)}}</dd><template v-if="selected.version"><dt>版本</dt><dd>{{selected.version}}</dd></template><template v-if="selected.componentId"><dt>模组 ID</dt><dd class="mono">{{selected.componentId}}</dd></template><template v-if="!selected.directory"><dt>状态</dt><dd><span v-if="tag(selected)" class="change-tag" :class="tag(selected)!.tone">{{tag(selected)!.label}}</span><span v-else>与当前发布一致</span></dd></template></template><template v-else><dt>数量</dt><dd>{{desk.selection.length}} 项</dd><dt>总大小</dt><dd>{{bytes(selectionSize)}}</dd></template></dl>
    <section class="card-section"><h3>维护方式</h3><div class="option-list" role="radiogroup" aria-label="维护方式"><button v-for="key in maintenance.concat(commonPreset==='DEFAULT_CONFIG'?['DEFAULT_CONFIG']:[])" :key="key" role="radio" :aria-checked="commonPreset===key" :class="{active:commonPreset===key}" :disabled="desk.busy||key==='DEFAULT_CONFIG'" @click="safely(()=>setPreset(key))"><span class="option-radio"/><span><strong>{{presets[key].label}}</strong><small>{{presets[key].description}}</small></span></button></div>
     <p class="card-note"><Shield :size="13"/>{{selected?.directory?(explicitRule?'单独为这个文件夹设置':'未单独设置，跟随上级或默认'):selected?presetSourceLabel(selected.presetSource):commonPreset?'所选内容使用相同的维护方式':'所选内容使用不同的维护方式'}}</p><button v-if="explicitRule" class="text-action" :disabled="desk.busy" @click="safely(()=>applyPreset(null))">改为继承上级设置</button></section>
    <section class="card-section"><h3>可选内容</h3><p v-if="memberGroup" class="group-line"><Layers :size="14"/>{{groupTitle(memberGroup)}}</p><p v-else class="card-note">未加入可选内容，玩家会按维护方式获得。</p><div class="card-actions"><button @click="desk.dialog='group-members';desk.dialogData={}"><Plus :size="14"/>加入可选内容</button><button v-if="memberGroup" @click="safely(leaveGroup)">移出这组</button></div></section>
   </div>
   <footer class="card-footer"><button v-if="selected&&!selected.directory" @click="safely(repair)"><ShieldAlert :size="15"/>修复玩家副本</button><button class="danger-soft" @click="desk.dialog='remove'"><Trash2 :size="15"/>移出整合包</button></footer>
  </aside></transition>
  <div v-if="desk.menu==='context'" class="menu context-menu" data-menu-root role="menu" :style="{left:`${context.x}px`,top:`${context.y}px`}">
   <span class="menu-label">{{selected?(selected.displayName||selected.name):`已选 ${desk.selection.length} 项`}}</span>
   <button v-if="selected?.directory" role="menuitem" @click="desk.menu='';navigate(selected.path)"><FolderOpen :size="15"/>打开文件夹</button><button role="menuitem" @click="desk.menu='';desk.inspector=true"><Info :size="15"/>查看详情<kbd>Ctrl I</kbd></button><hr>
   <button v-for="key in maintenance" :key="key" role="menuitemradio" :aria-checked="commonPreset===key" @click="safely(()=>setPreset(key))"><span class="menu-swatch" :class="key.toLowerCase()"/>{{presets[key].label}}<Check v-if="commonPreset===key" :size="15" class="menu-check"/></button>
   <button v-if="explicitRule" role="menuitem" @click="desk.menu='';safely(()=>applyPreset(null))"><CornerLeftUp :size="15"/>继承上级设置</button><hr>
   <button role="menuitem" @click="desk.menu='';desk.dialog='group-members';desk.dialogData={}"><Layers :size="15"/>加入可选内容</button><button v-if="memberGroup" role="menuitem" @click="safely(leaveGroup)"><X :size="15"/>从可选内容移出</button><button v-if="selected&&!selected.directory" role="menuitem" @click="safely(repair)"><ShieldAlert :size="15"/>修复玩家副本</button><hr>
   <button role="menuitem" class="danger-text" @click="desk.menu='';desk.dialog='remove'"><Trash2 :size="15"/>移出整合包<kbd>Del</kbd></button>
  </div>
 </div>
</template>
