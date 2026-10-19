export type Data = Record<string, any>;
export interface SourceFile {path:string;size:number;published:boolean;preset:string;presetSource:string|null;cleanup:boolean;optionalGroup:string|null;componentId:string|null;displayName:string|null;version:string|null;lastModifiedMillis:number}
export interface Entry extends Partial<SourceFile> {path:string;name:string;directory:boolean;size:number;count?:number}
export type ImportAction = 'ADD'|'OVERWRITE'|'REPLACE_MOD';
export type ImportDecision = ImportAction|'SKIP';
export interface ImportPlan {
 id:string;path:string;size:number;sha256:string;stamp:string;committed:boolean;undone:boolean;
 existing:{sha256:string}|null;metadata:{displayName?:string;componentId:string;version?:string}|null;
 replacements:{path:string}[];
}
export type TaskState = 'queued'|'uploading'|'checking'|'review'|'importing'|'done'|'skipped'|'unknown'|'check-error'|'expired'|'needs-file'|'undone'|'error';
export interface Task {
 id:string;projectId:string;requestId:string;name:string;path:string;size:number;progress:number;state:TaskState;
 file?:File;serverPath?:string;plan?:ImportPlan;action?:ImportDecision;error?:string;
}
export function taskFinished(task:Task){return ['done','skipped','check-error','undone'].includes(task.state);}
export function imported(task:Task){return task.state==='done'||task.state==='check-error';}
export function parseImportPlan(value:unknown):ImportPlan {
 const plan=value as ImportPlan;
 if(!plan||typeof plan.id!=='string'||typeof plan.path!=='string'||typeof plan.stamp!=='string'||
    typeof plan.sha256!=='string'||typeof plan.size!=='number'||!Array.isArray(plan.replacements)||
    typeof plan.committed!=='boolean'||typeof plan.undone!=='boolean')
  throw new Error('管理服务返回了不兼容的导入状态，请升级管理端后复查结果');
 return plan;
}

export const presets:Record<string,{label:string;description:string}>={SYNC:{label:'普通同步',description:'跟随服主更新，玩家可以停用或自行管理。'},REQUIRED:{label:'强制同步',description:'必须与发布内容一致；强制目录会处理额外文件。'},INITIAL:{label:'首次提供',description:'缺少时提供一次，之后归玩家自己维护。'},DEFAULT_CONFIG:{label:'旧版配置',description:'尚未修改的配置跟随更新。'}};
export function bytes(value=0){if(value<1024)return `${value} B`;const units=['KiB','MiB','GiB','TiB'];let n=value/1024,i=0;while(n>=1024&&i<units.length-1){n/=1024;i++;}return `${n>=10?n.toFixed(1):n.toFixed(2)} ${units[i]}`;}
export function when(value:any){if(!value)return '—';return new Date(typeof value==='number'?value:value).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});}
export function nameOf(path:string){return path.split('/').filter(Boolean).at(-1)||'整合包';}
export function parentOf(path:string){return path.split('/').slice(0,-1).join('/');}
export function newestReleases(releases:Data[]=[]):Data[]{return [...releases].sort((a,b)=>Number(b.sequence)-Number(a.sequence));}
export function authRequiresForm(status:Data){return status.registered===false||status.authenticated===false;}
export function changedFileSize(change:Data,files:Data[]=[],history:Data[]=[]):number|null {
 if(typeof change.fileSize==='number')return change.fileSize;
 const samePath=(path:string)=>path?.toLowerCase()===String(change.path).toLowerCase();
 if(change.kind==='REMOVED')return history.find(f=>samePath(f.path))?.versions?.find((v:Data)=>v.currentlyPublished)?.size??null;
 return files.find(f=>samePath(f.path))?.size??null;
}
export interface FileIndex {rows:Map<string,Entry[]>;folders:Map<string,Entry>}
export function buildFileIndex(files:SourceFile[],directories:string[]):FileIndex {
 const rows=new Map<string,Entry[]>(),folders=new Map<string,Entry>();
 const children=(parent:string)=>{let found=rows.get(parent);if(!found){found=[];rows.set(parent,found);}return found;};
 function folder(path:string){if(!path||folders.has(path))return;folder(parentOf(path));const entry:Entry={path,name:nameOf(path),directory:true,size:0,count:0};folders.set(path,entry);children(parentOf(path)).push(entry);}
 for(const path of directories)folder(path);
 for(const file of files){let parent=parentOf(file.path);folder(parent);children(parent).push({...file,name:nameOf(file.path),directory:false});
  while(parent){const entry=folders.get(parent)!;entry.size+=file.size;entry.count=(entry.count||0)+1;parent=parentOf(parent);}
 }
 for(const list of rows.values())list.sort((a,b)=>Number(b.directory)-Number(a.directory)||a.name.localeCompare(b.name,'zh-CN',{numeric:true}));
 return {rows,folders};
}
export function indexEntries(index:FileIndex,directory:string):Entry[]{return [...(index.rows.get(directory)||[])];}
export function entries(files:SourceFile[],directories:string[],directory:string):Entry[]{return indexEntries(buildFileIndex(files,directories),directory);}
export function directoryCounts(files:SourceFile[],include:(file:SourceFile)=>boolean){const counts=new Map<string,number>();for(const file of files){if(!include(file))continue;let parent=parentOf(file.path);while(parent){counts.set(parent,(counts.get(parent)||0)+1);parent=parentOf(parent);}}return counts;}
export function pageEntries<T>(rows:T[],page:number,size=200):T[]{return rows.slice(Math.max(0,page)*size,(Math.max(0,page)+1)*size);}
export function selectionVisibility(selection:Entry[],visible:Entry[],page:Entry[]){const shown=new Set(page.map(e=>e.path)),scope=new Set(visible.map(e=>e.path));return {outsideFilter:selection.filter(e=>!scope.has(e.path)).length,otherPages:selection.filter(e=>scope.has(e.path)&&!shown.has(e.path)).length};}
