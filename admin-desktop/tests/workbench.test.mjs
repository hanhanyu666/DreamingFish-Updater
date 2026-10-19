import test from 'node:test';
import assert from 'node:assert/strict';
import {profilePayload} from '../src/connections.ts';
import {entries,buildFileIndex,indexEntries,pageEntries,selectionVisibility,newestReleases,authRequiresForm,changedFileSize} from '../src/workbench/model.ts';
// A form retains values when changing tabs; IPC must still match Rust's strict tagged schema.
const mixed={id:'fixture',name:'工作区',home:'D:/pack',url:'https://admin.example.com/',target:'my-server',remotePort:18080};
test('local connection excludes address and SSH fields',()=>assert.deepEqual(profilePayload({...mixed,kind:'local'}),{id:'fixture',name:'工作区',kind:'local',home:'D:/pack'}));
test('address connection excludes local and SSH fields',()=>assert.deepEqual(profilePayload({...mixed,kind:'remote'}),{id:'fixture',name:'工作区',kind:'remote',url:'https://admin.example.com/'}));
test('SSH connection includes only alias and numeric port',()=>assert.deepEqual(profilePayload({...mixed,kind:'ssh'}),{id:'fixture',name:'工作区',kind:'ssh',target:'my-server',remotePort:18080}));
test('directory listing preserves empty folders and aggregates descendants without sibling prefix matches',()=>{
 const rows=entries([{path:'config/client/a.json',size:17},{path:'config/b.json',size:4},{path:'config-other/c.json',size:99},{path:'readme.txt',size:0}],['config','config/client','config/empty','config-other'],'');
 assert.deepEqual(rows.map(r=>[r.path,r.directory,r.size,r.count]),[['config',true,21,2],['config-other',true,99,1],['readme.txt',false,0,undefined]]);
});
test('nested directory includes only immediate files and folders',()=>{
 const rows=entries([{path:'config/client/a.json',size:17},{path:'config/b.json',size:4},{path:'config-other/c.json',size:99}],['config','config/client','config/empty'],'config');
 assert.deepEqual(rows.map(r=>r.path),['config/client','config/empty','config/b.json']);
});
test('release selection uses the highest sequence for both API orderings without mutating input',()=>{
 const first={sequence:1,displayVersion:'4.0'},latest={sequence:3,displayVersion:'6.0'},middle={sequence:2,displayVersion:'5.0'};
 for(const input of [[first,middle,latest],[latest,middle,first]]){
  const before=[...input];assert.deepEqual(newestReleases(input),[latest,middle,first]);assert.deepEqual(input,before);
 }
});
test('bootstrap local access still requires creating an account; existing signed-in accounts open directly',()=>{
 assert.equal(authRequiresForm({registered:false,authenticated:true}),true);
 assert.equal(authRequiresForm({registered:true,authenticated:false}),true);
 assert.equal(authRequiresForm({registered:true,authenticated:true}),false);
});
test('older API file-size fallback uses file metadata, preserves zero bytes and ignores download estimates',()=>{
 assert.equal(changedFileSize({path:'mods/a.jar',kind:'ADDED',downloadSize:0},[{path:'mods/a.jar',size:123}]),123);
 assert.equal(changedFileSize({path:'empty.txt',kind:'ADDED',fileSize:0,downloadSize:100}),0);
 const history=[{path:'mods/a.jar',versions:[{size:50,currentlyPublished:false},{size:123,currentlyPublished:true}]}];
 assert.equal(changedFileSize({path:'mods/A.jar',kind:'REMOVED',downloadSize:0},[],history),123);
 assert.equal(changedFileSize({path:'unknown',kind:'REMOVED',downloadSize:0}),null);
});

test('large directory aggregation and page boundaries preserve full selection identity',()=>{
 const files=Array.from({length:10000},(_,i)=>({path:`config/group-${Math.floor(i/5)}/file-${i}.txt`,size:i,published:true}));
 const index=buildFileIndex(files,['config/empty']);assert.equal(index.folders.get('config').count,10000);assert.equal(index.folders.get('config').size,49995000);assert.equal(index.folders.get('config/empty').count,0);
 const rows=files.map(f=>({...f,name:f.path,directory:false})),page=pageEntries(rows,1);
 assert.equal(page.length,200);assert.equal(page[0].path,rows[200].path);assert.equal(page.at(-1).path,rows[399].path);
 const shiftSelection=rows.slice(198,202);assert.deepEqual(selectionVisibility(shiftSelection,rows,page),{outsideFilter:0,otherPages:2});
 const filtered=rows.slice(200,202);assert.deepEqual(selectionVisibility(shiftSelection,filtered,filtered),{outsideFilter:2,otherPages:0});
 assert.equal(indexEntries(index,'config/group-0').length,5);assert.equal(indexEntries(index,'config/empty').length,0);
});
