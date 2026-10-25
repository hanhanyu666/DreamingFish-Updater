<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Plus,Trash2,ChevronDown,ArrowUp,ArrowDown,Images,Blocks } from '@lucide/vue';
import type {Data} from './model';
import ImageField from './ImageField.vue';
import LinkField from './LinkField.vue';
import { SHOWCASE_LIMITS,BLOCK_KINDS,BLOCK_TONES,BLOCK_ICONS,blockKindLabels,toneLabels,iconLabels,blockFields,itemTitleLabels,itemTextLabels,itemMetaLabels,newId,ids,move } from './showcase';

// Gallery images and content blocks of one GALLERY or BLOCKS page.
const props=defineProps<{page:Data;pages:Data[];images:Data[]}>();
const imageIndex=ref(0),blockIndex=ref(0),itemIndex=ref(0),newKind=ref('CARDS');
const layout=computed(()=>props.page.layout);
const picture=computed(()=>props.page.gallery?.[imageIndex.value]);
const block=computed(()=>props.page.blocks?.[blockIndex.value]);
const fields=computed(()=>blockFields[block.value?.kind]||{});
const item=computed(()=>block.value?.items?.[itemIndex.value]);
watch(()=>props.page.id,()=>{imageIndex.value=0;blockIndex.value=0;itemIndex.value=0;});
watch(blockIndex,()=>{itemIndex.value=0;});

function addPicture(){props.page.gallery??=[];props.page.gallery.push({id:newId('shot',ids(props.page.gallery)),title:'新的作品',author:'',category:'',caption:null,location:null,imageFile:null,imageUrl:null});imageIndex.value=props.page.gallery.length-1;}
function removePicture(){props.page.gallery.splice(imageIndex.value,1);imageIndex.value=Math.max(0,imageIndex.value-1);}
function addBlock(){props.page.blocks??=[];const kind=newKind.value;props.page.blocks.push({id:newId(kind.toLowerCase(),ids(props.page.blocks)),kind,title:blockKindLabels[kind],lead:null,navLabel:null,tone:null,columns:null,markdown:blockFields[kind]?.markdown?'':null,imageFile:null,imageUrl:null,items:blockFields[kind]?.items?[]:null,actions:[]});blockIndex.value=props.page.blocks.length-1;}
function removeBlock(){props.page.blocks.splice(blockIndex.value,1);blockIndex.value=Math.max(0,blockIndex.value-1);}
function addItem(){const target=block.value!;target.items??=[];target.items.push({id:newId('item',ids(target.items)),title:itemTitleLabels[target.kind]?'':'新的条目',text:null,meta:null,icon:null,imageFile:null,imageUrl:null,action:null});itemIndex.value=target.items.length-1;}
function removeItem(){block.value!.items.splice(itemIndex.value,1);itemIndex.value=Math.max(0,itemIndex.value-1);}
function addAction(){const target=block.value!;target.actions??=[];target.actions.push({label:'',kind:'PAGE',pageId:props.pages[0]?.id||null,articleId:null,url:null});}
const shows=(field:string)=>fields.value.item?.includes(field);
const defaultEyebrows:Record<string,string>={TEXT:'ABOUT',CARDS:'FEATURES',STEPS:'HOW TO JOIN',LIST:'RULES',TEAM:'TEAM',FAQ:'FAQ',STATS:'NUMBERS',CALLOUT:'NOTE',IMAGE:'PHOTO',GALLERY:'GALLERY'};
function setTags(value:string){const tags=value.split(/[,，、]/).map(tag=>tag.trim()).filter(Boolean).slice(0,SHOWCASE_LIMITS.tags);props.page.tags=tags.length?tags:null;}
</script>
<template>
 <template v-if="layout==='GALLERY'">
  <h3>画廊图片</h3><p class="field-help">最多 {{SHOWCASE_LIMITS.gallery}} 张，玩家可以按分类筛选并点开看大图。</p>
  <div class="page-controls"><div class="select-capsule wide"><select v-model.number="imageIndex" aria-label="编辑画廊图片" :disabled="!page.gallery?.length"><option v-if="!page.gallery?.length" :value="0">还没有图片</option><option v-for="(shot,i) in page.gallery" :key="shot.id" :value="i">{{shot.title||'未命名'}}</option></select><ChevronDown :size="13"/></div><button type="button" class="icon-button filled" aria-label="添加画廊图片" title="添加画廊图片" :disabled="(page.gallery?.length||0)>=SHOWCASE_LIMITS.gallery" @click="addPicture"><Plus :size="16"/></button></div>
  <template v-if="picture"><ImageField :target="picture" :images="images"/><label>作品标题<input v-model="picture.title" maxlength="60" required></label><label class="check toggle-line"><span>本周精选<small>在图片上标“本周精选”</small></span><input v-model="picture.featured" type="checkbox" class="switch"></label><div class="form-grid"><label>作者<input v-model="picture.author" maxlength="40"></label><label>分类<input v-model="picture.category" maxlength="24" placeholder="例如 建筑"></label></div><label>坐标或地点<input v-model="picture.location" maxlength="40" placeholder="例如 主城 X 120 Z -340"></label><label>说明<textarea v-model="picture.caption" rows="2" maxlength="300"></textarea></label>
   <div class="list-actions"><button type="button" class="small" :disabled="imageIndex===0" @click="imageIndex=move(page.gallery,imageIndex,-1)"><ArrowUp :size="14"/>前移</button><button type="button" class="small" :disabled="imageIndex>=page.gallery.length-1" @click="imageIndex=move(page.gallery,imageIndex,1)"><ArrowDown :size="14"/>后移</button><span class="toolbar-spacer"/><button type="button" class="text-action danger-text" @click="removePicture"><Trash2 :size="14"/>移除这张</button></div></template>
  <div v-else class="empty-list compact"><span class="empty-art"><Images :size="24"/></span><strong>添加玩家作品</strong><span>建筑、风景和活动合影都可以放进画廊。</span></div>
 </template>

 <template v-else-if="layout==='BLOCKS'">
  <h3>页面顶部</h3><ImageField :target="page" :images="images" label="顶部大图（留空用项目背景）"/><label>标签<input :value="(page.tags||[]).join('，')" placeholder="用逗号分开，最多 6 个，例如 Java 1.20.1 · Fabric，正版验证" @change="setTags(($event.target as HTMLInputElement).value)"></label>
  <h3>内容块</h3><p class="field-help">按顺序排在页面上，最多 {{SHOWCASE_LIMITS.blocks}} 块。有标题的块会出现在页面左侧的目录里。</p>
  <div class="page-controls"><div class="select-capsule wide"><select v-model.number="blockIndex" aria-label="编辑内容块" :disabled="!page.blocks?.length"><option v-if="!page.blocks?.length" :value="0">还没有内容块</option><option v-for="(entry,i) in page.blocks" :key="entry.id" :value="i">{{Number(i)+1}}. {{entry.title||blockKindLabels[entry.kind]}}</option></select><ChevronDown :size="13"/></div></div>
  <div class="page-controls"><div class="select-capsule wide"><select v-model="newKind" aria-label="新内容块的类型"><option v-for="kind in BLOCK_KINDS" :key="kind" :value="kind">{{blockKindLabels[kind]}}</option></select><ChevronDown :size="13"/></div><button type="button" :disabled="(page.blocks?.length||0)>=SHOWCASE_LIMITS.blocks" @click="addBlock"><Plus :size="14"/>添加</button></div>
  <template v-if="block"><div class="block-kind">{{blockKindLabels[block.kind]}}</div>
   <label>标题<input v-model="block.title" maxlength="60"></label>
   <label v-if="block.kind!=='FAQ'">{{block.kind==='IMAGE'?'图片说明':'引导文字'}}<textarea v-model="block.lead" rows="2" maxlength="300"></textarea></label>
   <label>英文小字<input v-model="block.eyebrow" maxlength="40" :placeholder="'留空显示 '+defaultEyebrows[block.kind]"></label>
   <div class="form-grid"><label>目录名称<input v-model="block.navLabel" maxlength="24" placeholder="留空用标题"></label><label>色调<div class="select-capsule wide"><select v-model="block.tone"><option :value="null">默认</option><option v-for="tone in BLOCK_TONES.slice(1)" :key="tone" :value="tone">{{toneLabels[tone]}}</option></select><ChevronDown :size="13"/></div></label></div>
   <label v-if="fields.columns">每行几列<div class="select-capsule wide"><select v-model="block.columns"><option :value="null">自动</option><option v-for="n in 4" :key="n" :value="n">{{n}} 列</option></select><ChevronDown :size="13"/></div></label>
   <label v-if="fields.markdown">正文 Markdown<textarea v-model="block.markdown" class="markdown-editor" rows="6"></textarea></label>
   <ImageField v-if="fields.image" :target="block" :images="images" :label="block.kind==='CALLOUT'?'背景图':'图片'"/>
   <template v-if="fields.items"><div class="music-heading"><h3>条目</h3><button type="button" class="small" :disabled="(block.items?.length||0)>=SHOWCASE_LIMITS.items" @click="addItem"><Plus :size="14"/>添加</button></div>
    <div v-if="block.items?.length" class="select-capsule wide"><select v-model.number="itemIndex" aria-label="编辑条目"><option v-for="(entry,i) in block.items" :key="entry.id" :value="i">{{Number(i)+1}}. {{entry.title||'未填写'}}</option></select><ChevronDown :size="13"/></div>
    <article v-if="item" class="item-card"><label>{{itemTitleLabels[block.kind]||'标题'}}<input v-model="item.title" maxlength="60" required></label>
     <label v-if="shows('meta')">{{itemMetaLabels[block.kind]||'补充'}}<input v-model="item.meta" maxlength="40"></label>
     <label v-if="shows('text')">{{itemTextLabels[block.kind]||'说明'}}<textarea v-model="item.text" rows="3" maxlength="600"></textarea></label>
     <label v-if="shows('icon')">图标<div class="select-capsule wide"><select v-model="item.icon"><option :value="null">不显示图标</option><option v-for="icon in BLOCK_ICONS" :key="icon" :value="icon">{{iconLabels[icon]}}</option></select><ChevronDown :size="13"/></div></label>
     <ImageField v-if="shows('image')" :target="item" :images="images" :label="block.kind==='TEAM'?'头像（留空按名字生成像素头像）':'图片'"/>
     <LinkField v-if="shows('action')" v-model="item.action" :pages="pages" label="卡片按钮"/>
     <div class="list-actions"><button type="button" class="small" :disabled="itemIndex===0" @click="itemIndex=move(block.items,itemIndex,-1)"><ArrowUp :size="14"/>前移</button><button type="button" class="small" :disabled="itemIndex>=block.items.length-1" @click="itemIndex=move(block.items,itemIndex,1)"><ArrowDown :size="14"/>后移</button><span class="toolbar-spacer"/><button type="button" class="text-action danger-text" @click="removeItem"><Trash2 :size="14"/>移除条目</button></div></article></template>
   <template v-if="fields.actions"><div class="music-heading"><h3>按钮</h3><button type="button" class="small" :disabled="(block.actions?.length||0)>=SHOWCASE_LIMITS.actions" @click="addAction"><Plus :size="14"/>添加</button></div>
    <article v-for="(action,i) in block.actions||[]" :key="i" class="item-card"><LinkField v-model="block.actions[i]" :pages="pages" :label="i===0?'主按钮':'按钮 '+(Number(i)+1)" removable @remove="block.actions.splice(i,1)"/></article></template>
   <div class="list-actions"><button type="button" class="small" :disabled="blockIndex===0" @click="blockIndex=move(page.blocks,blockIndex,-1)"><ArrowUp :size="14"/>上移</button><button type="button" class="small" :disabled="blockIndex>=page.blocks.length-1" @click="blockIndex=move(page.blocks,blockIndex,1)"><ArrowDown :size="14"/>下移</button><span class="toolbar-spacer"/><button type="button" class="text-action danger-text" @click="removeBlock"><Trash2 :size="14"/>移除这块</button></div></template>
  <div v-else class="empty-list compact"><span class="empty-art"><Blocks :size="24"/></span><strong>搭一个介绍页</strong><span>特色卡片、加入步骤、规则、团队、问答都是现成的内容块。</span></div>
 </template>
</template>
