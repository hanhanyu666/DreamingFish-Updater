import {serverTaskStates,type ServerTaskState} from './server-task-states';
export interface BackendTask {
 id:string;projectId:string;kind:string;state:ServerTaskState;phase:string;completed:number;total:number;
 result?:unknown;error:string;status:number;dismissed?:boolean;
}
export function parseBackendTask(value:unknown):BackendTask {
 const task=value as BackendTask;
 if(!task||typeof task.id!=='string'||typeof task.projectId!=='string'||typeof task.kind!=='string'
   ||!serverTaskStates.includes(task.state)||typeof task.phase!=='string'||typeof task.completed!=='number'
   ||typeof task.total!=='number'||typeof task.status!=='number'||typeof task.error!=='string')
  throw new Error('管理任务状态不兼容，请升级管理端并保留原任务标识');
 return task;
}
export function backendTaskFinished(task:BackendTask){return ['SUCCEEDED','FAILED','CANCELLED','EXPIRED'].includes(task.state);}
export function backendTaskNeedsReview(task:BackendTask){return !task.dismissed&&['UNKNOWN','WAITING_FOR_INPUT'].includes(task.state);}
export function backendTaskReview(task:BackendTask){
 const stopped=task.phase==='执行者已结束，等待核对'||task.error.startsWith('原执行者已释放任务');
 const summary=stopped?'上次任务已经停止，但没有留下成功或失败的记录。':'暂时无法确认这次操作的结果，任务可能仍在服务器上执行。';
 let instruction='先查看原项目的操作记录和实际内容，确认上次操作的结果后，再移出这条提醒。';
 let destination={view:'changes',section:'operations',label:'去查看操作记录'};
 if(task.kind==='official-player-prepare'){
  instruction='到“玩家端程序”查看“管理端已有的程序版本”，确认要下载的版本是否已经保存。下载到管理端本身不会批准玩家升级。';
  destination={view:'delivery',section:'programs',label:'去查看玩家端程序'};
 }else if(['official-player-publish','programs','program-package','program-approval','program-update-policy'].includes(task.kind)){
  instruction='到“玩家端程序”核对当前发布的版本和升级开关，确认玩家实际能收到哪个版本。';
  destination={view:'delivery',section:'programs',label:'去查看玩家端程序'};
 }else if(['publish','rollback'].includes(task.kind)){
  instruction='到“发布版本”查看是否已经生成了这次版本，核对版本号和更新说明后，再决定是否重新发布。';
  destination={view:'changes',section:'releases',label:'去查看发布版本'};
 }else if(task.kind.startsWith('distribution-')){
  instruction='到“外部托管”查看上传记录，并核对服务器目录或 OSS、WebDAV 中的实际文件。';
  destination={view:'delivery',section:'hosting',label:'去查看外部托管'};
 }else if(task.kind.startsWith('files/')||task.kind==='scan'){
  instruction='先查看整合包文件和操作记录，确认原来的文件操作是否已经完成。';
  destination={view:'files',section:'files',label:'去查看整合包文件'};
 }
 if(task.state==='WAITING_FOR_INPUT')instruction+=' 如果需要重试上传，请回到原页面重新填写密钥；系统不会自动重传。';
 return {summary,instruction,destination};
}
export const backendTaskLabels:Record<ServerTaskState,string>={QUEUED:'等待执行',RUNNING:'执行中',COMMITTING:'正在提交',SUCCEEDED:'已完成',FAILED:'执行失败',UNKNOWN:'结果待核对',CANCELLED:'已取消',WAITING_FOR_INPUT:'等待重新提供凭据',EXPIRED:'结果已过期'};
export function backendTaskName(task:BackendTask){return ({scan:'检查改动',publish:'发布版本',rollback:'创建回滚发布',programs:'发布玩家程序','program-package':'发布玩家程序',instance:'准备实例',deployment:'生成部署包','files/commit':'导入文件','files/stage-server':'暂存服务器文件','files/check-imports':'检查已导入内容','files/undo-import':'恢复导入前内容','distribution-export':'导出静态内容','distribution-webdav':'上传静态内容','distribution-s3':'上传静态内容','storage/clean':'清理存储','official-player-publish':'发布官方玩家端','official-player-prepare':'下载官方玩家端'} as Record<string,string>)[task.kind]||'管理任务';}
