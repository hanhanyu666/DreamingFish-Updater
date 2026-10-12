export type Data = Record<string, any>;
export interface SourceFile {path:string;size:number;published:boolean;preset:string;presetSource:string|null;cleanup:boolean;optionalGroup:string|null;componentId:string|null;displayName:string|null;version:string|null;lastModifiedMillis:number}
export interface Entry extends Partial<SourceFile> {path:string;name:string;directory:boolean;size:number;count?:number}
export interface Task {id:string;name:string;path:string;size:number;progress:number;state:'queued'|'uploading'|'checking'|'review'|'importing'|'done'|'error';file?:File;plan?:Data;action?:string;error?:string}
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
export function entries(files:SourceFile[],directories:string[],directory:string):Entry[]{
 const prefix=directory?directory+'/':'';const folders=new Set<string>();
 for(const p of directories){if(p.startsWith(prefix)&&p!==directory){const n=p.slice(prefix.length).split('/')[0];if(n)folders.add(prefix+n);}}
 for(const f of files){if(f.path.startsWith(prefix)&&f.path.slice(prefix.length).includes('/'))folders.add(prefix+f.path.slice(prefix.length).split('/')[0]);}
 const rows:Entry[]=[...folders].map(path=>{const children=files.filter(f=>f.path.startsWith(path+'/'));return {path,name:nameOf(path),directory:true,size:children.reduce((n,f)=>n+f.size,0),count:children.length};});
 rows.push(...files.filter(f=>parentOf(f.path)===directory).map(f=>({...f,name:nameOf(f.path),directory:false})));
 return rows.sort((a,b)=>Number(b.directory)-Number(a.directory)||a.name.localeCompare(b.name,'zh-CN',{numeric:true}));
}
