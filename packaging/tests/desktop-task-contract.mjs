import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {pathToFileURL} from 'node:url';

const repo=path.resolve(import.meta.dirname,'../..');
const options=Object.fromEntries(process.argv.slice(2).reduce((pairs,value,index,args)=>index%2===0?[...pairs,[value,args[index+1]]]:pairs,[]));
const output=path.resolve(options['--output']||path.join(repo,'target','desktop-task-contract-'+crypto.randomUUID()));
assert.ok(!fs.existsSync(output),'use a new isolated fixture directory');
assert.ok(options['--proxy']&&options['--java'],'provide --proxy and --java executable paths');
fs.mkdirSync(output,{recursive:true});
const nativeFetch=globalThis.fetch;
let java,proxy;
function start(executable,args){
 const child=spawn(executable,args,{cwd:repo,windowsHide:true,stdio:['pipe','pipe','pipe']});
 child.stderrText='';child.stderr.on('data',bytes=>{child.stderrText=(child.stderrText+bytes.toString()).slice(-32768);});
 return child;
}
function ready(child,predicate){return new Promise((resolve,reject)=>{
 const reader=createInterface({input:child.stdout});
 const timer=setTimeout(()=>finish(new Error('fixture startup deadline: '+child.stderrText)),30000);
 function finish(error,value){clearTimeout(timer);reader.close();child.off('error',failed);child.off('exit',exited);error?reject(error):resolve(value);}
 function failed(error){finish(error);}function exited(code){finish(new Error(`fixture exited ${code}: ${child.stderrText}`));}
 child.once('error',failed);child.once('exit',exited);
 reader.on('line',line=>{try{const value=JSON.parse(line);if(predicate(value))finish(null,value);}catch{/* readiness is a JSON protocol; non-JSON diagnostics are not commands */}});
});}
async function stop(child,command){
 if(!child||!child.pid||child.exitCode!==null)return;
 const exited=new Promise(resolve=>child.once('exit',resolve));
 child.stdin.on('error',()=>{});child.stdin.end(command+'\n');
 let timeout;const code=await Promise.race([exited,new Promise(resolve=>{timeout=setTimeout(()=>resolve('timeout'),12000);})]);clearTimeout(timeout);
 if(code==='timeout'){child.kill();await exited;throw new Error('fixture did not shut down within its budget');}
 assert.equal(code,0,child.stderrText);
}
try {
 const home=path.join(output,'home');fs.mkdirSync(home);
 java=start(path.resolve(options['--java']),['-Xms64m','-Xmx256m','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8',`-Ddfs.home=${home}`,'-jar',path.resolve(options['--jar']||path.join(repo,'management-cli/target/dfs-admin.jar')),'desktop']);
 const upstream=await ready(java,value=>value.type==='ready');
 proxy=start(path.resolve(options['--proxy']),[upstream.url]);
 const endpoint=await ready(proxy,value=>typeof value.url==='string'&&typeof value.token==='string');
 const httpLifetime=AbortSignal.timeout(60000);
 const requestSignal=()=>AbortSignal.any([httpLifetime,AbortSignal.timeout(15000)]);
 const headers={'X-DFS-Desktop':endpoint.token,'Content-Type':'application/json'};
 async function api(relative,body,method=body===undefined?'GET':'POST'){
  const response=await nativeFetch(new URL(relative,endpoint.url),{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:requestSignal()});
  const result=await response.json();assert.ok(response.ok,JSON.stringify(result));return result;
 }
 const password='Synthetic-task-contract-20261019!';
 await api('/api/auth/register',{username:'task-fixture',password,confirmPassword:password,allowLocalBypass:false});
 headers['X-DFS-Token']=(await api('/api/session')).token;
 for(const id of ['demo','other']){
  const source=path.join(output,'source-'+id);fs.mkdirSync(source);fs.writeFileSync(path.join(source,'config.txt'),'synthetic configuration');
  await api('/api/projects',{id,displayName:id,sourceDirectory:source,publicBaseUrl:'http://127.0.0.1:8080'});
 }
 const memory=new Map();
 globalThis.localStorage={getItem:key=>memory.get(key)??null,setItem:(key,value)=>memory.set(key,String(value))};
 globalThis.location={search:'?project=demo'};globalThis.window={__DFS_DESKTOP_TOKEN__:endpoint.token};
 globalThis.confirm=()=>true;
 const traces=[];let loseAcknowledgement=false;
 globalThis.fetch=async(relative,init={})=>{
  const response=await nativeFetch(new URL(relative,endpoint.url),{...init,signal:requestSignal()});
  const scan=relative==='/api/projects/demo/scan'&&init.method==='POST';
  if(scan){traces.push({status:response.status,id:init.headers['X-DFS-Request-Id']});if(loseAcknowledgement){loseAcknowledgement=false;await response.body.cancel();throw new TypeError('Synthetic lost acknowledgement');}}
  return response;
 };
 const require=createRequire(path.join(repo,'player-ui/package.json'));const {build}=require('esbuild');
 const bundle=await build({stdin:{contents:"export * from './store'; export {backendTaskFinished} from './backend-tasks';",resolveDir:path.join(repo,'admin-desktop/src/workbench'),loader:'ts'},bundle:true,write:false,platform:'node',format:'esm'});
 const bundleFile=path.join(output,'store.mjs');fs.writeFileSync(bundleFile,bundle.outputFiles[0].text);
 const store=await import(pathToFileURL(bundleFile).href);
 // Import Vue in its headless environment, then provide only the theme surface
 // used by boot(); no browser rendering is claimed by this HTTP contract test.
 globalThis.document={documentElement:{dataset:{}}};
 await store.boot();await store.switchProject('demo');
 await store.scan();
 assert.equal(traces[0].status,202);assert.ok(traces[0].id);
 assert.equal(store.desk.jobs.find(job=>job.id===traces[0].id).state,'SUCCEEDED');
 assert.ok(store.desk.jobs.every(store.backendTaskFinished));
 await store.switchProject('other');assert.equal(store.desk.project.id,'other');await store.switchProject('demo');
 loseAcknowledgement=true;await store.scan();
 assert.equal(traces.length,2,'lost acknowledgement must not issue another POST');
 assert.equal(traces[1].status,202);assert.notEqual(traces[0].id,traces[1].id);
 assert.equal(store.desk.jobs.find(job=>job.id===traces[1].id).state,'SUCCEEDED');
 assert.ok(store.desk.jobs.every(store.backendTaskFinished));
 await store.switchProject('other');assert.equal(store.desk.project.id,'other');
 const result={passed:true,realStore:true,realRustProxy:true,realJavaApi:true,acceptedTaskCount:traces.length,lostAcknowledgementReconciled:true,remainingUnfinishedJobs:0};
 fs.writeFileSync(path.join(output,'result.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify(result));
} finally {
 try{await stop(proxy,'stop');}finally{await stop(java,'{"command":"shutdown"}');}
}
