import {parseBackendTask,backendTaskFinished,backendTaskNeedsReview,type BackendTask} from './backend-tasks';
import { computed, reactive, watch } from 'vue';
import type { Data, Entry, SourceFile, Task, ImportPlan } from './model';
import { nameOf, newestReleases, authRequiresForm, taskFinished, imported, parseImportPlan,buildFileIndex,directoryCounts } from './model';
import { interfaceLanding } from './interface-state';
import { deploymentFileName, saveDeployment, revealDeployment, type DeploymentDownload } from './deployment-download';
const landing = interfaceLanding(location.search);
declare global {
    interface Window {
        __DFS_DESKTOP_TOKEN__?: string;
        __DFS_DESKTOP_META__?: Data;
    }
}
export const desk = reactive({
    ready: false, auth: {} as Data, state: {} as Data, project: null as Data | null, projectId: landing.projectId, view: landing.view, section: landing.section,
    files: [] as SourceFile[], directories: [] as string[], directory: '', search: '', filter: 'all', group: '', selection: [] as Entry[],
    inspector: localStorage.getItem('dfs-workbench-inspector') === 'on', sidebar: localStorage.getItem('dfs-workbench-sidebar') !== 'off', historyPaths: [''], historyIndex: 0, busy: false, busyText: '', notice: '', error: '',
    jobs:[] as BackendTask[], tasks: [] as Task[], tasksOpen: false, uploading: false, uploadPaused: false, transferTarget: '',
    dialog: '', dialogProjectId: '', dialogData: {} as Data, menu: '', palette: false, theme: (localStorage.getItem('dfs-admin-theme') || localStorage.getItem('dfs-desktop-theme') || 'light'),
    releaseVersion: '', releaseNotes: '', operations: [] as Data[], releaseFiles: [] as Data[], operationPlan: null as Data | null,
    browse: {} as Data, browsePath: '', browseKind: 'directory', browseSelected: '', browseAction: '',
    settingsDirty: false, browseValues: {} as Data, historyLoading: false, sourceError: '',
    deploymentDownload: null as (DeploymentDownload & { projectId: string }) | null,
});
// Shared by the project sidebar, the command bar and the file view.
export const fileIndex=computed(()=>buildFileIndex(desk.files,desk.directories));
export const pendingCounts=computed(()=>{const changes=new Set((desk.project?.preview?.changes||[]).map((change:Data)=>change.path));return directoryCounts(desk.files,file=>!file.published||changes.has(file.path));});
export const places=computed(()=>[...fileIndex.value.folders.values()].filter(entry=>!entry.path.includes('/')).map(entry=>({path:entry.path,count:entry.count||0})).sort((a,b)=>a.path.localeCompare(b.path,'zh-CN',{numeric:true})));
export const groups = computed(() => (desk.project?.maintenance?.optionalGroups || []).map((g: Data) => ({ ...g, count: desk.files.filter(f => f.optionalGroup === g.id).length })));
export const publishState = computed(() => !desk.project?.releases?.length ? '未发布' : desk.project?.previewStale || !desk.project.preview ? '待检查' : desk.project.preview.changes?.length || desk.project.preview.policyChanges?.length || desk.project.preview.presentationChanges?.length ? '待发布' : desk.files.some(f => !f.published) ? '待检查' : '已发布');
export const latestVersion = computed(() => newestReleases(desk.project?.releases)[0]?.displayVersion || '');
let projectGeneration = 0, loadGeneration = 0;
export class ApiError extends Error {
    constructor(message: string, public status = 0, public code = '') { super(message); }
}
class ProjectChangedError extends Error {
}
const taskKey = 'dfs-admin-import-tasks-v2';
watch(() => desk.dialog, value => { desk.dialogProjectId = value ? desk.projectId : ''; }, { flush: 'sync' });
watch(() => desk.tasks, () => { try {
    localStorage.setItem(taskKey, JSON.stringify(desk.tasks.map(({ file, ...task }) => task)));
}
catch {
    desk.error = '导入清单无法保存在浏览器，刷新前请完成结果复查';
} }, { deep: true });
function taskPath(task: Task, suffix: string) { return `/api/projects/${encodeURIComponent(task.projectId)}${suffix}`; }
function currentProject(id: string) { return desk.projectId === id && desk.project?.id === id; }
export function assertProject(id: string) { if (!currentProject(id))
    throw new Error(`项目 ${id} 的视图已切换或尚未加载，请返回原项目重新确认。`); }
let sessionToken = '';
let suggestedFor = '';
export const apiPath = (suffix = '') => `/api/projects/${encodeURIComponent(desk.projectId)}${suffix}`;
const desktopHeaders = () => ({ 'X-DFS-Desktop': window.__DFS_DESKTOP_TOKEN__ || '', 'X-DFS-Token': sessionToken });
const jobsKey='dfs-admin-backend-tasks-v1';
watch(()=>desk.jobs,()=>{try{localStorage.setItem(jobsKey,JSON.stringify(desk.jobs.map(({result,...task})=>task)));}catch{desk.error='管理任务标识无法保存，请在刷新前完成结果复查';}},{deep:true});
function rememberJob(task:BackendTask){const index=desk.jobs.findIndex(t=>t.id===task.id);if(index<0)desk.jobs.push(task);else desk.jobs[index]={...desk.jobs[index],...task};}
export async function queryJob(id:string,wait=false):Promise<unknown>{
 const deadline=Date.now()+60*60*1000;let pause=50;
 do {
  let task:BackendTask;
  try{const response=await fetch(`/api/tasks/${encodeURIComponent(id)}`,{headers:desktopHeaders(),cache:'no-store'});const body=await response.json();if(!response.ok)throw new ApiError(body.message||'无法读取任务',response.status);task=parseBackendTask(body);rememberJob(task);}
  catch(error){const job=desk.jobs.find(t=>t.id===id);if(job){job.state='UNKNOWN';job.error='尚不能确认服务端结果，请保留此任务并复查';}throw error;}
  if(task.state==='SUCCEEDED')return task.result;
  if(backendTaskFinished(task))throw new ApiError(task.error||'任务没有执行完成',task.status,'task_failed');
  if(backendTaskNeedsReview(task))throw new ApiError(task.error||'实际结果待核对，不能自动重做',409,'result_unknown');
  if(!wait)return task;
  await new Promise(resolve=>setTimeout(resolve,pause));pause=Math.min(300,pause*2);
 }while(Date.now()<deadline);
 throw new ApiError('任务仍在执行，已保留标识；请稍后复查',408,'result_unknown');
}
export async function recheckJob(id:string){
 await queryJob(id,false);const job=desk.jobs.find(task=>task.id===id);
 if(job?.state==='SUCCEEDED'&&job.projectId===desk.projectId){
  if(job.kind==='files/commit'&&job.result&&typeof job.result==='object'){
   const result=job.result as {id?:string;undone?:boolean};
   if(typeof result.id==='string'&&typeof result.undone==='boolean')for(const task of desk.tasks){if(task.projectId===job.projectId&&task.plan?.id===result.id){task.state=result.undone?'undone':'done';task.plan.committed=!result.undone;task.plan.undone=result.undone;}}
  }
  await loadProject();
 }
}
export async function refreshJobs(){
 if(!desk.state.capabilities?.includes('application-tasks'))return;
 const data=await request('/api/tasks');for(const raw of data.tasks||[])rememberJob(parseBackendTask(raw));
}
export function dismissJob(task:BackendTask){if(confirm('请先在原项目或外部目标核对实际结果。移出提示不会取消、撤销或重新执行任务。确认已核对？'))task.dismissed=true;}
export async function cancelJob(task:BackendTask){rememberJob(parseBackendTask(await request(`/api/tasks/${encodeURIComponent(task.id)}`,'DELETE')));}
export async function request(path:string,method='GET',body?:unknown,background=false):Promise<any>{
 const owner=path.match(/^\/api\/projects\/([^/?]+)/)?.[1],generation=projectGeneration;
 const asynchronous=method==='POST'&&body!==undefined&&desk.state.capabilities?.includes('application-tasks')&&(/^\/api\/projects\/[^/]+\/(scan|publish|rollback|programs|program-package|instance|deployment|distribution-export|distribution-webdav|distribution-s3|files\/(commit|stage-server|check-imports|undo-import))$/.test(path)||path==='/api/storage/clean');
 const id=asynchronous?crypto.randomUUID():'';
 if(id)rememberJob({id,projectId:owner?decodeURIComponent(owner):'',kind:path.replace(/^\/api\/projects\/[^/]+\//,'').replace(/^\/api\//,''),state:'QUEUED',phase:'等待服务端确认',completed:0,total:1,status:202,error:''});
 let response:Response;
 try{response=await fetch(path,{method,headers:{...desktopHeaders(),...(body!==undefined?{'Content-Type':'application/json'}:{}),...(id?{'Prefer':'respond-async','X-DFS-Request-Id':id}:{})},body:body!==undefined?JSON.stringify(body):undefined,cache:'no-store'});}
 catch(error){if(id){let outcome:unknown;try{outcome=await queryJob(id,true);}catch{throw new ApiError(`任务 ${id} 的结果尚不能确认，请先复查原任务`,0,'result_unknown');}if(!background&&owner&&(decodeURIComponent(owner)!==desk.projectId||generation!==projectGeneration))throw new ProjectChangedError('原项目的任务已完成，请返回原项目查看');return outcome;}throw error;}
 let result=await response.json().catch(()=>({message:response.statusText}));
 if(!response.ok){if(response.status===401){desk.auth.authenticated=false;desk.ready=false;}if(id){const job=desk.jobs.find(t=>t.id===id);if(job){job.state=response.status===503?'FAILED':'UNKNOWN';job.error=result.message||'请求未完成';job.status=response.status;}}throw new ApiError(result.message||`操作失败 (${response.status})`,response.status,result.error||result.code||'');}
 if(response.status===202){if(!id||result.taskId!==id)throw new Error('服务端返回了不一致的任务身份，已停止自动操作');result=await queryJob(id,true);}
 else if(id){const job=desk.jobs.find(task=>task.id===id);if(job)rememberJob({...job,state:'SUCCEEDED',phase:'已完成（同步响应）',completed:1,total:1,result,error:'',status:response.status});}
 if(!background&&owner&&(decodeURIComponent(owner)!==desk.projectId||generation!==projectGeneration))throw new ProjectChangedError(method==='GET'?'已忽略旧项目的迟到响应':`项目 ${decodeURIComponent(owner)} 的操作已完成，请返回该项目查看结果`);
 return result;
}
export async function operation(work: () => Promise<any>, label = '正在处理…') {
    if(desk.jobs.some(job=>backendTaskNeedsReview(job)&&(!job.projectId||job.projectId===desk.projectId)))throw new Error('请先在任务清单中核对原操作的实际结果');
    if (desk.busy)
        throw new Error('请等待当前操作完成');
    desk.busy = true;
    desk.busyText = label;
    desk.error = '';
    try {
        return await work();
    }
    catch (e) {
        desk.error = String(e instanceof Error ? e.message : e);
        throw e;
    }
    finally {
        desk.busy = false;
        desk.busyText = '';
    }
}
export function safely(work: () => Promise<any>) { void work().catch(e => { if (!(e instanceof ProjectChangedError))
    desk.error = e instanceof Error ? e.message : String(e); }); }
export function notify(text: string) { desk.notice = text; setTimeout(() => { if (desk.notice === text)
    desk.notice = ''; }, 4000); }
export function setTheme(theme: string) { desk.theme = theme; document.documentElement.dataset.theme = theme; localStorage.setItem('dfs-desktop-theme', theme); localStorage.setItem('dfs-admin-theme', theme); }
export async function boot() { restoreTasks(); setTheme(desk.theme); desk.auth = await request('/api/auth/status'); if (!authRequiresForm(desk.auth) && desk.auth.authenticated)
    await enter(); }
export async function enter() { const session = await request('/api/session'); sessionToken = session.token; await refresh(); await refreshJobs(); desk.ready = true; if (desk.project && desk.view === 'changes')
    await openView(desk.view, desk.section); }
export async function authenticate(form: Data) { await request(desk.auth.registered ? '/api/auth/login' : '/api/auth/register', 'POST', form); desk.auth = await request('/api/auth/status'); await enter(); }
export async function refresh() {
    const state = await request('/api/state');
    desk.state = state;
    const projects = state.projects || [];
    const id = projects.some((p: Data) => p.id === desk.projectId) ? desk.projectId : state.defaultProjectId || projects[0]?.id || '';
    if (id !== desk.projectId) {
        projectGeneration++;
        desk.projectId = id;
        resetProjectNavigation();
    }
    if (id)
        await loadProject();
    else {
        desk.project = null;
        desk.files = [];
        desk.directories = [];
    }
}
export async function loadProject() {
    const id = desk.projectId, generation = ++loadGeneration, epoch = projectGeneration;
    const paths = new Set(desk.selection.map(e => e.path));
    const base = `/api/projects/${encodeURIComponent(id)}`;
    const [details, source] = await Promise.allSettled([request(base), request(base + '/files')]);
    if (generation !== loadGeneration || epoch !== projectGeneration || id !== desk.projectId)
        return;
    if (details.status === 'rejected') {
        desk.project = null;
        desk.files = [];
        desk.directories = [];
        throw details.reason;
    }
    if (source.status === 'rejected' && !desk.auth.authenticated)
        throw source.reason;
    const data = source.status === 'fulfilled' ? source.value : {};
    desk.project = details.value;
    desk.sourceError = source.status === 'rejected' ? String(source.reason.message || source.reason) : '';
    desk.files = data.files || [];
    desk.directories = data.directories || [];
    desk.selection = desk.selection.filter(e => e.directory && desk.directories.includes(e.path)).concat(desk.files.filter(f => paths.has(f.path)).map(f => ({ ...f, name: nameOf(f.path), directory: false })));
    const latest = newestReleases(desk.project?.releases)[0], key = `${id}:${latest?.releaseId || 'new'}`;
    if (suggestedFor !== key || !desk.releaseVersion) {
        desk.releaseVersion = suggestVersion(latest?.displayVersion);
        suggestedFor = key;
    }
}
export async function switchProject(id: string) {
    if (desk.uploading || desk.busy || desk.tasks.some(t => !taskFinished(t)))
        throw new Error('请先完成或移出导入清单，再切换项目');
    if (desk.settingsDirty && !confirm('有尚未保存的设置，离开当前项目？'))
        return;
    projectGeneration++;
    desk.settingsDirty = false;
    desk.projectId = id;
    desk.project = null;
    desk.files = [];
    desk.directories = [];
    resetProjectNavigation();
    await loadProject();
}
export function resetProjectNavigation() { desk.directory = ''; desk.group = ''; desk.search = ''; desk.filter = 'all'; desk.selection = []; desk.historyPaths = ['']; desk.historyIndex = 0; desk.releaseNotes = ''; desk.releaseVersion = ''; desk.operations = []; desk.releaseFiles = []; desk.operationPlan = null; desk.dialog = ''; desk.dialogData = {}; desk.browseValues = {}; desk.browse = {}; desk.sourceError = ''; }
export function navigate(path: string, record = true) { desk.directory = path; desk.selection = []; desk.search = ''; desk.group = ''; if (record) {
    desk.historyPaths = desk.historyPaths.slice(0, desk.historyIndex + 1);
    desk.historyPaths.push(path);
    desk.historyIndex = desk.historyPaths.length - 1;
} }
export function travel(offset: number) { const i = desk.historyIndex + offset; if (i >= 0 && i < desk.historyPaths.length) {
    desk.historyIndex = i;
    navigate(desk.historyPaths[i], false);
} }
export async function openView(view: string, section?: string) { if (desk.settingsDirty && view !== desk.view) {
    if (!confirm('放弃尚未保存的修改并离开？'))
        return;
    desk.settingsDirty = false;
} const changed = desk.view !== view; desk.view = view; desk.menu = ''; if (section)
    desk.section = section;
else if (changed)
    desk.section = ({ changes: 'changes', delivery: 'deployment', appearance: 'branding', settings: 'project' } as Record<string, string>)[view] || 'files'; if (view === 'changes' && desk.project) {
    if (desk.section === 'changes' && (changed || !desk.project.preview || desk.project.previewStale || desk.files.some(f => !f.published)))
        await scan();
    if (desk.section === 'operations')
        await history();
    if (desk.section === 'problems')
        await getHistoryFiles();
} }
export async function scan() { await operation(async () => { const preview = await request(apiPath('/scan'), 'POST', {}); if (desk.project) {
    desk.project.preview = preview;
    desk.project.previewStale = false;
} if (preview.changes?.some((c: Data) => c.kind === 'REMOVED' && typeof c.fileSize !== 'number'))
    await getHistoryFiles(); }, '正在检查整合包…'); }
function suggestVersion(version?: string) { if (!version)
    return '1.0.0'; const m = version.match(/^(.*?)(\d+)$/); return m ? m[1] + (Number(m[2]) + 1) : ''; }
export async function publish(ack: boolean) {
    await operation(async () => {
        const id = desk.projectId, preview = desk.project?.preview;
        if (!preview)
            throw new Error('请先检查改动');
        assertProject(id);
        try {
            await request(apiPath('/publish'), 'POST', { displayVersion: desk.releaseVersion, minimumPlayerVersion: preview.policyPlayerVersion || '0.2.0', changelog: desk.releaseNotes, previewId: preview.previewId, previewDigest: preview.previewDigest, acknowledgePlayerUpgrade: ack });
        }
        catch (error) {
            if (currentProject(id)) {
                try {
                    const refreshed = await request(apiPath('/scan'), 'POST', {});
                    desk.project!.preview = refreshed;
                    desk.project!.previewStale = false;
                }
                catch {
                    desk.project!.previewStale = true;
                }
            }
            throw new Error(`${error instanceof Error ? error.message : error}；请核对最新检查并重新确认发布。`);
        }
        await refresh();
        if (currentProject(id))
            desk.releaseNotes = '';
        notify('新版本已发布');
    }, '正在发布整合包…');
}
export async function applyPreset(preset: string | null,owner=desk.project?.id||desk.projectId) { assertProject(owner);await operation(async () => { await request(apiPath('/maintenance/presets'), 'POST', { items: desk.selection.map(e => ({ path: e.path, directory: e.directory })), preset }); await loadProject(); notify('维护设置已保存，发布后生效'); }, '正在保存维护设置…'); }
export async function removeSelected(action: string,owner=desk.dialogProjectId||desk.project?.id||desk.projectId) {assertProject(owner); const paths = desk.selection.flatMap(e => e.directory ? desk.files.filter(f => f.path.startsWith(e.path + '/')).map(f => f.path) : [e.path]); await operation(async () => { await request(apiPath('/files/remove-batch'), 'POST', { paths: [...new Set(paths)], action }); desk.dialog = ''; await loadProject(); notify('文件已移出，检查并发布后更新玩家副本'); }, '正在移出文件…'); }
export async function makeDirectory(path: string,owner=desk.dialogProjectId||desk.project?.id||desk.projectId) {assertProject(owner); await request(apiPath('/files/directory'), 'POST', { path }); await loadProject(); desk.dialog = ''; navigate(path); }
let historyGeneration = 0;
export async function history() { const generation = ++historyGeneration, project = desk.projectId; desk.historyLoading = true; try {
    const entries = await request(apiPath('/operations'));
    if (generation === historyGeneration && project === desk.projectId)
        desk.operations = entries;
}
finally {
    if (generation === historyGeneration)
        desk.historyLoading = false;
} }
export async function restorePreview(id?: string) { desk.operationPlan = await request(apiPath(id ? '/operations/undo-preview' : '/operations/published-preview'), id ? 'POST' : 'GET', id ? { id } : undefined); desk.dialogData = { id }; desk.dialog = 'restore'; }
export async function restoreConfirm() {assertProject(desk.dialogProjectId);if(!desk.operationPlan)throw new Error('恢复预览已失效，请重新确认');await operation(async () => { const id = desk.dialogData.id; const result = await request(apiPath(id ? '/operations/undo' : '/operations/restore-published'), 'POST', { id, stamp: desk.operationPlan?.stamp }); desk.dialog = ''; await loadProject(); await history(); notify(result.message || '已恢复，检查并发布后生效'); if (result.checkError)
    desk.error = `已恢复；发布检查未通过：${result.checkError}`; }, '正在恢复…'); }
export async function getHistoryFiles() { const result = await request(apiPath('/history')); desk.releaseFiles = result.files || []; }
export async function saveProject(values: Data, owner = desk.project?.id || desk.projectId) { assertProject(owner); await operation(async () => { desk.project = await request(apiPath(), 'PUT', values); desk.settingsDirty = false; await refresh(); notify('项目设置已保存'); }, '正在保存项目…'); }
export async function service(action: string) { await operation(async () => { await request(`/api/public-service/${action}`, 'POST', {}); desk.state.publicService = await request('/api/public-service/status'); notify(action === 'stop' ? '下载服务已停止' : '下载服务已启动'); }); }
export async function binary(path: string, body: Blob | File, method = 'PUT') { const owner = desk.projectId, generation = projectGeneration; const response = await fetch(path, { method, headers: { ...desktopHeaders(), 'Content-Type': 'application/octet-stream' }, body }); const data = await response.json(); if (!response.ok)
    throw new ApiError(data.message, response.status, data.error); if (owner !== desk.projectId || generation !== projectGeneration)
    throw new ProjectChangedError('已忽略旧项目的文件响应'); return data; }
export async function blob(path: string, method = 'GET', body?: Data) { const owner = desk.projectId, generation = projectGeneration; const response = await fetch(path, { method, headers: { ...desktopHeaders(), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined }); if (!response.ok) {
    const err = await response.json();
    throw new Error(err.message);
} const content = await response.blob(); if (owner !== desk.projectId || generation !== projectGeneration)
    throw new ProjectChangedError('已忽略旧项目的资源响应'); return { blob: content, name: response.headers.get('Content-Disposition')?.match(/filename\*?=(?:UTF-8'')?"?([^";]+)/)?.[1] }; }
export async function downloadDeployment(releaseId: string, platform: string, programVersion = '') {
    await operation(async () => {
        const projectId = desk.projectId, generation = projectGeneration;
        assertProject(projectId);
        desk.deploymentDownload = null;
        desk.notice = '';
        const version = desk.project?.releases?.find((release: Data) => release.releaseId === releaseId)?.displayVersion || '';
        const result = await saveDeployment({ projectId, releaseId, platform,
            fileName: deploymentFileName(projectId, version, programVersion, platform), headers: desktopHeaders() });
        if (!currentProject(projectId) || generation !== projectGeneration) throw new ProjectChangedError('部署包操作属于原项目，请返回原项目查看保存结果');
        desk.deploymentDownload = { ...result, projectId };
        notify(result.state === 'saved' ? '部署包已保存' : result.state === 'canceled' ? '已取消保存部署包' : '已交给浏览器下载，请在浏览器下载列表查看保存结果');
    }, '正在准备并保存部署包…');
}
export async function openDeploymentFolder() {
    const result = desk.deploymentDownload;
    if (!result?.receiptId || result.state !== 'saved') throw new Error('还没有已保存的桌面部署包');
    assertProject(result.projectId);
    await revealDeployment(result.receiptId);
}
export function addUploads(files: File[], keepFolder = false) {
    assertProject(desk.projectId);
    desk.transferTarget = desk.directory;
    for (const file of files) {
        const relative = (file as File & {
            webkitRelativePath: string;
        }).webkitRelativePath;
        let path = relative || file.name;
        if (relative && !keepFolder)
            path = relative.split('/').slice(1).join('/');
        path = [desk.directory, path].filter(Boolean).join('/');
        const id = crypto.randomUUID();
        desk.tasks.push({ id, requestId: id, projectId: desk.projectId, name: file.name, path, size: file.size, file, progress: 0, state: 'queued' });
    }
    desk.tasksOpen = true;
}
function applyPlan(task: Task, plan: ImportPlan) {
    task.plan = plan;
    task.path = plan.path;
    task.progress = 100;
    task.error = '';
    task.state = plan.undone ? 'undone' : plan.committed ? 'done' : 'review';
    task.action = plan.existing?.sha256 === plan.sha256 ? 'SKIP' : plan.replacements.length ? 'REPLACE_MOD' : plan.existing ? 'OVERWRITE' : 'ADD';
}
async function inspectImports(projectId: string, tasks: Task[]) {
    const completed = tasks.filter(imported);
    if (!completed.length)
        return;
    try {
        await request(`/api/projects/${encodeURIComponent(projectId)}/files/check-imports`, 'POST', { ids: completed.map(t => t.plan!.id) }, true);
        for (const t of completed) {
            t.state = 'done';
            t.error = '';
        }
    }
    catch (error) {
        for (const t of completed) {
            t.state = 'check-error';
            t.error = `已导入，发布检查未通过：${error instanceof Error ? error.message : error}`;
        }
    }
    finally {
        if (currentProject(projectId))
            await loadProject();
    }
}
export async function checkUploads() {
    if (desk.uploading)
        return;
    desk.uploading = true;
    desk.uploadPaused = false;
    const recovered = new Set<string>();
    try {
        for (const task of desk.tasks) {
            if (desk.uploadPaused)
                break;
            if (!['queued', 'error', 'unknown', 'expired', 'needs-file', 'check-error'].includes(task.state))
                continue;
            if (task.state === 'check-error') {
                recovered.add(task.projectId);
                continue;
            }
            const wasUnknown = task.state === 'unknown';
            if(wasUnknown&&!task.plan&&!desk.state.capabilities?.includes('source-request-id')){task.error='旧管理服务无法按请求身份查询丢失的上传回执，请升级管理端后核对；不会自动重新上传';continue;}
            task.state = 'checking';
            task.error = '';
            try {
                let plan: ImportPlan | undefined;
                if (task.plan || wasUnknown) {
                    try {
                        plan = parseImportPlan(await request(taskPath(task, '/files/recheck'), 'POST', { id: task.plan?.id || task.requestId }, true));
                    }
                    catch (error) {
                        if (!(error instanceof ApiError && error.status === 410)) {
                            task.state = 'unknown';
                            throw new Error(`尚不能确认结果：${error instanceof Error ? error.message : error}`);
                        }
                        task.plan = undefined;
                        task.requestId=crypto.randomUUID();
                        task.state = 'expired';
                    }
                }
                if (!plan) {
                    if (task.serverPath) {
                        plan = parseImportPlan(await request(taskPath(task, '/files/stage-server'), 'POST', { sourcePath: task.serverPath, targetPath: task.path, requestId: task.requestId }, true));
                    }
                    else if (task.file instanceof Blob) {
                        task.state = 'uploading';
                        plan = parseImportPlan(await uploadWithProgress(task));
                    }
                    else {
                        task.state = 'needs-file';
                        throw new Error('浏览器文件无法在刷新后恢复，请移出此项并重新选择文件；原提交结果仍可复查');
                    }
                }
                applyPlan(task, plan);
                if (plan.committed)
                    recovered.add(task.projectId);
            }
            catch (error) {
                if (!['unknown', 'needs-file', 'expired'].includes(task.state))
                    task.state = 'unknown';
                task.error = String(error instanceof Error ? error.message : error);
            }
        }
    }
    finally {
        for (const id of recovered)
            await inspectImports(id, desk.tasks.filter(t => t.projectId === id));
        desk.uploading = false;
    }
}
let activeUpload: XMLHttpRequest | null = null;
export function cancelUpload() { desk.uploadPaused = true; activeUpload?.abort(); }
function uploadWithProgress(task: Task): Promise<unknown> {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        activeUpload = xhr;
        xhr.open('PUT', taskPath(task, `/files/stage?path=${encodeURIComponent(task.path)}&requestId=${encodeURIComponent(task.requestId)}`));
        for (const [key, value] of Object.entries(desktopHeaders()))
            xhr.setRequestHeader(key, value);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.timeout = 30 * 60 * 1000;
        xhr.upload.onprogress = e => { if (e.lengthComputable)
            task.progress = Math.round(e.loaded / e.total * 100); };
        xhr.onload = () => { activeUpload = null; try {
            const data = JSON.parse(xhr.responseText);
            if (xhr.status < 200 || xhr.status >= 300)
                reject(new ApiError(data.message, xhr.status, data.error));
            else
                resolve(data);
        }
        catch {
            reject(new Error('上传结果无效，需复查'));
        } };
        const failed = () => { activeUpload = null; reject(new Error('上传已中断，结果待确认；重试将先复查')); };
        xhr.onerror = failed;
        xhr.onabort = failed;
        xhr.ontimeout = failed;
        xhr.send(task.file);
    });
}
export async function discardTask(task: Task) {
    if (task.state === 'unknown' || task.state === 'importing')
        throw new Error('提交结果尚未确认，请先复查再移出清单');
    if (task.plan && !imported(task))
        await request(taskPath(task, `/files/stage?id=${encodeURIComponent(task.plan.id)}`), 'DELETE', undefined, true);
    desk.tasks = desk.tasks.filter(t => t.id !== task.id);
}
export async function commitUploads() {
    if (desk.uploading)
        return;
    desk.uploading = true;
    desk.uploadPaused = false;
    const projects = new Set<string>();
    try {
        for (const task of desk.tasks) {
            if (desk.uploadPaused)
                break;
            if (task.state !== 'review' || !task.plan)
                continue;
            if(task.action==='SKIP'){
                const released=await request(taskPath(task,`/files/stage?id=${encodeURIComponent(task.plan.id)}`),'DELETE',undefined,true);
                if(released.discarded===true){task.state='skipped';task.error='';}
                else if(released.protected===true){const actual=parseImportPlan(await request(taskPath(task,'/files/recheck'),'POST',{id:task.plan.id},true));applyPlan(task,actual);if(actual.committed)projects.add(task.projectId);else if(!actual.undone)throw new Error('原操作尚未结束，不能将它标记为已跳过');}
                else throw new Error('管理端无法明确确认暂存释放状态，请升级后复查原任务');
                continue;
            }
            if (!task.action || !['ADD', 'OVERWRITE', 'REPLACE_MOD'].includes(task.action))
                throw new Error('不支持的导入动作');
            task.state = 'importing';
            try {
                await request(taskPath(task, '/files/commit'), 'POST', { id: task.plan.id, action: task.action, stamp: task.plan.stamp }, true);
                task.state = 'done';
                task.plan.committed = true;
                projects.add(task.projectId);
            }
            catch (error) {
                task.state = 'unknown';
                task.error = `结果待确认：${error instanceof Error ? error.message : error}；重试将先复查，避免重复导入`;
            }
        }
        for (const id of projects)
            await inspectImports(id, desk.tasks.filter(t => t.projectId === id));
        if (projects.size)
            notify('文件已导入；请查看每项的发布检查结果');
    }
    finally {
        desk.uploading = false;
    }
}
export function restoreTasks() {
    try {const jobs=JSON.parse(localStorage.getItem(jobsKey)||'[]');if(Array.isArray(jobs))desk.jobs=jobs.map(parseBackendTask);

        const saved = JSON.parse(localStorage.getItem(taskKey) || '[]');
        if (!Array.isArray(saved))
            return;
        desk.tasks = saved.filter((t: Task) => t && typeof t.projectId === 'string' && typeof t.id === 'string' && typeof t.requestId === 'string').map((t: Task) => ({ ...t, file: undefined, state: ['uploading', 'checking', 'importing'].includes(t.state) ? 'unknown' : t.state }));
    }
    catch {
        desk.error = '导入清单记录无法读取，请检查原项目的操作记录';
    }
}
export function openBrowse(kind: string, action: string, path = '') { if (desk.dialog !== 'project-create')
    desk.dialogData = {}; desk.browseKind = kind; desk.browseAction = action; desk.browsePath = path; desk.browseSelected = ''; desk.dialog = 'browse'; safely(() => browse(path)); }
let browseGeneration = 0;
export async function browse(path: string) { const generation = ++browseGeneration, owner = desk.projectId, epoch = projectGeneration; const result = await request('/api/system/browse-path', 'POST', { kind: desk.browseKind, path }); if (generation !== browseGeneration || owner !== desk.projectId || epoch !== projectGeneration)
    return; desk.browse = result; desk.browsePath = desk.browse.currentPath || path; desk.browseSelected = desk.browse.selectedPath || ''; }
export async function serverImport(path: string) {
    const projectId = desk.projectId;
    assertProject(projectId);
    const id = crypto.randomUUID();
    const task: Task = { id, requestId: id, projectId, serverPath: path, name: nameOf(path.replaceAll('\\', '/')), path: [desk.directory, nameOf(path.replaceAll('\\', '/'))].filter(Boolean).join('/'), size: 0, progress: 0, state: 'queued' };
    desk.tasks.push(task);
    desk.tasksOpen = true;
    desk.dialog = '';
    await checkUploads();
}
