import test from 'node:test';
import assert from 'node:assert/strict';
import {packRootPreview,relativeRootForLevels,defaultLayout,parseLayout} from '../src/workbench/pack-root-preview.ts';

test('three parent directories highlight the pack while keeping the instance origin visible',()=>{
 const preview=packRootPreview('../../..');
 assert.equal(preview.rootName,'整合包');
 assert.deepEqual(preview.rows.filter(row=>row.selected).map(row=>row.id),['up-3']);
 assert.equal(preview.rows.find(row=>row.origin).levels,0);
 assert(preview.rows.find(row=>row.id==='mods').depth>preview.rows.find(row=>row.origin).depth);
});

test('each supported ancestor selects exactly the directory described by the submitted path',()=>{
 for(let levels=1;levels<=8;levels++){
  const preview=packRootPreview(relativeRootForLevels(levels));
  const selected=preview.rows.filter(row=>row.selected);
  assert.equal(selected.length,1);assert.equal(selected[0].levels,levels);
  assert.equal(preview.rows.find(row=>row.origin).depth-selected[0].depth,levels);
  assert.equal(preview.relativeRoot.split('/').length,levels);
 }
 assert.equal(packRootPreview('..').rootName,'versions');
 assert.equal(packRootPreview('../..').rootName,'.minecraft');
});

test('invalid paths never display a selected root or an acceptable submission',()=>{
 for(const value of ['','.','./','../../mods','C:\\MC','/root','../other','..//..',Array(9).fill('..').join('/')]){
  const preview=packRootPreview(value);
  assert.equal(preview.levels,null,value);assert.equal(preview.relativeRoot,null,value);
  assert(!preview.rows.some(row=>row.selected),value);
 }
 for(const value of [0,9,1.5,NaN])assert.throws(()=>relativeRootForLevels(value));
});

test('Windows separators and trailing slashes produce the canonical path sent to the server',()=>{
 assert.equal(packRootPreview(' ..\\..\\..\\ ').relativeRoot,'../../..');
});

test('the default folder layout mirrors the version-isolated example and the server default',()=>{
 assert.deepEqual(defaultLayout(1),['*']);
 assert.deepEqual(defaultLayout(3),['.minecraft','versions','*']);
 assert.deepEqual(defaultLayout(5),['*','*','.minecraft','versions','*']);
 for(let levels=1;levels<=8;levels++)assert.equal(defaultLayout(levels).length,levels);
});

test('a custom layout must name one folder per level and never escape it',()=>{
 assert.deepEqual(parseLayout(' .minecraft\\versions/* ',3),['.minecraft','versions','*']);
 assert.equal(parseLayout('.minecraft/versions',3),null);
 for(const value of ['../versions/*','.minecraft/ver*ons/*','C:/versions/*','.minecraft//*'])assert.equal(parseLayout(value,3),null,value);
 assert.equal(parseLayout('*',null),null);
});
