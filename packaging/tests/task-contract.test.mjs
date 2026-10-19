import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

test('task contract accepts checkout line endings but rejects stale states',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'dfs-task-contract-'));
 try{
  const script=path.join(root,'packaging/update-task-contract.mjs');
  const source=path.join(root,'management-core/src/main/java/cn/dreamingfish/updater/management/ApplicationTasks.java');
  const target=path.join(root,'admin-desktop/src/workbench/server-task-states.ts');
  for(const file of [script,source,target])fs.mkdirSync(path.dirname(file),{recursive:true});
  fs.copyFileSync(path.resolve(import.meta.dirname,'../update-task-contract.mjs'),script);
  fs.writeFileSync(source,'public enum State { QUEUED, RUNNING, SUCCEEDED }');
  assert.equal(spawnSync(process.execPath,[script]).status,0);
  const expected=fs.readFileSync(target,'utf8');
  for(const text of [expected,expected.replaceAll('\n','\r\n')]){
   fs.writeFileSync(target,text);
   const check=spawnSync(process.execPath,[script,'--check'],{encoding:'utf8'});
   assert.equal(check.status,0,check.stderr);
  }
  fs.writeFileSync(target,expected.replace('SUCCEEDED','WRONG'));
  assert.notEqual(spawnSync(process.execPath,[script,'--check']).status,0);
 }finally{fs.rmSync(root,{recursive:true,force:true});}
});
