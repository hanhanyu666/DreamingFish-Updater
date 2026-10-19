import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..');
const source=fs.readFileSync(path.join(root,'management-core/src/main/java/cn/dreamingfish/updater/management/ApplicationTasks.java'),'utf8');
const states=source.match(/public enum State\s*\{([^}]+)\}/)[1].split(',').map(s=>s.trim());
const output=`// Generated from ApplicationTasks.State by packaging/update-task-contract.mjs.\nexport const serverTaskStates=${JSON.stringify(states)} as const;\nexport type ServerTaskState=typeof serverTaskStates[number];\n`;
const target=path.join(root,'admin-desktop/src/workbench/server-task-states.ts');
if(process.argv.includes('--check')){if(!fs.existsSync(target)||fs.readFileSync(target,'utf8')!==output)throw new Error('Task state contract is stale; run node packaging/update-task-contract.mjs');}
else fs.writeFileSync(target,output);
