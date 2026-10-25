import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {newId,move,toLocalInput,fromLocalInput,aiBrief,formatBytes,blockFields,BLOCK_KINDS,SHOWCASE_AI_PROMPT,parseShowcase,applyShowcase,showcaseFromBranding,validateShowcase} from '../src/workbench/showcase.ts';

const example=readFileSync(new URL('../../docs/showcase/example-whalesong.json',import.meta.url),'utf8');

test('new ids follow the showcase id pattern and avoid taken ones',()=>{
 const taken=new Set();
 for(let i=0;i<50;i++){const id=newId('slide',taken);assert.match(id,/^[a-z0-9][a-z0-9-]{0,63}$/);assert(!taken.has(id));taken.add(id);}
});
test('moving an entry keeps every entry and reports where it landed',()=>{
 const list=[{id:'a'},{id:'b'},{id:'c'}];
 assert.equal(move(list,0,1),1);assert.deepEqual(list.map(i=>i.id),['b','a','c']);
 assert.equal(move(list,0,-1),0);assert.deepEqual(list.map(i=>i.id),['b','a','c']);
});
test('event times round-trip through the datetime-local editor with an offset',()=>{
 const stored=fromLocalInput('2026-10-24T20:00');
 assert.match(stored,/^2026-10-24T20:00:00[+-]\d{2}:\d{2}$/);
 assert.equal(toLocalInput(stored),'2026-10-24T20:00');
 assert.equal(fromLocalInput(''),'');assert.equal(toLocalInput('下周六'),'');
 assert.deepEqual(validateShowcase({format:'dreamingfish-showcase',version:1,events:[{id:'e',title:'开赛',startsAt:stored}]}),[]);
});
test('every block kind has an editor layout',()=>{
 for(const kind of BLOCK_KINDS)assert(blockFields[kind],kind);
});
test('the AI brief carries the rules, the schema and the example',()=>{
 const brief=aiBrief(SHOWCASE_AI_PROMPT,'{"schema":1}',example);
 assert(brief.startsWith(SHOWCASE_AI_PROMPT));assert.match(brief,/showcase\.schema\.json/);assert.match(brief,/鲸歌/);
});
test('importing the example and exporting it again gives the same content',()=>{
 const parsed=parseShowcase(example);assert.deepEqual(parsed.issues,[]);
 const branding=applyShowcase({productName:'旧名字',coverObject:'cover',musicTracks:[{id:'m',title:'海',fileName:'m.mp3'}]},parsed.document);
 assert.equal(branding.coverObject,'cover');assert.equal(branding.musicTracks.length,1);
 const again=showcaseFromBranding(branding);
 assert.deepEqual(again.spotlights,parsed.document.spotlights);assert.deepEqual(again.contentPages,parsed.document.contentPages);
});
test('import errors name the JSON path for the owner',()=>{
 const broken=JSON.parse(example);broken.contentPages[1].blocks[0].kind='HERO';
 assert.deepEqual(parseShowcase(JSON.stringify(broken)).issues,[{path:'contentPages[1].blocks[0].kind',message:'不支持 "HERO"'}]);
});
test('image sizes read naturally',()=>{
 assert.equal(formatBytes(2048),'2 KB');assert.equal(formatBytes(3*1048576),'3.0 MB');
});
