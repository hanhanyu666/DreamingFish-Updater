import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

function legacy(api){
 const app={project:{id:'a'},transferProjectId:'a',transferTasks:[],transferPaused:false,activeUploads:new Set()};
 const elements=new Map();
 const context=vm.createContext({app,api,crypto,console,byId:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);},
  AdminTransport:{upload:()=>{throw new Error('unexpected reupload');}},confirmAction:async()=>true,loadProject:async()=>{},loadSourceFiles:async()=>{},transferPath:(_,path)=>path});
 vm.runInContext(fs.readFileSync(new URL('../../management-cli/src/main/resources/cn/dreamingfish/updater/management/cli/web/file-transfer.js',import.meta.url),'utf8')+
  '\nrenderTransferQueue=()=>{};globalThis.workflow={checkTransferTasks,commitTransferTasks};',context);
 return {app,...context.workflow};
}
test('legacy skip ends locally and excludes skipped stages from import checks',async()=>{
 const calls=[];const {app,commitTransferTasks}=legacy(async(path,options)=>{calls.push({path,options});return {discarded:true};});
 app.transferTasks=[{path:'a',state:'READY',action:'SKIP',plan:{id:'stage'}}];
 await commitTransferTasks();assert.equal(app.transferTasks[0].state,'SKIPPED');assert.equal(calls.length,1);assert.equal(calls[0].options.method,'DELETE');
});
test('legacy committed result is queried without another upload or commit',async()=>{
 const calls=[];const {app,checkTransferTasks}=legacy(async(path)=>{calls.push(path);return {id:'stage',path:'a',sha256:'hash',committed:true,undone:false,replacements:[]};});
 app.transferTasks=[{path:'a',state:'FAILED',plan:{id:'stage'},resultUnknown:true}];
 await checkTransferTasks();assert.equal(app.transferTasks[0].state,'DONE');assert.equal(calls.some(p=>p.includes('/stage?')||p.endsWith('/commit')),false);
});
test('legacy failed query retains the original stage even for a 400 old API response',async()=>{
 const {app,checkTransferTasks}=legacy(async()=>{throw Object.assign(new Error('old API'),{status:400});});
 app.transferTasks=[{path:'a',state:'FAILED',plan:{id:'stage'},resultUnknown:true}];
 await checkTransferTasks();assert.equal(app.transferTasks[0].plan.id,'stage');assert.equal(app.transferTasks[0].resultUnknown,true);
});

test('legacy skip recognizes an already committed receipt from another window',async()=>{
 const {app,commitTransferTasks}=legacy(async(path,options)=>options?.method==='DELETE'?{discarded:false,protected:true}:path.endsWith('/recheck')?{id:'stage',committed:true,undone:false}:{});
 app.transferTasks=[{path:'a',state:'READY',action:'SKIP',plan:{id:'stage'}}];await commitTransferTasks();assert.equal(app.transferTasks[0].state,'DONE');
});
