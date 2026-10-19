import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const source=fs.readFileSync(new URL('../src/desktop-chrome.js',import.meta.url),'utf8');
class Element {
 constructor(){this.dataset={};this.classList={add(){},toggle(){}};this.children=[];}
 append(...items){this.children.push(...items);}
 prepend(item){this.children.unshift(item);item.parentNode=this;}
 setAttribute(){} removeAttribute(){} addEventListener(){} matches(){return false;}
}
function fixture(rejections,ready='complete'){
 const timers=[],events=new Map(),body=new Element(),root=new Element();
 const document={documentElement:root,body,head:{},readyState:ready,title:'Workspace',getElementById:()=>null,createElement:()=>new Element(),createElementNS:()=>new Element(),querySelectorAll:()=>[],addEventListener:(name,fn)=>events.set('document:'+name,fn)};
 let calls=0;
 const window={__DFS_DESKTOP_META__:{check:true,workspace:true},__TAURI_INTERNALS__:{invoke:async()=>{calls++;if(calls<=rejections)throw new Error('Origin header is not a valid URL');return {maximized:false};}},addEventListener:(name,fn)=>events.set(name,fn)};
 window.parent=window;
 const context={window,document,MutationObserver:class{observe(){}},setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout(){},console};
 vm.runInNewContext(source,context);
 return {document,timers,events,body,get calls(){return calls;}};
}
const tick=()=>new Promise(resolve=>setImmediate(resolve));
test('frame status tolerates delayed origin commitment without reporting success early',async()=>{
 const f=fixture(10);
 for(let i=0;i<12;i++){await tick();const next=f.timers.shift();if(next)next();}
 await tick();assert.equal(f.calls,11);assert.equal(f.timers.length,0);
 assert.equal(f.document.title,'Workspace');assert.equal(f.body.children[0].dataset.error,undefined);
});
test('status waits for page load while the document is still interactive',async()=>{
 const f=fixture(0,'interactive');assert.equal(f.calls,0);
 f.events.get('load')();await tick();assert.equal(f.calls,1);
});
test('permanent origin errors have a finite retry budget and remain errors',async()=>{
 const f=fixture(Infinity);
 for(let i=0;i<60;i++){await tick();const next=f.timers.shift();if(next)next();}
 await tick();assert.equal(f.calls,51);assert.equal(f.timers.length,0);
 assert.match(f.document.title,/DFS_FRAME_ERROR:.*Origin/);assert.equal(f.body.children[0].dataset.error,'true');
});
