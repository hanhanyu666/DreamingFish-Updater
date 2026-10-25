<script setup lang="ts">
import { computed, ref } from 'vue';
import { Plus,Trash2,ChevronDown,ArrowUp,ArrowDown,Upload,Image,Copy,FileUp,CheckCircle2,AlertTriangle,Sparkles,CalendarClock,Link2 } from '@lucide/vue';
import { desk,safely,apiPath,binary,request,operation,loadProject,notify } from './store';
import type {Data} from './model';
import ImageField from './ImageField.vue';
import LinkField from './LinkField.vue';
import schemaText from '../../../docs/showcase/showcase.schema.json?raw';
import exampleText from '../../../docs/showcase/example-whalesong.json?raw';
import { SHOWCASE_AI_PROMPT,SHOWCASE_LIMITS,COMMUNITY_KINDS,applyShowcase,parseShowcase,showcaseFromBranding,validateShowcase,communityLabels,newId,ids,move,toLocalInput,fromLocalInput,aiBrief,formatBytes,type ShowcaseIssue } from './showcase';

// Home carousel, events, server card, showcase images, and JSON import/export for the appearance view.
const props=defineProps<{form:Data;section:string;images:Data[];imageUrls:Record<string,string>}>();
const slideIndex=ref(0),eventIndex=ref(0),imageInput=ref<HTMLInputElement|null>(null),fileInput=ref<HTMLInputElement|null>(null),importText=ref(''),importIssues=ref<ShowcaseIssue[]>([]);
const pages=computed<Data[]>(()=>props.form.contentPages||[]);
const slide=computed(()=>props.form.spotlights?.[slideIndex.value]);
const event=computed(()=>props.form.events?.[eventIndex.value]);
const currentIssues=computed(()=>validateShowcase(showcaseFromBranding(props.form as never)));

function addSlide(){props.form.spotlights??=[];props.form.spotlights.push({id:newId('slide',ids(props.form.spotlights)),tag:'',eyebrow:'',title:'新的焦点图',lead:'',imageFile:null,imageUrl:null,action:null});slideIndex.value=props.form.spotlights.length-1;}
function removeSlide(){props.form.spotlights.splice(slideIndex.value,1);slideIndex.value=Math.max(0,slideIndex.value-1);}
function addEvent(){props.form.events??=[];const start=new Date(Date.now()+86400000);start.setMinutes(0,0,0);props.form.events.push({id:newId('event',ids(props.form.events)),title:'新的活动',startsAt:fromLocalInput(toLocalInput(start.toISOString())),endsAt:null,action:null});eventIndex.value=props.form.events.length-1;}
function removeEvent(){props.form.events.splice(eventIndex.value,1);eventIndex.value=Math.max(0,eventIndex.value-1);}
function setCard(){props.form.serverCard={enabled:true,showStatus:true,versionLabel:null,motd:null,logoFile:null,links:[]};}
function addLink(){const card=props.form.serverCard;card.links??=[];card.links.push({id:newId('link',ids(card.links)),kind:'QQ',label:'QQ 群',url:null,copyText:''});}

/** Upload names must match the release rules, so other characters become dashes. */
function uploadName(file:File){const match=/\.(png|jpe?g|webp|gif)$/i.exec(file.name);const ext=(match?.[1]||'png').toLowerCase();const base=file.name.slice(0,match?.index??file.name.length).replace(/[^A-Za-z0-9._-]+/g,'-').replace(/^[^A-Za-z0-9]+|-+$/g,'').slice(0,100);return `${base||newId('image')}.${ext}`;}
async function uploadImages(event:Event){const files=[...((event.target as HTMLInputElement).files||[])];(event.target as HTMLInputElement).value='';if(!files.length)return;await operation(async()=>{for(const file of files)await binary(apiPath(`/presentation-images/upload?fileName=${encodeURIComponent(uploadName(file))}`),file,'POST');await loadProject();notify(`已上传 ${files.length} 张展示图片，检查并发布后生效`);},'正在上传展示图片…');}
async function removeImage(name:string){await operation(async()=>{await request(apiPath('/presentation-images/'+encodeURIComponent(name)),'DELETE');await loadProject();notify('展示图片已移除');});}

async function copy(text:string,done:string){await navigator.clipboard.writeText(text);notify(done);}
const exportJson=()=>JSON.stringify(showcaseFromBranding(props.form as never),null,2);
function checkImport(){const result=parseShowcase(importText.value);importIssues.value=result.issues;if(result.document){const merged=applyShowcase(props.form as never,result.document) as Data;for(const key of Object.keys(result.document))if(key in merged)props.form[key]=merged[key];slideIndex.value=0;eventIndex.value=0;notify('已导入到编辑区，确认预览没问题后点保存');}}
async function readImport(event:Event){const file=(event.target as HTMLInputElement).files?.[0];(event.target as HTMLInputElement).value='';if(file){importText.value=await file.text();checkImport();}}
</script>
<template>
 <template v-if="section==='home'">
  <h3>首页焦点图</h3><p class="field-help">数量不限，每 7 秒轮换。没有焦点图时，首页显示欢迎标题和最新 3 篇公告。</p>
  <div class="page-controls"><div class="select-capsule wide"><select v-model.number="slideIndex" aria-label="编辑焦点图" :disabled="!form.spotlights?.length"><option v-if="!form.spotlights?.length" :value="0">还没有焦点图</option><option v-for="(item,i) in form.spotlights" :key="item.id" :value="i">{{Number(i)+1}}. {{item.title||'未命名'}}</option></select><ChevronDown :size="13"/></div><button type="button" class="icon-button filled" aria-label="新建焦点图" title="新建焦点图" @click="addSlide"><Plus :size="16"/></button></div>
  <template v-if="slide"><div class="form-grid"><label>小标签<input v-model="slide.tag" maxlength="24" placeholder="例如 新赛季"></label><label>切换器名字<input v-model="slide.label" maxlength="24" placeholder="留空用标题"></label></div><label>眉题<input v-model="slide.eyebrow" maxlength="40" placeholder="例如 SEASON 03 · NOW OPEN"></label><label>标题<input v-model="slide.title" maxlength="60" required></label><label>简介<textarea v-model="slide.lead" rows="3" maxlength="300"></textarea></label><ImageField :target="slide" :images="images" label="背景图"/><LinkField v-model="slide.action" :pages="pages" label="按钮"/>
   <div class="list-actions"><button type="button" class="small" :disabled="slideIndex===0" @click="slideIndex=move(form.spotlights,slideIndex,-1)"><ArrowUp :size="14"/>前移</button><button type="button" class="small" :disabled="slideIndex>=form.spotlights.length-1" @click="slideIndex=move(form.spotlights,slideIndex,1)"><ArrowDown :size="14"/>后移</button><span class="toolbar-spacer"/><button type="button" class="text-action danger-text" @click="removeSlide"><Trash2 :size="14"/>移除这张</button></div></template>
  <div v-else class="empty-list compact"><span class="empty-art"><Image :size="24"/></span><strong>添加首页焦点图</strong><span>赛季开放、活动、新手指南都可以放在首页轮播。</span></div>

  <h3>活动倒计时</h3><p class="field-help">数量不限，首页只显示下一场还没结束的活动。时间按这台电脑的时区保存。</p>
  <div class="page-controls"><div class="select-capsule wide"><select v-model.number="eventIndex" aria-label="编辑活动" :disabled="!form.events?.length"><option v-if="!form.events?.length" :value="0">还没有活动</option><option v-for="(item,i) in form.events" :key="item.id" :value="i">{{item.title||'未命名'}}</option></select><ChevronDown :size="13"/></div><button type="button" class="icon-button filled" aria-label="新建活动" title="新建活动" @click="addEvent"><Plus :size="16"/></button></div>
  <template v-if="event"><label>活动名称<input v-model="event.title" maxlength="60" required></label><div class="form-grid"><label>开始时间<input :value="toLocalInput(event.startsAt)" type="datetime-local" required @change="event.startsAt=fromLocalInput(($event.target as HTMLInputElement).value)"></label><label>结束时间<input :value="toLocalInput(event.endsAt)" type="datetime-local" @change="event.endsAt=fromLocalInput(($event.target as HTMLInputElement).value)||null"><small>留空按开始后 2 小时</small></label></div><LinkField v-model="event.action" :pages="pages" label="点击活动卡片"/><button type="button" class="text-action danger-text" @click="removeEvent"><Trash2 :size="14"/>移除这个活动</button></template>
  <div v-else class="empty-list compact"><span class="empty-art"><CalendarClock :size="24"/></span><strong>添加一场活动</strong><span>首页右侧会显示倒计时，开始后显示“正在进行”。</span></div>
 </template>

 <template v-else-if="section==='server'">
  <template v-if="form.serverCard"><h3>服务器卡片</h3>
   <label class="check toggle-line"><span>在首页显示服务器卡片<small>关闭后首页只保留活动和更新区</small></span><input v-model="form.serverCard.enabled" type="checkbox" class="switch"></label>
   <label class="check toggle-line"><span>显示在线人数和延迟<small>玩家端会查询服务器地址，查询失败不影响更新和启动</small></span><input v-model="form.serverCard.showStatus" type="checkbox" class="switch"></label>
   <p class="field-help">服务器地址和它在首页的位置在“标题与品牌”里设置。</p>
   <label class="spaced">版本文字<input v-model="form.serverCard.versionLabel" maxlength="24" placeholder="留空时使用服务器返回的版本"></label>
   <label>MOTD<textarea v-model="form.serverCard.motd" rows="2" maxlength="200" placeholder="留空时使用服务器返回的 MOTD"></textarea><small>支持 § 颜色码，例如 §b鲸歌 §7· §6第三赛季；首页显示第一行。</small></label>
   <ImageField :target="form.serverCard" :images="images" label="Logo" file-key="logoFile" no-url/>
   <div class="music-heading"><h3>社区按钮</h3><button type="button" class="small" :disabled="(form.serverCard.links?.length||0)>=SHOWCASE_LIMITS.links" @click="addLink"><Plus :size="14"/>添加</button></div>
   <article v-for="(link,i) in form.serverCard.links||[]" :key="link.id" class="item-card"><div class="form-grid"><label>类型<div class="select-capsule wide"><select v-model="link.kind"><option v-for="kind in COMMUNITY_KINDS" :key="kind" :value="kind">{{communityLabels[kind]}}</option></select><ChevronDown :size="13"/></div></label><label>按钮文字<input v-model="link.label" maxlength="24"></label></div><label>链接地址<input v-model="link.url" type="url" placeholder="有地址时点击打开"></label><label>复制内容<input v-model="link.copyText" maxlength="60" placeholder="没有地址时点击复制，例如 QQ 群号"></label><button type="button" class="text-action danger-text" @click="form.serverCard.links.splice(i,1)"><Trash2 :size="14"/>移除</button></article>
   <p v-if="!form.serverCard.links?.length" class="field-help">QQ 群、Discord、官网等，最多 {{SHOWCASE_LIMITS.links}} 个。</p></template>
  <div v-else class="empty-list compact"><span class="empty-art"><Link2 :size="24"/></span><strong>首页服务器卡片</strong><span>显示服务器地址、在线人数、MOTD 和社区按钮。没有设置时，玩家端只显示地址。</span><button type="button" class="accent" @click="setCard">设置服务器卡片</button></div>
 </template>

 <template v-else-if="section==='images'">
  <div class="music-heading"><h3>展示图片</h3><button type="button" @click="imageInput?.click()"><Upload :size="15"/>上传图片</button></div>
  <p class="field-help">焦点图、画廊、内容块和服务器 Logo 用的图片。PNG、JPEG、WebP 或 GIF，单张不超过 8 MiB，最多 100 张。检查并发布后生效；仍在使用的图片不能移除。</p>
  <div v-if="images.length" class="image-grid"><figure v-for="image in images" :key="image.fileName"><img v-if="imageUrls[image.fileName]" :src="imageUrls[image.fileName]" :alt="image.fileName"><span v-else class="image-placeholder"><Image :size="20"/></span><figcaption><strong :title="image.fileName">{{image.fileName}}</strong><small>{{formatBytes(image.size)}}</small></figcaption><button type="button" class="icon-button" :aria-label="`移除图片 ${image.fileName}`" title="移除" @click="safely(()=>removeImage(image.fileName))"><Trash2 :size="15"/></button></figure></div>
  <div v-else class="empty-list compact"><span class="empty-art"><Image :size="24"/></span><strong>还没有展示图片</strong><span>上传后在焦点图、画廊和内容块里选择。</span></div>
  <input ref="imageInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif" multiple hidden @change="safely(()=>uploadImages($event))">
 </template>

 <template v-else-if="section==='showcase'">
  <h3>检查当前内容</h3>
  <p v-if="!currentIssues.length" class="group-note ok"><CheckCircle2 :size="14"/> 符合展示内容规范</p>
  <ul v-else class="issue-list"><li v-for="issue in currentIssues" :key="issue.path+issue.message"><code>{{issue.path}}</code>{{issue.message}}</li></ul>
  <h3>导出</h3><p class="field-help">导出首页、服务器卡片、活动、主题和全部页面。背景图片、音乐和上传的图片文件不在里面。</p>
  <div class="list-actions"><button type="button" @click="safely(()=>copy(exportJson(),'展示内容 JSON 已复制'))"><Copy :size="15"/>复制 JSON</button></div>
  <h3>导入</h3><p class="field-help">粘贴 JSON 或选择文件。通过检查后会替换编辑区里的对应内容，点保存才会生效。</p>
  <label>展示内容 JSON<textarea v-model="importText" class="markdown-editor" rows="8" placeholder='{ "format": "dreamingfish-showcase", "version": 1, ... }'></textarea></label>
  <div class="list-actions"><button type="button" class="accent" :disabled="!importText.trim()" @click="checkImport">检查并导入</button><button type="button" @click="fileInput?.click()"><FileUp :size="15"/>选择文件</button></div>
  <ul v-if="importIssues.length" class="issue-list"><li class="issue-head"><AlertTriangle :size="14"/>有 {{importIssues.length}} 处需要修改，可以把下面的原文发给 AI 让它改</li><li v-for="issue in importIssues" :key="issue.path+issue.message"><code>{{issue.path}}</code>{{issue.message}}</li></ul>
  <input ref="fileInput" type="file" accept=".json,application/json" hidden @change="safely(()=>readImport($event))">
  <h3>让 AI 帮你写</h3><p class="field-help">复制说明、格式规范和完整示例，粘贴给 ChatGPT、Claude、Kimi 等，再补上你的服务器介绍。拿到结果后在上面导入。</p>
  <div class="list-actions"><button type="button" @click="safely(()=>copy(aiBrief(SHOWCASE_AI_PROMPT,schemaText,exampleText),'给 AI 的说明已复制'))"><Sparkles :size="15"/>复制给 AI 的说明</button></div>
 </template>
</template>
