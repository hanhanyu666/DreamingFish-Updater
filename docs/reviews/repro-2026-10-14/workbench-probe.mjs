import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root = resolve(import.meta.dirname, '../../..');
const require = createRequire(resolve(root, 'player-ui/package.json'));
const {build} = require('esbuild');
globalThis.location = {search: ''};
globalThis.localStorage = {getItem: () => null, setItem: () => {}};
globalThis.window = {};
const bundled = await build({entryPoints: [resolve(root, 'admin-desktop/src/workbench/store.ts')],
    bundle: true, write: false, platform: 'node', format: 'esm'});
const {desk, commitUploads, checkUploads, switchProject} = await import(
    'data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64'));
const ok = value => ({ok: true, status: 200, json: async () => value});

let calls = [];
globalThis.fetch = async (path, options) => {
    calls.push({path, body: JSON.parse(options.body || '{}')});
    return {ok: false, status: 400, json: async () => ({message: '请选择新增、覆盖或替换旧模组'})};
};
desk.projectId = 'demo';
desk.tasks = [{id: 't', name: 'a', path: 'config/a', state: 'review',
    action: 'SKIP', plan: {id: 'stage-1', stamp: 'stamp'}}];
await commitUploads();
assert.equal(calls[0].body.action, 'SKIP');
assert.equal(desk.tasks[0].state, 'error');
console.log('SKIP_UI state=' + desk.tasks[0].state + ' sentAction=' + calls[0].body.action);

calls = [];
let uploaded = 0;
globalThis.XMLHttpRequest = class {
    upload = {};
    open(method, path) { calls.push({method, path}); }
    setRequestHeader() {}
    send() { uploaded++; this.status = 201; this.responseText = JSON.stringify({id: 'new-stage', stamp: 'new'}); this.onload(); }
};
desk.tasks = [{id: 't', name: 'a', path: 'config/a', state: 'error', action: 'ADD',
    file: new Blob(['fixture']), plan: {id: 'already-committed', stamp: 'old'}}];
await checkUploads();
assert.equal(uploaded, 1);
assert.equal(desk.tasks[0].plan.id, 'new-stage');
console.log('RETRY uploads=' + uploaded + ' stage=' + desk.tasks[0].plan.id + ' requests=' + JSON.stringify(calls));

desk.tasks = [];
let finishA;
globalThis.fetch = async path => {
    if (path === '/api/projects/a') return await new Promise(resolve => { finishA = () => resolve(ok({id: 'a', displayName: 'A', releases: []})); });
    if (path === '/api/projects/b') return ok({id: 'b', displayName: 'B', releases: []});
    if (path === '/api/projects/b/files') return ok({files: [{path: 'config/from-b', size: 1}], directories: ['config']});
    throw new Error('Unexpected request: ' + path);
};
const first = switchProject('a');
await switchProject('b');
finishA();
await first;
assert.equal(desk.projectId, 'b');
assert.equal(desk.project.id, 'a');
assert.equal(desk.files[0].path, 'config/from-b');
console.log('PROJECT_RACE selected=' + desk.projectId + ' displayed=' + desk.project.id + ' file=' + desk.files[0].path);
