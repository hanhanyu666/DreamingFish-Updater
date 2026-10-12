import {desk} from './store';
export const isDesktop=Boolean(window.__DFS_DESKTOP_META__?.workspace);
const oldViews:Record<string,string>={files:'content',changes:'publish',delivery:'instance',appearance:'personalization',settings:'project'};
export function openLegacy(){
 if(desk.busy||desk.uploading||desk.tasks.some(t=>t.state!=='done')){desk.error='请先完成或移出当前任务，再切换界面';return;}
 if((desk.settingsDirty||desk.releaseNotes.trim())&&!confirm('有尚未保存的内容，切换界面并放弃这些修改？'))return;
 localStorage.setItem('dfs-admin-interface','legacy');
 const params=new URLSearchParams({project:desk.projectId,view:oldViews[desk.view]||'content'});
 if(desk.view==='delivery')params.set('view',desk.section==='programs'?'player':desk.section==='hosting'?'distribution':'instance');
 if(desk.view==='settings')params.set('view',desk.section==='project'?'project':'settings');
 location.assign('/legacy/?'+params);
}
