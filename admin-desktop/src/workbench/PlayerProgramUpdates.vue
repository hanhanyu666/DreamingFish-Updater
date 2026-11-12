<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {desk,request,apiPath,operation,safely,loadProject,openView,notify,queryJob,cancelJob,requireTaskReview} from './store';
import {bytes,when,type Data} from './model';
import {playerUpdatePresentation,playerDeliveryPresentation,playerUpgradeChoices,officialOffer,autoPublishPresentation,uploadReminder,autoUploads,downloadProgress} from './player-update-presentation';
const props=defineProps<{platform:string;programs:Data[]}>();
const catalog=ref<Data|null>(null),catalogProblem=ref(''),publishing=ref(false),stopped=ref('');
const policy=computed(()=>desk.project?.playerUpdatePolicy);
const delivery=computed(()=>desk.project?.externalDelivery);
const auto=computed(()=>desk.project?.officialAutoPublish);
const saved=computed(()=>desk.project?.savedHosting);
const status=computed(()=>playerUpdatePresentation(policy.value,props.platform));
const uploadStatus=computed(()=>playerDeliveryPresentation(policy.value,delivery.value,saved.value));
const autoStatus=computed(()=>autoPublishPresentation(auto.value));
const release=computed(()=>catalog.value?.releases?.find((r:Data)=>r.platform===props.platform));
const offer=computed(()=>officialOffer(release.value,status.value.target));
const upgradeChoices=computed(()=>playerUpgradeChoices(props.programs,status.value.target));
const downloadJob=computed(()=>desk.jobs.find(job=>job.kind==='official-player-publish'&&job.projectId===desk.projectId&&['QUEUED','RUNNING','COMMITTING'].includes(job.state)));
const download=computed(()=>downloadProgress(downloadJob.value));
watch(()=>desk.projectId,()=>{catalog.value=null;catalogProblem.value='';});
// After a reload, or once publish() stops waiting, the card keeps following a download that is still running.
let following='';
watch(()=>publishing.value?'':downloadJob.value?.id||'',id=>{if(!id||following===id)return;following=id;const project=desk.projectId,version=release.value?.version||'';safely(async()=>{try{await queryJob(id,true);if(desk.projectId!==project)return;await loadProject();notify(`已发布玩家端 ${version}。${uploadReminder(saved.value)}`);}catch(error){if(stopped.value!==id)throw error;}finally{following='';}});},{immediate:true});
const resumeNote=computed(()=>status.value.hasTargets&&!status.value.enabled?'\n这也会恢复本项目已暂停的玩家端升级。':'');
function confirmPublish(version:string,downloads=false){return confirm(`把玩家端 ${version} 发布给玩家吗？\n玩家下次打开更新器时自动升级，服务器名称、背景和公告等个性化设置保留。${resumeNote.value}${downloads?'\n管理服务会从 GitHub 下载官方程序；国内服务器没有代理时可能需要几十分钟，期间可以继续使用管理端。':''}\n${autoUploads(saved.value)?'管理端随后自动上传到已保存的外部托管。':'如果玩家从 OSS 等外部位置下载，还需要重新导出并上传。'}`);}
async function check(){catalogProblem.value='';await operation(async()=>{try{catalog.value=await request(apiPath('/official-player-check'),'POST',{refresh:true});}catch(error){catalog.value=null;catalogProblem.value=error instanceof Error?error.message:String(error);}},'正在检查官方新版…');}
// The download may take most of an hour without a proxy, so it does not lock the rest of the workspace.
async function publish(){const target=release.value;if(!target||publishing.value||!confirmPublish(target.version,true))return;requireTaskReview();publishing.value=true;stopped.value='';desk.error='';notify('已开始下载官方程序，下载期间可以继续使用管理端');try{await request(apiPath('/official-player-publish'),'POST',{platform:props.platform,version:target.version,archiveSha256:target.archiveSha256,expectedRevision:policy.value?.revision});await loadProject();notify(`已发布玩家端 ${target.version}。${uploadReminder(saved.value)}`);}catch(error){if(!stopped.value)throw error;}finally{publishing.value=false;}}
// Stopping is allowed only while downloading; the service refuses it once the program is being stored.
async function stop(){const job=downloadJob.value;if(!job||!confirm('终止下载？已下载的部分会删除，下次发布需要重新下载。玩家和当前版本不受影响。'))return;stopped.value=job.id;try{await cancelJob(job);}catch(error){stopped.value='';throw error;}notify('已终止下载，没有发布任何内容');}
async function approve(program:Data){if(!confirmPublish(program.version))return;await operation(async()=>{await request(apiPath('/program-approval'),'POST',{platform:props.platform,version:program.version,manifestSha256:program.manifestSha256,expectedRevision:policy.value?.revision});await loadProject();notify(`已发布玩家端 ${program.version}。${uploadReminder(saved.value)}`);});}
async function toggle(){const enabled=!policy.value?.enabled;await operation(async()=>{await request(apiPath('/program-update-policy'),'POST',{enabled,expectedRevision:policy.value?.revision});await loadProject();notify(`${enabled?'已恢复本项目的玩家端升级':'已暂停后续程序升级，已升级的玩家不会退回旧版'}。${uploadReminder(saved.value)}`);});}
async function setAuto(enabled:boolean){await operation(async()=>{await request(apiPath('/official-auto-publish'),'POST',{enabled});await loadProject();notify(enabled?'已开启自动发布，管理端稍后会检查一次官方新版':'已关闭自动发布');});}
</script>
<template>
 <section class="form-card player-update-card" aria-label="玩家端程序更新">
  <header class="player-update-header"><strong>玩家端程序更新</strong><span class="current-tag">{{status.badge}}</span><button v-if="status.enabled||status.hasTargets" :disabled="desk.busy||!policy" :aria-pressed="status.enabled" @click="safely(toggle)">{{status.enabled?'暂停玩家端升级':'恢复玩家端升级'}}</button></header>
  <p class="player-update-intro">这里更新的是玩家电脑上的<strong>梦鱼更新器程序</strong>，用于获得新功能和问题修复。服务器名称、背景、公告等个性化设置会保留；模组和配置继续按原来的方式发布。</p>
  <div class="player-update-state" role="status"><strong>{{status.title}}</strong><p>{{status.detail}}</p></div>
  <div class="form-actions player-update-check"><button :class="{accent:!offer?.newer}" :disabled="desk.busy" @click="safely(check)">检查官方新版</button><template v-if="offer"><strong>{{offer.label}}</strong><span v-if="offer.newer" class="field-help">{{bytes(release!.archiveSize)}}</span><button v-if="offer.newer" class="accent" :disabled="desk.busy||publishing" @click="safely(publish)">{{publishing?'正在发布…':'发布给玩家'}}</button></template></div>
  <div v-if="download" class="player-download-progress" role="progressbar" aria-label="官方程序下载进度" :aria-valuenow="download.percent" aria-valuemin="0" aria-valuemax="100"><div><strong>{{download.phase}}</strong><span>{{bytes(download.done)}} / {{bytes(download.total)}} · {{download.percent}}%</span><button v-if="download.percent<100" class="text-action" :disabled="stopped===downloadJob?.id" @click="safely(stop)">终止下载</button></div><span class="progress-track"><span :style="{width:download.percent+'%'}"/></span><small>国内服务器没有代理时可能很慢，断线后会从已下载的位置继续，最长等待 6 小时；可以继续使用管理端的其他功能，也可以关闭页面。</small></div>
  <p v-if="catalogProblem" class="field-help error-copy" role="status">{{catalogProblem}}</p>
  <template v-if="release"><p v-if="offer?.newer" class="field-help player-release-notes">{{release.changelog}}</p><p v-if="offer?.newer" class="field-help">管理服务会从 GitHub 下载这个 {{bytes(release.archiveSize)}} 的程序。国内服务器没有代理时，速度可能只有每秒几 KB 到几十 KB，37 MB 可能要一两个小时，断线后会自动续传，最长等待 6 小时；也可以在自己电脑上<a :href="release.downloadUrl" target="_blank" rel="noreferrer">下载这个 ZIP</a>，再用下方的“手动上传玩家端发行 ZIP”发布。</p><details class="disclosure-inline"><summary>这个版本需要什么环境？</summary><p class="field-help">管理端至少 {{release.minimumManagementVersion}}，玩家整合包内的启动组件（Agent）至少 {{release.minimumBootstrapVersion}}。启动组件过旧的玩家会继续使用当前程序，需要重新制作并分发部署包。</p></details></template>
  <p v-else-if="catalog" class="field-help">这个平台暂时没有官方版本，也可以在下方手动上传发行 ZIP。</p>
  <p class="field-help">“发布给玩家”会由管理端下载官方程序、核对官方签名，再用本项目的签名发给玩家，和手动上传发行包是同一种发布。每个新版都要发布一次；暂停不会让已经升级的玩家退回旧版。</p>
  <label class="check toggle-line player-auto-publish"><span>官方发布新版后自动发布给玩家<small>管理服务运行时每小时检查一次，最多每 6 小时联网一次。暂停时不会自动发布，也不会自动恢复。国内服务器下载较慢时，会在后台慢慢完成。</small></span><input type="checkbox" class="switch" :checked="Boolean(auto?.enabled)" :disabled="desk.busy||!auto" @change="safely(()=>setAuto(($event.target as HTMLInputElement).checked))"></label>
  <p v-if="autoStatus.title" class="player-auto-status" :class="autoStatus.tone" role="status"><strong>{{autoStatus.title}}</strong><span v-if="autoStatus.detail">{{autoStatus.detail}}</span><small v-if="auto?.checkedAt">上次检查 {{when(auto.checkedAt)}}</small></p>
  <div v-for="program in upgradeChoices" :key="program.manifestSha256" class="player-update-candidate"><div><strong>{{program.version}}</strong><small>已在管理端，尚未发布给玩家</small></div><button class="accent" :disabled="desk.busy||publishing" @click="safely(()=>approve(program))">发布给玩家</button></div>
  <div class="player-delivery-note" :class="uploadStatus.tone" role="status"><strong>{{uploadStatus.title}}</strong><p>{{uploadStatus.detail}}</p><button class="text-action" @click="safely(()=>openView('delivery','hosting'))">去外部托管</button></div>
  <details class="disclosure-inline player-delivery-help"><summary>自己的下载服务和 OSS，操作有什么不同？</summary><p class="field-help">玩家从这台管理端的下载服务更新时，发布或暂停后立即使用新设置。玩家从阿里云 OSS、WebDAV 或其他网站下载时，玩家只能读到那里的文件，请去“外部托管”重新导出并上传；手动上传的服主还需按导出文件中的清理说明移除旧更新记录。在“外部托管”保存密钥并开启自动上传后，发布、暂停和自动发布都会自动上传。</p><p class="field-help">暂停时，管理端需要删除云端指向旧程序的更新记录，所以上传账号也要有删除这些记录的权限。使用 CDN 缓存时，暂停可能稍后才传到玩家；已经开始的升级也可能完成。</p><p v-if="delivery?.warning" class="field-help">记录详情：{{delivery.warning}}</p></details>
 </section>
</template>
