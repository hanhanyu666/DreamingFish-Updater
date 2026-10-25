import type {Data} from './model';
export {
 SHOWCASE_AI_PROMPT,SHOWCASE_LIMITS,BLOCK_KINDS,BLOCK_TONES,BLOCK_ICONS,COMMUNITY_KINDS,
 applyShowcase,parseShowcase,showcaseFromBranding,validateShowcase,
} from '../../../player-ui/src/lib/showcaseRules.ts';
export type {ShowcaseIssue,ShowcaseDocument} from '../../../player-ui/src/lib/showcaseRules.ts';

// Labels the owner sees for the fixed showcase options; the values stay the format's own.
export const layoutLabels:Record<string,string>={MARKDOWN:'图文页',ANNOUNCEMENTS:'公告页',GALLERY:'画廊页',BLOCKS:'内容块页'};
export const blockKindLabels:Record<string,string>={TEXT:'一段文字',CARDS:'卡片网格',STEPS:'编号步骤',LIST:'编号条目',TEAM:'团队成员',FAQ:'常见问答',STATS:'大数字',CALLOUT:'醒目横幅',IMAGE:'一张宽图',GALLERY:'小画廊'};
export const toneLabels:Record<string,string>={DEFAULT:'默认',ACCENT:'强调色',SECONDARY:'辅助色',WARM:'暖色',MUTED:'低调'};
export const communityLabels:Record<string,string>={QQ:'QQ 群',DISCORD:'Discord',KOOK:'KOOK',BILIBILI:'哔哩哔哩',WEBSITE:'官网',OTHER:'其他'};
export const iconLabels:Record<string,string>={star:'星星',shield:'盾牌',users:'成员',chat:'聊天',globe:'地球',calendar:'日历',pin:'定位',image:'图片',sparkle:'闪光',sword:'剑',pickaxe:'镐',heart:'爱心',home:'房子',gift:'礼物',trophy:'奖杯',book:'书',clock:'时钟',check:'对勾',info:'提示',signal:'信号'};
export const cornerItems=[{id:'ROUND',label:'圆润'},{id:'SOFT',label:'柔和'},{id:'SQUARE',label:'方正'}];
export const surfaceItems=[{id:'GLASS',label:'毛玻璃'},{id:'SOLID',label:'实色'},{id:'CLEAR',label:'通透'}];
/** Where the player home page shows the server address; unset means the server card. */
export const addressItems=[{id:'CARD',label:'服务器卡片'},{id:'HERO',label:'焦点图旁'},{id:'HIDDEN',label:'不显示'}];
export function addressHint(placement:string|null|undefined,cardEnabled:boolean){
 if(placement==='HIDDEN')return '首页不显示地址，玩家端仍会用它检测在线人数和延迟。';
 if(placement==='HERO')return '显示在每张焦点图的按钮旁，玩家点一下就能复制。';
 return cardEnabled?'显示在首页右侧的服务器卡片里，玩家点一下就能复制。':'服务器卡片已关闭，首页不会显示地址。可以在“服务器卡片”里打开，或改放在焦点图旁。';
}
// Which block fields each kind shows, so the editor only offers what the player renders.
export const blockFields:Record<string,{items?:boolean;markdown?:boolean;image?:boolean;columns?:boolean;actions?:boolean;item?:string[]}>={
 TEXT:{markdown:true,actions:true},
 CARDS:{items:true,columns:true,actions:true,item:['text','icon','image','action']},
 STEPS:{items:true,actions:true,item:['text']},
 LIST:{items:true,actions:true,item:['text']},
 TEAM:{items:true,columns:true,item:['meta','text','image']},
 FAQ:{items:true,item:['text']},
 STATS:{items:true,columns:true,item:['text','meta']},
 CALLOUT:{markdown:true,image:true,actions:true},
 IMAGE:{image:true},
 GALLERY:{items:true,columns:true,item:['meta','image']},
};
export const itemTitleLabels:Record<string,string>={TEAM:'名字',FAQ:'问题',STATS:'数字',GALLERY:'图片标题'};
export const itemTextLabels:Record<string,string>={FAQ:'回答',STATS:'说明'};
export const itemMetaLabels:Record<string,string>={TEAM:'职务',STATS:'补充说明',GALLERY:'作者'};

/** A short id in the format's pattern; the prefix keeps hand-edited files readable. */
export function newId(prefix:string,taken:Iterable<string>=[]){const used=new Set(taken);for(;;){const id=`${prefix}-${Math.random().toString(36).slice(2,8)}`;if(!used.has(id))return id;}}
export function ids(list:Data[]|null|undefined){return (list||[]).map(item=>String(item.id));}
export function move(list:Data[],index:number,delta:number){const target=index+delta;if(target<0||target>=list.length)return index;const [item]=list.splice(index,1);list.splice(target,0,item);return target;}

/** datetime-local value for an ISO time with offset, shown in this computer's time zone. */
export function toLocalInput(iso:string|null|undefined){if(!iso)return '';const time=new Date(iso);if(Number.isNaN(time.getTime()))return '';const local=new Date(time.getTime()-time.getTimezoneOffset()*60000);return local.toISOString().slice(0,16);}
/** ISO 8601 with this computer's offset, which is what events store. */
export function fromLocalInput(value:string){if(!value)return '';const time=new Date(value);if(Number.isNaN(time.getTime()))return '';const offset=-time.getTimezoneOffset(),sign=offset>=0?'+':'-',abs=Math.abs(offset);const pad=(n:number)=>String(n).padStart(2,'0');return `${value.length===16?value+':00':value}${sign}${pad(Math.floor(abs/60))}:${pad(abs%60)}`;}

/** The message an owner pastes back to an AI tool: rules, schema, and a full example. */
export function aiBrief(prompt:string,schema:string,example:string){return `${prompt}\n\n===== showcase.schema.json =====\n${schema.trim()}\n\n===== example-whalesong.json =====\n${example.trim()}\n`;}
export function formatBytes(size:number){return size>=1048576?`${(size/1048576).toFixed(1)} MB`:`${Math.max(1,Math.round(size/1024))} KB`;}
