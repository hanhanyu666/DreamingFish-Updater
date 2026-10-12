import type {Data} from './model';
import {presets} from './model.ts';

export interface PolicyPresentation {label:string;subject:string;detail:string}
const behavior=(value:unknown)=>value==null?'新文件':value==='LEGACY_MISSING_ONLY'?'旧版：只补齐缺失':presets[String(value)]?.label||'继承维护设置';
export function policyPresentation(change:Data,maintenance:Data={}):PolicyPresentation {
 const subject=String(change.subject||change.path||'相关设置');
 const previous=change.previous??change.before,current=change.current??change.after;
 const groupName=(id:unknown)=>id?maintenance.optionalGroups?.find((g:Data)=>g.id===id)?.title||'原可选内容':'不在可选内容中';
 switch(change.kind){
  case 'PRESET':return {label:'维护方式',subject,detail:`${behavior(previous)} → ${behavior(current)}`};
  case 'CLEANUP_DIRECTORY':return {label:'文件夹额外文件',subject:subject.replace(/\/$/,'')+'/',detail:current?'开始清理：玩家额外添加的文件会先备份，再移出':'不再移出玩家额外添加的文件'};
  case 'OPTIONAL_GROUP':return {label:'可选内容',subject:String(current||previous||groupName(subject)),detail:previous==null?'新增可选内容':current==null?'停止提供这组可选内容':`${previous} → ${current}`};
  case 'OPTIONAL_MEMBERSHIP':return {label:'可选内容归属',subject,detail:`${groupName(previous)} → ${groupName(current)}`};
  case 'WITHDRAWAL':{const rule=maintenance.withdrawals?.find((r:Data)=>r.id===subject);return {label:current?(rule?.kind==='REMOVAL'?'移除玩家副本':'处理问题版本'):'结束移除要求',subject:rule?.items?.map((i:Data)=>i.path).join('、')||'历史文件版本',detail:current?rule?.reason||'匹配的玩家副本将先备份，再移出':'后续发布不再要求移出这些副本'};}
  case 'CORRECTION':{const rule=maintenance.corrections?.find((r:Data)=>r.id===subject);return {label:current?'修复玩家文件':'结束修复要求',subject:rule?.path||'玩家文件',detail:current?rule?.reason||'用发布版本修复匹配的玩家副本':'后续发布不再执行这项修复'};}
  case 'CORRECTION_DROPPED':return {label:'文件修复未生效',subject:String(previous||subject),detail:'文件已不在标准目录中，本次发布不会执行这项修复'};
  default:return {label:'其他维护设置',subject,detail:change.description||'维护设置发生变化'};
 }
}
export const operationKinds:Record<string,string>={FILES:'文件操作',SETTINGS:'设置修改',PUBLISH:'版本发布'};
export const viewSections:Record<string,{id:string;label:string}[]>={
 changes:[{id:'changes',label:'待发布改动'},{id:'releases',label:'发布版本'},{id:'operations',label:'操作记录'},{id:'problems',label:'历史问题文件'}],
 delivery:[{id:'deployment',label:'发放整合包'},{id:'programs',label:'玩家端程序'},{id:'hosting',label:'外部托管'}],
 appearance:[{id:'branding',label:'标题与品牌'},{id:'colors',label:'外观与背景'},{id:'pages',label:'公告与页面'},{id:'music',label:'启动音乐'}],
 settings:[{id:'project',label:'项目设置'},{id:'service',label:'服务设置'},{id:'account',label:'账户与安全'}],
};
