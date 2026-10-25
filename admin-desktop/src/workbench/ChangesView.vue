<script setup lang="ts">
import { computed,ref,watch } from 'vue';
import { RefreshCw,Upload,Plus,Minus,ArrowRight,History,Undo2,ShieldAlert,Search,ListFilter,ChevronDown,Check,X,TriangleAlert,PackageCheck,Settings2,FileText,Rocket,Copy,Palette } from '@lucide/vue';
import { desk,safely,scan,publish,request,apiPath,loadProject,history,restorePreview,getHistoryFiles,notify,operation,openView } from './store';
import { bytes,when,nameOf,newestReleases,changedFileSize,type Data } from './model';
import { policyPresentation,operationKinds,viewSections,distinctWarnings,sectionLabel } from './presentation';
import Segmented from './Segmented.vue';
import Fish from './Fish.vue';
const filter=ref('all'),ack=ref(false),composing=ref(false),selectedRelease=ref<Data|null>(null),reason=ref(''),badVersions=ref<Data[]>([]);
const preview=computed(()=>desk.project?.preview);
const fileSize=(change:Data)=>changedFileSize(change,preview.value?.files,desk.releaseFiles);
const changedBytes=computed(()=>preview.value?.changedFileBytes??(preview.value?.changes||[]).filter((c:Data)=>c.kind==='ADDED'||c.kind==='MODIFIED').reduce((total:number,c:Data)=>total+(fileSize(c)||0),0));
const policies=computed(()=>(preview.value?.policyChanges||[]).map((c:Data)=>policyPresentation(c,desk.project?.maintenance)));
const fileChanges=computed(()=>(preview.value?.changes||[]).filter((c:Data)=>c.kind!=='POLICY_CHANGED'||!policies.value.some((p:any)=>p.subject===c.path)));
const changes=computed(()=>fileChanges.value.filter((c:Data)=>filter.value==='all'||c.kind===filter.value));
// Player design differences from the latest release; live ones already reach players after saving.
const designs=computed<Data[]>(()=>preview.value?.presentationChanges||[]);
const total=computed(()=>fileChanges.value.length+policies.value.length+designs.value.length);
const warnings=computed(()=>distinctWarnings(preview.value?.warnings));
const operationSearch=ref(''),operationFilter=ref('all');
const operations=computed(()=>desk.operations.filter(item=>(operationFilter.value==='all'||item.kind===operationFilter.value)&&`${item.title} ${JSON.stringify(item.changes)}`.toLowerCase().includes(operationSearch.value.toLowerCase())));
const releases=computed(()=>newestReleases(desk.project?.releases));
const release=computed(()=>selectedRelease.value||releases.value[0]);
const count=(kind:string)=>(preview.value?.changes||[]).filter((c:Data)=>c.kind===kind).length;
const kinds:Record<string,string>={ADDED:'新增',MODIFIED:'更新',REMOVED:'移除',METADATA_CHANGED:'信息',POLICY_CHANGED:'维护方式'};
const filters=computed(()=>[{id:'all',label:'全部',count:total.value},{id:'ADDED',label:'新增',count:count('ADDED')},{id:'MODIFIED',label:'更新',count:count('MODIFIED')},{id:'REMOVED',label:'移除',count:count('REMOVED')}]);
const markers:Record<string,any>={FILES:FileText,SETTINGS:Settings2,PUBLISH:Rocket};
const operationFilters:Record<string,string>={all:'全部操作',FILES:'文件操作',SETTINGS:'设置修改',PUBLISH:'版本发布'};
async function removal(path:string,action:string){const p=preview.value;const decisions=p.changes.filter((c:Data)=>c.kind==='REMOVED').map((c:Data)=>({path:c.path,action:c.path===path?action:c.removalAction})).filter((c:Data)=>c.action);desk.project!.preview=await request(apiPath('/removals'),'POST',{previewId:p.previewId,previewDigest:p.previewDigest,decisions});}
function rollback(target:Data){desk.dialog='rollback';desk.dialogData={release:target,version:'',notes:''};}
function problemAction(){if(!badVersions.value.length)return;desk.dialog='problem-confirm';desk.dialogData={versions:badVersions.value,reason:reason.value};}
function copy(text:string){void navigator.clipboard?.writeText(text).then(()=>notify('已复制'));}
const historyRelease=ref(''),historySearch=ref('');
const historyFiles=computed(()=>desk.releaseFiles.map(file=>({...file,path:file.path,versions:(file.versions||[]).filter((v:Data)=>!historyRelease.value||v.releaseIds?.includes(historyRelease.value))})).filter(f=>f.versions.length&&f.path.toLowerCase().includes(historySearch.value.toLowerCase())));
const requirements=computed(()=>(desk.project?.maintenance?.withdrawals?.length||0)+(desk.project?.maintenance?.corrections?.length||0));
const isBad=(path:string,sha:string)=>badVersions.value.some(v=>v.sha256===sha&&v.path===path);
function toggleBad(path:string,sha:string,on:boolean){badVersions.value=on?[...badVersions.value,{path,sha256:sha}]:badVersions.value.filter(v=>v.sha256!==sha||v.path!==path);}
watch(()=>desk.projectId,()=>{selectedRelease.value=null;badVersions.value=[];historyRelease.value='';});
async function revoke(id:string,correction=false){if(!confirm('结束这个处理要求？发布后生效。'))return;await operation(async()=>{await request(apiPath(correction?'/maintenance/correction-revoke':'/maintenance/withdrawal-revoke'),'POST',{id});await loadProject();await getHistoryFiles();notify('处理要求已结束，检查并发布后生效');});}
</script>
<template>
 <div class="view changes-view">
  <section class="stage">
   <header class="stage-toolbar">
    <Segmented class="section-tabs" :items="viewSections.changes" :model-value="desk.section" label="改动与版本" @update:model-value="safely(()=>openView('changes',$event))"/><strong class="section-title">{{sectionLabel('changes',desk.section)}}</strong>
    <span class="toolbar-spacer"/>
    <template v-if="desk.section==='changes'"><span v-if="preview" class="toolbar-note">检查于 {{when(preview.createdAt)}}</span><button class="capsule-button" :disabled="desk.busy" @click="safely(scan)"><RefreshCw :size="15" :class="{spin:desk.busy}"/>重新检查</button></template>
    <template v-else-if="desk.section==='releases'"><span class="toolbar-note">{{releases.length}} 个发布版本 · 不可变</span></template>
    <template v-else-if="desk.section==='operations'"><label class="search-capsule"><Search :size="15"/><input v-model="operationSearch" placeholder="搜索文件、设置或操作" aria-label="搜索操作记录"></label>
     <div class="menu-anchor" data-menu-root><button class="capsule-button" :class="{tinted:operationFilter!=='all'}" aria-haspopup="menu" :aria-expanded="desk.menu==='operation-filter'" @click="desk.menu=desk.menu==='operation-filter'?'':'operation-filter'"><ListFilter :size="15"/>{{operationFilters[operationFilter]}}<ChevronDown :size="13"/></button><div v-if="desk.menu==='operation-filter'" class="menu align-end" role="menu"><button v-for="(label,id) in operationFilters" :key="id" role="menuitemradio" :aria-checked="operationFilter===id" @click="operationFilter=id;desk.menu=''">{{label}}<Check v-if="operationFilter===id" :size="15" class="menu-check"/></button></div></div>
     <div class="capsule-group"><button class="icon-button" aria-label="恢复当前发布设置" title="恢复当前发布设置" :disabled="desk.busy||!releases.length" @click="safely(()=>restorePreview())"><Undo2 :size="15"/></button><button class="icon-button" aria-label="刷新操作记录" title="刷新" :disabled="desk.historyLoading" @click="safely(history)"><RefreshCw :size="15" :class="{spin:desk.historyLoading}"/></button></div></template>
    <template v-else><div class="select-capsule"><select v-model="historyRelease" aria-label="按发布版本查看历史"><option value="">全部发布版本</option><option v-for="r in releases" :key="r.releaseId" :value="r.releaseId">{{r.displayVersion}} · #{{r.sequence}}</option></select><ChevronDown :size="13"/></div><label class="search-capsule"><Search :size="15"/><input v-model="historySearch" aria-label="搜索历史文件" placeholder="搜索历史文件路径"></label><button class="capsule-button icon-only" aria-label="刷新历史" title="刷新历史" @click="safely(getHistoryFiles)"><RefreshCw :size="15"/></button></template>
   </header>

   <template v-if="desk.section==='changes'">
    <div class="change-summary"><div class="chip-row"><button v-for="item in filters" :key="item.id" class="chip" :class="['tone-'+item.id.toLowerCase(),{active:filter===item.id}]" @click="filter=item.id"><Plus v-if="item.id==='ADDED'" :size="13"/><ArrowRight v-else-if="item.id==='MODIFIED'" :size="13"/><Minus v-else-if="item.id==='REMOVED'" :size="13"/>{{item.label}}<small>{{item.count}}</small></button><span v-if="policies.length" class="chip static"><Settings2 :size="13"/>维护设置<small>{{policies.length}}</small></span><span v-if="designs.length" class="chip static"><Palette :size="13"/>玩家端设计<small>{{designs.length}}</small></span></div><span class="scope-summary">新增 / 更新文件 <strong>{{bytes(changedBytes)}}</strong></span></div>
    <div class="list-scroll"><div class="diff-list">
     <article v-for="change in changes" :key="change.path" class="diff-row"><span class="diff-badge" :class="change.kind.toLowerCase()"><Minus v-if="change.kind==='REMOVED'" :size="15"/><Plus v-else-if="change.kind==='ADDED'" :size="15"/><ArrowRight v-else :size="15"/></span><div class="diff-copy"><strong>{{change.displayName||nameOf(change.path)}}</strong><small>{{change.path}}</small><p v-if="change.previousVersion&&change.version" class="version-shift">{{change.previousVersion}}<ArrowRight :size="11"/>{{change.version}}</p></div><span class="kind-tag" :class="change.kind.toLowerCase()">{{kinds[change.kind]||'设置'}}</span><span class="diff-size tabular">{{fileSize(change)!==null?bytes(fileSize(change)!):'—'}}</span><div v-if="change.kind==='REMOVED'" class="select-capsule removal-select" :class="{unset:!change.removalAction}"><select :value="change.removalAction||''" aria-label="移除后如何处理" @change="safely(()=>removal(change.path,($event.target as HTMLSelectElement).value))"><option value="" disabled>选择处理方式</option><option value="DELETE">移除玩家副本</option><option value="RELEASE">留给玩家</option></select><ChevronDown :size="13"/></div></article>
     <article v-for="(rule,i) in policies" v-show="filter==='all'" :key="'policy-'+i" class="diff-row policy"><span class="diff-badge policy"><ShieldAlert :size="15"/></span><div class="diff-copy"><span class="policy-category">{{rule.label}}</span><strong>{{rule.subject}}</strong><small class="wrap">{{rule.detail}}</small></div></article>
     <article v-for="item in designs" v-show="filter==='all'" :key="'design-'+item.section+'-'+item.live" class="diff-row design"><span class="diff-badge design"><Palette :size="15"/></span><div class="diff-copy"><span class="policy-category">玩家端设计 · {{item.title}}</span><strong>{{item.items.join('、')}}</strong><small class="wrap">{{item.live?'保存后已对玩家生效，发布新版本时会一并写入':'发布新版本后玩家才会看到'}}</small></div><span class="kind-tag" :class="item.live?'live':'pending'">{{item.live?'已生效':'待发布'}}</span><button type="button" class="text-action" @click="safely(()=>openView('appearance',item.section))">查看<ArrowRight :size="13"/></button></article>
     <div v-if="!changes.length&&(filter!=='all'||!policies.length&&!designs.length)" class="empty-list"><Fish variant="scene" class="empty-fish"/><strong>{{desk.busy?'正在检查…':filter!=='all'?'没有这类改动':'与当前发布一致'}}</strong><span>修改文件、维护设置或玩家端设计后，变化会显示在这里。</span></div>
    </div></div>
   </template>

   <div v-else-if="desk.section==='releases'" class="release-split">
    <div v-if="releases.length" class="release-list" role="listbox" aria-label="发布版本"><button v-for="(item,i) in releases" :key="item.releaseId" role="option" :aria-selected="release?.releaseId===item.releaseId" :class="{current:release?.releaseId===item.releaseId}" @click="selectedRelease=item"><span class="release-dot" :class="{latest:i===0}"/><span class="release-copy"><strong>{{item.displayVersion}}</strong><small>{{when(item.createdAt)}} · #{{item.sequence}}</small></span><span v-if="i===0" class="current-tag">当前</span></button></div>
    <article v-if="release" class="release-detail"><span class="overline">不可变发布 · #{{release.sequence}}</span><h2>{{release.displayVersion}}</h2><p class="release-time">发布于 {{when(release.createdAt)}}</p>
     <div class="detail-actions"><button @click="rollback(release)"><Undo2 :size="15"/>以此版本创建回滚发布</button><button @click="safely(()=>openView('delivery','deployment'))"><PackageCheck :size="15"/>发放部署包</button></div>
     <section class="notes-card"><h3><FileText :size="15"/>更新说明</h3><pre class="release-notes">{{release.changelog||'此版本没有填写更新说明。'}}</pre></section>
     <dl class="grouped-facts"><div><dt>发布标识</dt><dd class="mono">{{release.releaseId}}</dd><button class="icon-button" aria-label="复制发布标识" title="复制" @click="copy(release.releaseId)"><Copy :size="14"/></button></div><div><dt>清单 SHA-256</dt><dd class="mono">{{release.manifestSha256}}</dd><button class="icon-button" aria-label="复制清单摘要" title="复制" @click="copy(release.manifestSha256)"><Copy :size="14"/></button></div></dl>
    </article>
    <div v-else class="empty-list"><span class="empty-art"><History :size="26"/></span><strong>还没有发布记录</strong><span>检查改动，发布第一个版本。</span><button class="accent" @click="safely(()=>openView('changes','changes'))">查看待发布改动</button></div>
   </div>

   <div v-else-if="desk.section==='operations'" class="list-scroll"><div class="timeline">
    <article v-for="item in operations" :key="item.id" :class="{undone:item.undone}"><span class="timeline-marker" :class="String(item.kind||'').toLowerCase()"><component :is="markers[item.kind]||History" :size="15"/></span><div class="timeline-copy"><div class="timeline-head"><strong>{{item.title||'工作区操作'}}</strong><span class="kind-tag">{{item.undone?'已撤销':operationKinds[item.kind]||'其他操作'}}</span></div><small>{{when(item.createdAt)}}</small><details v-if="item.changes?.length"><summary>查看 {{item.changes.length}} 项变化</summary><div class="operation-diffs"><div v-for="(change,i) in item.changes" :key="i"><strong>{{change.setting}}</strong><span>{{change.before||'未设置'}}</span><ArrowRight :size="13"/><span>{{change.after||'未设置'}}</span></div></div></details><p v-if="item.undoUnavailableReason" class="operation-unavailable">{{item.undoUnavailableReason}}</p></div><button v-if="!item.undone&&item.canUndo" class="capsule-button" :disabled="desk.busy" @click="safely(()=>restorePreview(item.id))"><Undo2 :size="14"/>撤销</button></article>
    <div v-if="!operations.length" class="empty-list"><span class="empty-art"><History :size="26"/></span><strong>{{desk.historyLoading?'正在读取操作记录…':operationSearch||operationFilter!=='all'?'没有匹配的记录':'还没有操作记录'}}</strong><span>文件与设置的改动会保留在这里。</span></div>
   </div></div>

   <template v-else>
    <div class="list-scroll" :class="{'with-selection':badVersions.length}">
     <div class="notice-card warning"><TriangleAlert :size="19"/><p>从过去的发布中选择坏版本。即使文件早已停止维护，要求仍会在发布后处理匹配的玩家副本。</p></div>
     <div class="history-files"><article v-for="file in historyFiles" :key="file.path" class="history-file"><strong :title="file.path">{{file.path}}</strong><label v-for="version in file.versions||[]" :key="version.sha256" class="history-version" :class="{chosen:isBad(file.path,version.sha256),disabled:Boolean(version.withdrawnBy)}"><input type="checkbox" :disabled="Boolean(version.withdrawnBy)" :checked="isBad(file.path,version.sha256)" @change="toggleBad(file.path,version.sha256,($event.target as HTMLInputElement).checked)"><span>{{version.version||version.sha256.slice(0,10)}}</span><small>{{bytes(version.size)}} · {{version.firstRelease}} 至 {{version.lastRelease}}</small><em v-if="version.withdrawnBy" class="kind-tag">已在处理</em></label></article>
      <div v-if="!historyFiles.length" class="empty-list"><span class="empty-art"><Search :size="26"/></span><strong>没有找到历史文件</strong><span>刷新历史或换一个发布版本。</span></div></div>
     <details class="disclosure-card"><summary>正在生效的处理要求<span class="count-pill">{{requirements}}</span></summary><div v-for="rule in desk.project?.maintenance?.withdrawals||[]" :key="rule.id" class="requirement-row"><div><strong>{{rule.reason||'移除要求'}}</strong><small>{{rule.items?.map((i:any)=>i.path).join('、')}}</small></div><button @click="safely(()=>revoke(rule.id))">结束要求</button></div><div v-for="rule in desk.project?.maintenance?.corrections||[]" :key="rule.id" class="requirement-row"><div><strong>{{rule.path}}</strong><small>{{rule.reason||'文件修复'}}</small></div><button @click="safely(()=>revoke(rule.id,true))">结束要求</button></div><p v-if="!requirements" class="card-note">目前没有生效中的处理要求。</p></details>
    </div>
    <transition name="rise"><div v-if="badVersions.length" class="selection-bar problem-bar" role="toolbar" aria-label="坏版本处理"><button class="icon-button" aria-label="清除选择" @click="badVersions=[]"><X :size="15"/></button><div class="selection-count"><strong>已选 {{badVersions.length}} 个版本</strong><small>发布后处理玩家副本</small></div><span class="bar-divider"/><input v-model="reason" class="bar-input" placeholder="问题说明，例如会导致崩溃" aria-label="问题说明"><button class="accent" @click="problemAction">处理坏版本</button></div></transition>
   </template>
   <!-- Narrow windows fold the composer away so it never covers the change list. -->
   <button v-if="desk.section==='changes'&&!composing" class="accent compose-fab narrow-only" @click="composing=true"><Rocket :size="16"/>发布新版本<small v-if="total">{{total}}</small></button>
  </section>

  <aside v-if="desk.section==='changes'" class="side-card composer" :class="{open:composing}" aria-label="发布新版本">
   <header class="card-header plain"><span class="header-icon accent"><Rocket :size="17"/></span><div><strong>准备新版本</strong><small>{{total?`${total} 项改动将发给玩家`:'没有待发布的改动'}}</small></div><button class="icon-button narrow-only" aria-label="收起" title="收起" @click="composing=false"><X :size="16"/></button></header>
   <div class="card-scroll"><label>版本号<input v-model="desk.releaseVersion" placeholder="例如 1.0.1" required></label><label>更新说明<textarea v-model="desk.releaseNotes" rows="7" placeholder="告诉玩家，这次更新了什么"></textarea></label>
    <div v-if="!desk.project?.publicBaseUrl" class="notice-card warning compact"><TriangleAlert :size="16"/><div><p>还没有填写玩家更新地址。发布前请先填写，玩家端要从这个地址获取更新。</p><p><button class="text-action" @click="safely(()=>openView('settings','project'))">去填写<ArrowRight :size="13"/></button></p></div></div>
    <div v-if="warnings.length" class="notice-card warning compact"><TriangleAlert :size="16"/><div><p v-for="(warning,i) in warnings" :key="i">{{warning}}</p></div></div>
    <label v-if="preview?.requiresPlayerUpgrade" class="check"><input v-model="ack" type="checkbox">玩家将获得 {{preview.policyPlayerVersion}} 或更新的玩家端</label></div>
   <footer class="card-footer stacked"><button class="accent large full" :disabled="desk.busy||!preview||!desk.releaseVersion||!desk.project?.publicBaseUrl||(preview.requiresPlayerUpgrade&&!ack)" @click="safely(()=>publish(ack))"><Upload :size="16"/>发布{{desk.releaseVersion?` ${desk.releaseVersion}`:'新版本'}}</button><span>最低玩家端 {{preview?.policyPlayerVersion||'0.2.0'}} · 发布后玩家下次启动时更新</span></footer>
  </aside>
 </div>
</template>
