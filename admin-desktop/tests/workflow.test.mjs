import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';

// Use the real store, including its reactive state; no copied workflow implementation.
const require=createRequire(resolve(import.meta.dirname,'../../player-ui/package.json'));
const {build}=require('esbuild');
const bundle=await build({entryPoints:[resolve(import.meta.dirname,'../src/workbench/store.ts')],bundle:true,write:false,platform:'node',format:'esm'});
const code=Buffer.from(bundle.outputFiles[0].text).toString('base64');
const ok=value=>({ok:true,status:200,json:async()=>value});
const fail=(status,message,code='')=>({ok:false,status,json:async()=>({message,code})});
const plan=(extra={})=>({id:'00000000-0000-4000-8000-000000000001',path:'config/a',size:4,sha256:'hash',stamp:'stamp',committed:false,undone:false,existing:null,replacements:[],metadata:null,...extra});
async function store(){
 globalThis.location={search:''};globalThis.window={};globalThis.confirm=()=>true;
 globalThis.localStorage={getItem:()=>null,setItem:()=>{}};
 const mod=await import(`data:text/javascript;base64,${code}#${crypto.randomUUID()}`);
 mod.desk.projectId='a';mod.desk.project={id:'a',releases:[]};mod.desk.auth={authenticated:true};
 return mod;
}
function task(extra={}){return {id:crypto.randomUUID(),requestId:crypto.randomUUID(),projectId:'a',name:'a',path:'config/a',size:4,progress:100,state:'review',action:'ADD',plan:plan(),...extra};}

test('A/B/A reverse responses commit one coherent project snapshot and clear dialogs',async()=>{
 const {desk,switchProject}=await store();const pending=[];
 globalThis.fetch=async path=>await new Promise(resolve=>pending.push({path,resolve}));
 desk.dialog='remove';desk.dialogData={path:'from-a'};
 const a1=switchProject('a'),b=switchProject('b'),a2=switchProject('a');
 assert.equal(desk.project,null);assert.equal(desk.dialog,'');assert.deepEqual(desk.dialogData,{});
 for(const index of [4,5,2,3,0,1]){
  const request=pending[index],id=request.path.split('/')[3];
  request.resolve(ok(request.path.endsWith('/files')?{files:[{path:`config/${id}-${index}`,size:4}],directories:['config']}:{id,releases:[]}));
 }
 await Promise.all([a1,b,a2]);assert.equal(desk.project.id,'a');assert.equal(desk.files[0].path,'config/a-5');
});
test('failed project details never leave the previous project actionable',async()=>{
 const {desk,switchProject}=await store();globalThis.fetch=async path=>path.endsWith('/files')?ok({files:[]}):fail(500,'fixture');
 await assert.rejects(switchProject('b'));assert.equal(desk.project,null);assert.deepEqual(desk.files,[]);
});
test('draft ownership rejects a write after a project change',async()=>{
 const {desk,saveProject}=await store();desk.projectId='b';desk.project={id:'b'};
 let writes=0;globalThis.fetch=async()=>{writes++;return ok({});};
 await assert.rejects(saveProject({displayName:'from A'},'a'));assert.equal(writes,0);
});
test('skip has its own terminal state and never sends SKIP to commit or check-imports',async()=>{
 const {desk,commitUploads,switchProject}=await store();desk.tasks=[task({action:'SKIP'})];const calls=[];
 globalThis.fetch=async(path,options)=>{calls.push({path,options});return ok(options.method==='DELETE'?{discarded:true}:path.endsWith('/files')?{files:[]}:{id:'b',releases:[]});};
 await commitUploads();assert.equal(desk.tasks[0].state,'skipped');assert.equal(calls.length,1);assert.equal(calls[0].options.method,'DELETE');
 await switchProject('b');assert.equal(desk.project.id,'b');
});
test('lost commit response is rechecked without uploading or submitting again',async()=>{
 const {desk,checkUploads}=await store();desk.tasks=[task({state:'unknown'})];const calls=[];
 globalThis.XMLHttpRequest=class{constructor(){throw new Error('must not upload');}};
 globalThis.fetch=async(path)=>{calls.push(path);if(path.endsWith('/recheck'))return ok(plan({committed:true}));if(path.endsWith('/check-imports'))return ok({});if(path.endsWith('/files'))return ok({files:[{path:'config/a',size:4}]});return ok({id:'a',releases:[]});};
 await checkUploads();assert.equal(desk.tasks[0].state,'done');assert.equal(desk.tasks[0].plan.id,plan().id);assert.equal(calls.filter(p=>p.endsWith('/recheck')).length,1);assert.equal(calls.some(p=>p.includes('/stage')||p.endsWith('/commit')),false);
});
test('failed result query keeps the original receipt identity',async()=>{
 const {desk,checkUploads}=await store();desk.tasks=[task({state:'unknown'})];globalThis.fetch=async()=>fail(503,'offline');
 await checkUploads();assert.equal(desk.tasks[0].state,'unknown');assert.equal(desk.tasks[0].plan.id,plan().id);assert.match(desk.tasks[0].error,/尚不能确认/);
});
test('server imports retry from the server path without a browser File',async()=>{
 const {desk,checkUploads}=await store();desk.tasks=[task({state:'queued',plan:undefined,serverPath:'C:/fixture/a'})];
 globalThis.fetch=async(path,options)=>{assert.match(path,/stage-server$/);assert.equal(JSON.parse(options.body).requestId,desk.tasks[0].requestId);return ok(plan());};
 await checkUploads();assert.equal(desk.tasks[0].state,'review');
});
test('successful commit refreshes files even when the followup check fails',async()=>{
 const {desk,commitUploads,checkUploads}=await store();desk.tasks=[task()];let commits=0,refreshes=0;
 globalThis.fetch=async(path)=>{if(path.endsWith('/commit')){commits++;return ok({});}if(path.endsWith('/check-imports'))return fail(400,'invalid preview');if(path.endsWith('/files')){refreshes++;return ok({files:[{path:'config/a',size:4}]});}return ok({id:'a',releases:[]});};
 await commitUploads();assert.equal(desk.tasks[0].state,'check-error');assert.equal(refreshes,1);assert.equal(desk.files[0].path,'config/a');
 await checkUploads();assert.equal(commits,1);assert.equal(refreshes,2);
});
test('stale publish refreshes preview and requires a new confirmation',async()=>{
 const {desk,publish}=await store();desk.project.preview={previewId:'old'};const calls=[];
 globalThis.fetch=async(path)=>{calls.push(path);return path.endsWith('/publish')?fail(409,'stale preview'):ok({previewId:'new'});};
 await assert.rejects(publish(false),/重新确认发布/);assert.equal(desk.project.preview.previewId,'new');assert.equal(calls.filter(p=>p.endsWith('/publish')).length,1);
});

test('async API uses one stable identity and polls a typed final result',async()=>{
 const {desk,request}=await store();desk.state={capabilities:['application-tasks']};let writes=0,queries=0,id;
 globalThis.fetch=async(path,options)=>{
  if(options.method==='POST'){writes++;id=options.headers['X-DFS-Request-Id'];assert.equal(options.headers.Prefer,'respond-async');return {...ok({taskId:id}),status:202};}
  queries++;return ok({id,projectId:'a',kind:'scan',state:'SUCCEEDED',phase:'done',completed:1,total:1,status:200,error:'',result:{previewId:'new'}});
 };
 assert.deepEqual(await request('/api/projects/a/scan','POST',{}),{previewId:'new'});assert.equal(writes,1);assert.equal(queries,1);assert.equal(desk.jobs[0].state,'SUCCEEDED');
});
test('lost async acknowledgement queries its saved identity instead of repeating a write',async()=>{
 const {desk,request}=await store();desk.state={capabilities:['application-tasks']};let writes=0,id;
 globalThis.fetch=async(path,options)=>{
  if(options.method==='POST'){writes++;id=options.headers['X-DFS-Request-Id'];throw new TypeError('connection lost');}
  assert.equal(path,`/api/tasks/${id}`);return ok({id,projectId:'a',kind:'publish',state:'SUCCEEDED',phase:'done',completed:1,total:1,status:200,error:'',result:{releaseId:'committed'}});
 };
 assert.deepEqual(await request('/api/projects/a/publish','POST',{}),{releaseId:'committed'});assert.equal(writes,1);
});
test('unknown backend result blocks another mutation until the original operation is reviewed',async()=>{
 const {desk,operation}=await store();desk.jobs=[{id:'known',projectId:'a',kind:'publish',state:'UNKNOWN',phase:'unknown',completed:0,total:1,status:409,error:'unknown'}];let writes=0;
 await assert.rejects(operation(async()=>{writes++;}),/原操作/);assert.equal(writes,0);
});

test('skip reconciles a receipt committed by another window instead of claiming it was skipped',async()=>{
 const {desk,commitUploads}=await store();desk.tasks=[task({action:'SKIP'})];
 globalThis.fetch=async(path,options)=>options.method==='DELETE'?ok({discarded:false,protected:true}):path.endsWith('/recheck')?ok(plan({committed:true})):path.endsWith('/files')?ok({files:[]}):path.endsWith('/check-imports')?ok({}):ok({id:'a',releases:[]});
 await commitUploads();assert.equal(desk.tasks[0].state,'done');assert.equal(desk.tasks[0].plan.committed,true);
});
test('directory dialog writes retain their original project identity',async()=>{
 const {desk,makeDirectory}=await store();desk.projectId='b';desk.project={id:'b'};let writes=0;globalThis.fetch=async()=>{writes++;return ok({});};
 await assert.rejects(makeDirectory('from-a','a'));assert.equal(writes,0);
});

test('successful synchronous fallback finishes the reserved task without polling a missing ID',async()=>{
 for(const status of [200,201]){
  const {desk,request}=await store();desk.state.capabilities=['application-tasks'];let calls=0;
  globalThis.fetch=async(path,options)=>{calls++;assert.equal(options.headers.Prefer,'respond-async');assert.ok(options.headers['X-DFS-Request-Id']);return {ok:true,status,json:async()=>({previewId:'sync-result'})};};
  assert.deepEqual(await request('/api/projects/a/scan','POST',{}),{previewId:'sync-result'});
  assert.equal(calls,1);assert.equal(desk.jobs.length,1);assert.equal(desk.jobs[0].state,'SUCCEEDED');
  assert.deepEqual(desk.jobs[0].result,{previewId:'sync-result'});
 }
});
