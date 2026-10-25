<script setup lang="ts">
import { computed } from 'vue';
import { ChevronDown } from '@lucide/vue';
import type {Data} from './model';
// Where a showcase button goes: one of the owner's pages, one announcement, or a public link.
const props=defineProps<{modelValue:Data|null|undefined;pages:Data[];label?:string;removable?:boolean}>();
const emit=defineEmits<{'update:modelValue':[value:Data|null];remove:[]}>();
const kinds=[{id:'',label:'不显示按钮'},{id:'PAGE',label:'打开页面'},{id:'ARTICLE',label:'打开一篇公告'},{id:'URL',label:'打开链接'}];
const layoutOf=(page:Data)=>page.layout||(page.announcementPage?'ANNOUNCEMENTS':'MARKDOWN');
const choices=computed(()=>props.modelValue?.kind==='ARTICLE'?props.pages.filter(p=>layoutOf(p)==='ANNOUNCEMENTS'):props.pages);
const articles=computed(()=>props.pages.find(p=>p.id===props.modelValue?.pageId)?.articles||[]);
function setKind(event:Event){
 const kind=(event.target as HTMLSelectElement).value,old=props.modelValue||{};
 if(!kind){if(props.removable)emit('remove');else emit('update:modelValue',null);return;}
 const pages=kind==='ARTICLE'?props.pages.filter(p=>layoutOf(p)==='ANNOUNCEMENTS'):props.pages,page=pages.find(p=>p.id===old.pageId)||pages[0];
 emit('update:modelValue',{label:old.label||'',kind,pageId:kind==='URL'?null:page?.id||null,articleId:kind==='ARTICLE'?page?.articles?.[0]?.id||null:null,url:kind==='URL'?old.url||'':null});
}
function setPage(event:Event){const value=props.modelValue!;value.pageId=(event.target as HTMLSelectElement).value;if(value.kind==='ARTICLE')value.articleId=articles.value[0]?.id||null;}
</script>
<template><div class="link-field"><label>{{label||'按钮'}}<div class="select-capsule wide"><select :value="modelValue?.kind||''" :aria-label="(label||'按钮')+'去向'" @change="setKind"><option v-for="kind in kinds" :key="kind.id" :value="kind.id">{{kind.id===''&&removable?'移除这个按钮':kind.label}}</option></select><ChevronDown :size="13"/></div></label>
 <template v-if="modelValue?.kind"><div class="form-grid"><label>按钮文字<input v-model="modelValue.label" maxlength="24" placeholder="例如 查看详情"></label>
  <label v-if="modelValue.kind!=='URL'">{{modelValue.kind==='ARTICLE'?'公告页':'页面'}}<div class="select-capsule wide"><select :value="modelValue.pageId||''" @change="setPage"><option v-if="!choices.length" value="">还没有可选的页面</option><option v-for="page in choices" :key="page.id" :value="page.id">{{page.navigationLabel}}</option></select><ChevronDown :size="13"/></div></label>
  <label v-else>链接地址<input v-model="modelValue.url" type="url" placeholder="https://"></label></div>
  <label v-if="modelValue.kind==='ARTICLE'">公告<div class="select-capsule wide"><select v-model="modelValue.articleId"><option v-if="!articles.length" :value="null">这一页还没有公告</option><option v-for="article in articles" :key="article.id" :value="article.id">{{article.title}}</option></select><ChevronDown :size="13"/></div></label></template>
</div></template>
