import test from 'node:test';
import assert from 'node:assert/strict';
import {policyPresentation,viewSections} from '../src/workbench/presentation.ts';
import {interfaceLanding} from '../src/workbench/interface-state.ts';

test('actual PRESET DTO renders the file and Chinese before/after values',()=>{
 assert.deepEqual(policyPresentation({kind:'PRESET',subject:'config/keys.txt',previous:'SYNC',current:'REQUIRED'}),{label:'维护方式',subject:'config/keys.txt',detail:'普通同步 → 强制同步'});
});
test('cleanup DTO describes enabling and ending directory cleanup without exposing ON',()=>{
 const enabled=policyPresentation({kind:'CLEANUP_DIRECTORY',subject:'config',previous:null,current:'ON'});
 assert.equal(enabled.subject,'config/');assert.match(enabled.detail,/先备份/);
 assert.match(policyPresentation({kind:'CLEANUP_DIRECTORY',subject:'config',previous:'ON',current:null}).detail,/不再/);
});
test('all policy kinds use Chinese labels and resolve group and problem subjects',()=>{
 const maintenance={optionalGroups:[{id:'graphics',title:'画面优化'}],withdrawals:[{id:'bad-version',reason:'会导致崩溃',items:[{path:'mods/bad.jar'}]}],corrections:[{id:'fix',path:'config/a.json',reason:'修复配置'}]};
 for(const kind of ['PRESET','CLEANUP_DIRECTORY','OPTIONAL_GROUP','OPTIONAL_MEMBERSHIP','WITHDRAWAL','CORRECTION','CORRECTION_DROPPED','FUTURE_KIND']){
  const view=policyPresentation({kind,subject:kind==='WITHDRAWAL'?'bad-version':kind==='CORRECTION'?'fix':'config/a.json',previous:'SYNC',current:'ON'},maintenance);
  assert.doesNotMatch(view.label,/^[A-Z_]+$/);
 }
 assert.match(policyPresentation({kind:'OPTIONAL_MEMBERSHIP',subject:'mods/a.jar',previous:null,current:'graphics'},maintenance).detail,/画面优化/);
 assert.equal(policyPresentation({kind:'WITHDRAWAL',subject:'bad-version',current:'ON'},maintenance).subject,'mods/bad.jar');
});
test('collapsed navigation retains operations and every other section',()=>{
 assert(viewSections.changes.some(v=>v.id==='operations'&&v.label==='操作记录'));
 assert.equal(viewSections.settings.length,3);assert.equal(viewSections.appearance.length,4);
});
test('interface links retain project and view but reject unknown sections',()=>{
 assert.deepEqual(interfaceLanding('?project=test&view=changes&section=operations'),{projectId:'test',view:'changes',section:'operations'});
 assert.equal(interfaceLanding('?view=changes&section=unknown').section,'changes');
 assert.equal(interfaceLanding('?view=__proto__').view,'files');
});
