import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';

const require=createRequire(resolve(import.meta.dirname,'../../player-ui/package.json'));
const {build}=require('esbuild');
const bundle=await build({entryPoints:[resolve(import.meta.dirname,'../src/workbench/store.ts')],bundle:true,write:false,platform:'node',format:'esm'});
const code=Buffer.from(bundle.outputFiles[0].text).toString('base64');
const bytes=new Uint8Array([80,75,3,4,1,2,3,4]);
function response(body=bytes,headers={}){
 return new Response(body,{headers:{'Content-Type':'application/zip','Content-Length':String(bytes.length),...headers}});
}
async function store(){
 globalThis.window={};globalThis.location={search:''};
 globalThis.localStorage={getItem:()=>null,setItem:()=>{}};
 globalThis.fetch=async()=>{throw new Error('unexpected request');};
 const mod=await import(`data:text/javascript;base64,${code}#${crypto.randomUUID()}`);
 mod.desk.projectId='demo';mod.desk.project={id:'demo',releases:[{releaseId:'release',displayVersion:'1.0.0'}]};
 return mod;
}
function desktop(invoke){window.__DFS_DESKTOP_META__={workspace:true};window.__TAURI_INTERNALS__={invoke};}

test('desktop reports saved only after the native save completes and reveals by receipt',async()=>{
 const s=await store();let finish;const calls=[];
 desktop(async(command,args)=>{calls.push({command,args});if(command==='save_deployment_download')return await new Promise(resolve=>{finish=resolve;});});
 const pending=s.downloadDeployment('release','windows-x64','0.2.2');
 assert.equal(s.desk.busy,true);assert.equal(s.desk.notice,'');assert.equal(s.desk.deploymentDownload,null);
 assert.equal(calls[0].args.request.fileName,'demo-player-deployment-1.0.0-0.2.2-windows-x64.zip');
 assert.equal('destination' in calls[0].args,false);
 finish({id:'receipt',path:'D:/Downloads/demo.zip',size:bytes.length});await pending;
 assert.equal(s.desk.notice,'部署包已保存');assert.equal(s.desk.deploymentDownload.destination,'D:/Downloads/demo.zip');
 await s.openDeploymentFolder();assert.deepEqual(calls[1],{command:'reveal_deployment_download',args:{receiptId:'receipt'}});
});

test('native cancel does not fetch a ZIP or announce success',async()=>{
 const s=await store();desktop(async()=>null);
 await s.downloadDeployment('release','windows-x64');
 assert.equal(s.desk.deploymentDownload.state,'canceled');assert.equal(s.desk.notice,'已取消保存部署包');assert.equal(s.desk.error,'');assert.equal(s.desk.busy,false);
});

test('a late save cannot attach its result to a new project visit',async()=>{
 const s=await store();let finish;
 desktop(async()=>await new Promise(resolve=>{finish=resolve;}));
 const pending=s.downloadDeployment('release','windows-x64');
 let project='other';
 globalThis.fetch=async path=>new Response(JSON.stringify(path==='/api/state'?{projects:[{id:project}],defaultProjectId:project}:path.endsWith('/files')?{files:[]}:{id:project,releases:[]}),{headers:{'Content-Type':'application/json'}});
 await s.refresh();project='demo';await s.refresh();
 finish({id:'old-receipt',path:'D:/Downloads/original.zip',size:8});
 await assert.rejects(pending,/原项目/);assert.equal(s.desk.deploymentDownload,null);assert.equal(s.desk.notice,'');
});

test('native disk errors remove the previous success and remain visible',async()=>{
 const s=await store();s.desk.notice='old success';s.desk.deploymentDownload={state:'saved',projectId:'demo',destination:'old.zip',size:8};
 desktop(async()=>{throw new Error('磁盘空间不足');});
 await assert.rejects(s.downloadDeployment('release','windows-x64'),/磁盘空间不足/);
 assert.equal(s.desk.deploymentDownload,null);assert.equal(s.desk.notice,'');assert.equal(s.desk.error,'磁盘空间不足');assert.equal(s.desk.busy,false);
});

test('web picker cancel happens before any deployment request',async()=>{
 const s=await store();window.showSaveFilePicker=async()=>{throw new DOMException('Canceled','AbortError');};
 await s.downloadDeployment('release','windows-x64');
 assert.equal(s.desk.deploymentDownload.state,'canceled');assert.equal(s.desk.notice,'已取消保存部署包');
});

test('web save asks before fetching and waits for the file commit',async()=>{
 const s=await store(),events=[];let finish;const received=[];
 window.showSaveFilePicker=async()=>{events.push('picker');return {name:'chosen.zip',createWritable:async()=>new WritableStream({
  write:chunk=>received.push(...chunk),close:()=>new Promise(resolve=>{finish=resolve;})
 })};};
 globalThis.fetch=async()=>{events.push('fetch');return response();};
 const pending=s.downloadDeployment('release','windows-x64');
 while(!finish)await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(events,['picker','fetch']);assert.deepEqual(received,[...bytes]);assert.equal(s.desk.notice,'');assert.equal(s.desk.deploymentDownload,null);
 finish();await pending;assert.equal(s.desk.notice,'部署包已保存');assert.equal(s.desk.deploymentDownload.destination,'chosen.zip');assert.equal(s.desk.deploymentDownload.size,bytes.length);
});

test('incomplete web transfers abort the temporary file instead of committing it',async()=>{
 const s=await store();let closed=0,aborted=0;
 window.showSaveFilePicker=async()=>({name:'existing.zip',createWritable:async()=>new WritableStream({close(){closed++;},abort(){aborted++;}})});
 globalThis.fetch=async()=>response(bytes,{'Content-Length':'100'});
 await assert.rejects(s.downloadDeployment('release','windows-x64'),/没有完整下载/);
 assert.equal(closed,0);assert.equal(aborted,1);assert.equal(s.desk.deploymentDownload,null);assert.equal(s.desk.notice,'');
});

test('web disk and final-commit failures never announce saved',async()=>{
 for(const phase of ['write','close']){
  const s=await store();
  window.showSaveFilePicker=async()=>({name:'existing.zip',createWritable:async()=>new WritableStream({[phase](){throw new Error('write denied');}})});
  globalThis.fetch=async()=>response();
  await assert.rejects(s.downloadDeployment('release','windows-x64'),/write denied/);
  assert.equal(s.desk.notice,'');assert.equal(s.desk.deploymentDownload,null);assert.equal(s.desk.busy,false);
 }
});

test('backend generation failures do not open a writable browser file',async()=>{
 const s=await store();let opened=0;
 window.showSaveFilePicker=async()=>({name:'existing.zip',createWritable:async()=>{opened++;throw new Error('must not write');}});
 globalThis.fetch=async()=>new Response(JSON.stringify({message:'未发布玩家端程序'}),{status:400,headers:{'Content-Type':'application/json'}});
 await assert.rejects(s.downloadDeployment('release','windows-x64'),/未发布玩家端程序/);assert.equal(opened,0);assert.equal(s.desk.notice,'');
});

test('an HTTP 200 login page cannot be saved as a deployment ZIP',async()=>{
 const s=await store();
 window.showSaveFilePicker=async()=>({name:'chosen.zip',createWritable:async()=>{throw new Error('must not write');}});
 globalThis.fetch=async()=>new Response('<html>login</html>',{headers:{'Content-Type':'text/html'}});
 await assert.rejects(s.downloadDeployment('release','windows-x64'),/没有返回 ZIP/);assert.equal(s.desk.deploymentDownload,null);
});

test('ordinary browser fallback reports a handoff rather than a completed save',async t=>{
 t.mock.timers.enable({apis:['setTimeout']});
 const s=await store(),anchors=[];
 const anchor={click(){this.clicked=true;},remove(){this.removed=true;}};
 globalThis.document={createElement:()=>anchor,body:{append:element=>anchors.push(element)}};
 globalThis.fetch=async()=>response(bytes,{'Content-Disposition':'attachment; filename="server-name.zip"'});
 await s.downloadDeployment('release','windows-x64');
 assert.equal(anchor.clicked,true);assert.equal(anchor.removed,true);assert.equal(anchors.length,1);assert.equal(anchor.download,'server-name.zip');
 assert.equal(s.desk.deploymentDownload.state,'browser');assert.match(s.desk.notice,/浏览器下载列表/);assert.doesNotMatch(s.desk.notice,/已保存/);
 await assert.rejects(s.openDeploymentFolder(),/还没有已保存/);t.mock.timers.tick(60_000);
});

test('malformed server filenames cannot prevent the ordinary browser download',async t=>{
 t.mock.timers.enable({apis:['setTimeout']});const s=await store();const anchor={click(){},remove(){}};
 globalThis.document={createElement:()=>anchor,body:{append(){}}};
 globalThis.fetch=async()=>response(bytes,{'Content-Disposition':"attachment; filename*=UTF-8''bad%ZZ.zip"});
 await s.downloadDeployment('release','linux-x64');assert.equal(s.desk.deploymentDownload.state,'browser');assert.match(anchor.download,/demo-player-deployment-1.0.0-linux-x64.zip/);t.mock.timers.tick(60_000);
});
