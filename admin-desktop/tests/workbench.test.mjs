import test from 'node:test';
import assert from 'node:assert/strict';
import {profilePayload} from '../src/connections.ts';
import {entries,newestReleases,authRequiresForm,changedFileSize} from '../src/workbench/model.ts';
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
