import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
const repo=path.resolve(import.meta.dirname,'../..');
test('production resource guards reject absent, modified and stale assets',()=>{
 const root=path.join(repo,'target','resource-guard-'+randomUUID());
 fs.mkdirSync(path.join(root,'packaging'),{recursive:true});
 for(const file of ['build-identity.mjs','stamp-frontend.mjs','check-build-resources.mjs'])fs.copyFileSync(path.join(repo,'packaging',file),path.join(root,'packaging',file));
 for(const app of ['admin-desktop','player-ui']){fs.mkdirSync(path.join(root,app,'src'),{recursive:true});fs.writeFileSync(path.join(root,app,'package.json'),'{"version":"0.0.0-test"}');}
 const run=(file,...args)=>spawnSync(process.execPath,[path.join(root,'packaging',file),...args],{cwd:root,encoding:'utf8'});
 const manifest=path.join(root,'admin-desktop','src-tauri','Cargo.toml');fs.mkdirSync(path.dirname(manifest),{recursive:true});
 fs.writeFileSync(manifest,'[package]\r\nname="fixture"\r\nversion="0.0.0"\r\n');const crlfId=run('build-identity.mjs','--print').stdout.trim();
 fs.writeFileSync(manifest,fs.readFileSync(manifest,'utf8').replaceAll('\r\n','\n'));assert.equal(run('build-identity.mjs','--print').stdout.trim(),crlfId,'Tauri line ending normalization must not change source identity');
 assert.notEqual(run('check-build-resources.mjs','management-cli').status,0);
 for(const [app,output] of [['player-ui','dist'],['admin-desktop','web-dist'],['admin-desktop','dist']]){
  fs.mkdirSync(path.join(root,app,output),{recursive:true});fs.writeFileSync(path.join(root,app,output,'index.html'),'<main>Fixture</main>');
  assert.equal(run('stamp-frontend.mjs',app,output).status,0);
 }
 assert.equal(run('check-build-resources.mjs','management-cli').status,0);
 assert.equal(run('check-build-resources.mjs','admin-desktop').status,0);
 const asset=path.join(root,'admin-desktop','dist','index.html');fs.appendFileSync(asset,'changed');
 assert.match(run('check-build-resources.mjs','admin-desktop').stderr,/missing, stale or modified assets/);
 assert.equal(run('stamp-frontend.mjs','admin-desktop','dist').status,0);
 fs.writeFileSync(path.join(root,'player-ui','src','changed.ts'),'export const fixture=1;');
 assert.match(run('check-build-resources.mjs','management-cli').stderr,/stale for the current source/);
 assert.match(run('check-build-resources.mjs','admin-desktop').stderr,/stale for the current source/);
 // Retain this small synthetic fixture as evidence; never remove arbitrary outputs.
});
