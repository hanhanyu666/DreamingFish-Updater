import test from 'node:test';
import assert from 'node:assert/strict';
import {effectScope,reactive,ref} from 'vue';
import {emptyHosting,hostingRequest,loadHostingHistory,parseHostingEntries,rememberHosting,restoreHosting,saveHostingHistory} from '../src/workbench/hosting-history.ts';
import {useHostingHistory} from '../src/workbench/hosting-history-state.ts';

const tick=()=>new Promise(resolve=>setImmediate(resolve));
const input=extra=>({...emptyHosting(),outputDirectory:'D:/exports',endpoint:'https://oss.example.test',bucket:'pack',
    accessKeyId:'public-id',password:'private-password',secretAccessKey:'private-secret',sessionToken:'private-session',...extra});
const record=(extra={})=>rememberHosting([],'s3',input(extra),1)[0];
function browser(){
    const data=new Map();
    globalThis.window={};
    globalThis.localStorage={getItem:key=>data.get(key)??null,setItem:(key,value)=>data.set(key,value)};
    return data;
}

test('browser history survives reopening, remembers Access Key ID and cannot persist or restore secrets',async()=>{
    const disk=browser();
    const entries=rememberHosting([],'s3',input());
    entries[0].values.password='injected-password';
    entries[0].values.secretAccessKey='injected-secret';
    entries[0].values.sessionToken='injected-session';
    await saveHostingHistory('project-a',entries);
    const raw=[...disk.values()].join('');
    for(const value of ['private-password','private-secret','private-session','injected-password','injected-secret','injected-session'])assert(!raw.includes(value));
    assert(!raw.includes('secretAccessKey'));assert(!raw.includes('sessionToken'));assert(!raw.includes('password'));
    const reopened=await loadHostingHistory('project-a');
    assert.equal(reopened[0].values.accessKeyId,'public-id');
    const restored=restoreHosting(reopened[0]);
    assert.equal(restored.password,'');assert.equal(restored.secretAccessKey,'');assert.equal(restored.sessionToken,'');
    assert.deepEqual(await loadHostingHistory('project-b'),[]);
});

test('saved URLs strip credentials and query tokens, and each hosting method keeps only its own fields',()=>{
    const s3=record({endpoint:'https://user:secret@oss.example.test/path?token=private#fragment'});
    assert.equal(s3.values.endpoint,'https://oss.example.test/path');
    assert.equal('username' in s3.values,false);assert.equal('baseUrl' in s3.values,false);
    const webdav=rememberHosting([],'webdav',input({baseUrl:'https://user:pass@dav.example.test/pack/?token=private',username:'owner'}),1)[0];
    assert.equal(webdav.values.baseUrl,'https://dav.example.test/pack/');
    assert.equal(webdav.values.username,'owner');assert.equal('accessKeyId' in webdav.values,false);
    assert.deepEqual(rememberHosting([],'export',input(),1)[0].values,{outputDirectory:'D:/exports'});
});

test('requests send credentials only to their corresponding upload method and never to a plain export',()=>{
    const values=input({baseUrl:'https://dav.example.test',username:'owner'});
    assert.deepEqual(hostingRequest('export',values),{outputDirectory:'D:/exports',exportFirst:true});
    const webdav=hostingRequest('webdav',values),s3=hostingRequest('s3',values);
    assert.equal(webdav.password,'private-password');assert.equal('secretAccessKey' in webdav,false);assert.equal('sessionToken' in webdav,false);
    assert.equal(s3.secretAccessKey,'private-secret');assert.equal('password' in s3,false);assert.equal('username' in s3,false);
});

test('history de-duplicates after native omission of empty fields and bounds the most recent entries',()=>{
    let entries=rememberHosting([],'s3',input({prefix:''}),1);
    const id=entries[0].id;
    entries=parseHostingEntries(JSON.parse(JSON.stringify(entries)));
    entries=rememberHosting(entries,'s3',input({prefix:''}),2);
    assert.equal(entries.length,1);assert.equal(entries[0].id,id);assert.equal(entries[0].savedAt,2);
    for(let index=0;index<20;index++)entries=rememberHosting(entries,'s3',input({bucket:'pack-'+index}),index+3);
    assert.equal(entries.length,10);assert.equal(entries[0].values.bucket,'pack-19');
});

test('desktop persistence uses the native store and never falls back to volatile browser storage',async()=>{
    browser();const calls=[];let saved;
    localStorage.getItem=()=>{throw new Error('desktop must not use browser storage');};
    localStorage.setItem=()=>{throw new Error('desktop must not use browser storage');};
    window.__DFS_DESKTOP_META__={workspace:true};window.__DFS_DESKTOP_TOKEN__='connection-token';
    window.__TAURI_INTERNALS__={invoke:async(command,args)=>{
        calls.push({command,args});
        if(command==='save_hosting_history'){saved=args.entries;return;}
        return saved||[];
    }};
    await saveHostingHistory('project-a',rememberHosting([],'s3',input()));
    assert.equal((await loadHostingHistory('project-a'))[0].values.accessKeyId,'public-id');
    assert.equal(calls[0].command,'save_hosting_history');assert.equal(calls[0].args.desktopToken,'connection-token');
    assert(!JSON.stringify(saved).includes('private-secret'));
    window.__TAURI_INTERNALS__.invoke=async()=>{throw new Error('native disk error');};
    await assert.rejects(saveHostingHistory('project-a',[]),/native disk error/);
});

test('corrupt browser history remains intact rather than being silently replaced during reading',async()=>{
    const data=browser();data.set('dfs-hosting-history-v1:project-a','invalid json');
    await assert.rejects(loadHostingHistory('project-a'),/原记录已保留/);
    assert.equal(data.get('dfs-hosting-history-v1:project-a'),'invalid json');
});

function state(persistence){
    const owner=ref('a'),form=reactive(emptyHosting()),method=ref('export'),scope=effectScope();
    const history=scope.run(()=>useHostingHistory(owner,form,method,persistence));
    return {owner,form,method,scope,history};
}
test('late A/B/A loads cannot overwrite the active project, and switching projects clears in-memory secrets',async()=>{
    const pending=[];
    const s=state({load:owner=>new Promise(resolve=>pending.push({owner,resolve})),save:async()=>{}});
    s.form.secretAccessKey='a-secret';s.owner.value='b';
    assert.equal(s.form.secretAccessKey,'');s.owner.value='a';
    pending[2].resolve([record({bucket:'current-a'})]);await tick();
    pending[0].resolve([record({bucket:'stale-a'})]);pending[1].resolve([record({bucket:'stale-b'})]);await tick();
    assert.equal(s.form.bucket,'current-a');assert.equal(s.form.secretAccessKey,'');s.scope.stop();
});

test('late history does not replace values already typed by the owner',async()=>{
    let finish;const s=state({load:()=>new Promise(resolve=>{finish=resolve;}),save:async()=>{}});
    s.form.bucket='new-input';s.history.touch();
    finish([record({bucket:'old-history'})]);await tick();
    assert.equal(s.form.bucket,'new-input');assert.equal(s.history.entries.value.length,1);s.scope.stop();
});

test('failed or stale saves never announce success or attach a record to a later project visit',async()=>{
    let fail=false,finish;const writes=[];
    const s=state({load:async()=>[],save:async(owner,entries)=>{writes.push({owner,entries});if(fail)throw new Error('disk full');await new Promise(resolve=>{finish=resolve;});}});
    await tick();Object.assign(s.form,input());s.method.value='s3';fail=true;
    assert.equal(await s.history.remember(),false);assert.equal(s.history.entries.value.length,0);assert.match(s.history.error.value,/disk full/);
    fail=false;const pending=s.history.remember();await tick();s.owner.value='b';s.owner.value='a';await tick();
    finish();assert.equal(await pending,false);assert.equal(writes.at(-1).owner,'a');assert.equal(s.history.entries.value.length,0);s.scope.stop();
});

test('deleting a record persists the change while retaining the other records',async()=>{
    let stored=[record({bucket:'one'}),record({bucket:'two'})];
    const s=state({load:async()=>structuredClone(stored),save:async(_owner,entries)=>{stored=structuredClone(entries);}});
    await tick();const removed=s.history.selected.value;await s.history.forget();
    assert.equal(stored.length,1);assert(!stored.some(entry=>entry.id===removed));
    assert.equal(s.history.selected.value,'');s.scope.stop();
});
