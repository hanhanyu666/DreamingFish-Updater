// Run each variant in its own process. Both perform the same complete folder walk.
import {pathToFileURL} from 'node:url';
import crypto from 'node:crypto';
const [variant,model]=process.argv.slice(2);
if(!['baseline','current'].includes(variant)||!model)throw new Error('Expected baseline/current and model.ts');
const api=await import(pathToFileURL(model).href);
const files=Array.from({length:10000},(_,n)=>({path:`config/group-${Math.floor(n/5)}/file-${n}.txt`,size:n,published:true}));
const directories=['config',...Array.from({length:2000},(_,n)=>`config/group-${n}`),'config/empty'];
const cpu=process.cpuUsage(),start=performance.now();let index;
if(variant==='current')index=api.buildFileIndex(files,directories);
const records=[];
for(const directory of ['',...directories]){
 const rows=variant==='current'?api.indexEntries(index,directory):api.entries(files,directories,directory);
 records.push(rows.map(row=>[row.path,row.directory,row.size,row.count??null]));
}
const elapsed=performance.now()-start,used=process.cpuUsage(cpu);
console.log(JSON.stringify({variant,files:files.length,directories:directories.length,seconds:elapsed/1000,cpuSeconds:(used.user+used.system)/1e6,
 resultSha256:crypto.createHash('sha256').update(JSON.stringify(records)).digest('hex'),maxRssKiB:process.resourceUsage().maxRSS,heapUsedBytes:process.memoryUsage().heapUsed}));
// Keep the process alive until the parent captures final OS counters.
process.stdin.once('data',()=>process.exit(0));process.stdin.resume();
