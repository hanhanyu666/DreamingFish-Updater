import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';

test('both installed Vue renderers reject carriage returns in dynamic attribute names',()=>{
 for(const app of ['admin-desktop','player-ui']){
  const require=createRequire(resolve(import.meta.dirname,`../../${app}/package.json`));
  const {ssrRenderAttrs}=require('@vue/server-renderer');
  const original=console.error;console.error=()=>{};
  try{assert.equal(ssrRenderAttrs({['x\rdata-security-probe']:'value'}),'');}finally{console.error=original;}
 }
});
test('patched generator and source-map guards terminate in bounded helper processes',()=>{
 for(const app of ['admin-desktop','player-ui']){
  const cwd=resolve(import.meta.dirname,`../../${app}`);
  const options={cwd,timeout:2000,windowsHide:true,encoding:'utf8'};
  assert.equal(execFileSync(process.execPath,['-e',"process.stdout.write(require('nanoid').customAlphabet('abc',0)())"],options),'');
  const script="const {SourceMapConsumer,SourceNode}=require('source-map-js');try{const c=new SourceMapConsumer({version:3,sections:[{offset:{line:Number.MAX_SAFE_INTEGER,column:0},map:{version:3,sources:['a'],names:[],mappings:'AAAA'}}]});SourceNode.fromStringWithSourceMap('x',c);process.exit(2)}catch(e){process.stdout.write('guarded')}";
  assert.equal(execFileSync(process.execPath,['-e',script],options),'guarded');
 }
});
