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
export const backendTaskLabels:Record<ServerTaskState,string>={QUEUED:'等待执行',RUNNING:'执行中',COMMITTING:'正在提交',SUCCEEDED:'已完成',FAILED:'执行失败',UNKNOWN:'结果待核对',CANCELLED:'已取消排队',WAITING_FOR_INPUT:'等待重新提供凭据',EXPIRED:'结果已过期'};
export function backendTaskName(task:BackendTask){return ({scan:'检查改动',publish:'发布版本',rollback:'创建回滚发布',programs:'发布玩家程序','program-package':'发布玩家程序',instance:'准备实例',deployment:'生成部署包','files/commit':'导入文件','files/stage-server':'暂存服务器文件','files/check-imports':'检查已导入内容','files/undo-import':'恢复导入前内容','distribution-export':'导出静态内容','distribution-webdav':'上传静态内容','distribution-s3':'上传静态内容','storage/clean':'清理存储'} as Record<string,string>)[task.kind]||'管理任务';}
