import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
export const repo=path.resolve(import.meta.dirname,'..');
export const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
export function files(directory){
 if(!fs.existsSync(directory))return [];
 const stat=fs.lstatSync(directory);if(stat.isSymbolicLink())throw new Error(`Build input/output is a link: ${directory}`);
 if(stat.isFile())return [directory];
 return fs.readdirSync(directory,{withFileTypes:true}).flatMap(entry=>files(path.join(directory,entry.name))).sort();
}
export function productionFiles(){
 const roots=['pom.xml','LICENSE'];
 for(const module of ['protocol','update-engine','management-core','management-cli','bootstrap-agent','player-app'])roots.push(`${module}/pom.xml`,`${module}/src/main`);
 for(const app of ['player-ui','admin-desktop']){
  roots.push(`${app}/src`,`${app}/public`,`${app}/package.json`,`${app}/package-lock.json`,`${app}/tsconfig.json`,`${app}/vite.config.ts`,`${app}/index.html`);
  roots.push(`${app}/src-tauri/src`,`${app}/src-tauri/capabilities`,`${app}/src-tauri/icons`,`${app}/src-tauri/build.rs`,`${app}/src-tauri/Cargo.toml`,`${app}/src-tauri/Cargo.lock`,`${app}/src-tauri/tauri.conf.json`);
 }
 roots.push('admin-desktop/vite.web.config.ts','admin-desktop/workspace.html');
 return [...new Set(roots.flatMap(root=>files(path.join(repo,root))))].filter(file=>!file.includes(`${path.sep}__tests__${path.sep}`)&&!file.endsWith('.test.ts')).sort();
}
export function sourceIdentity(){return sha(productionFiles().map(file=>{
 const bytes=fs.readFileSync(file);
 // Tauri rewrites Cargo.toml line endings before its frontend build hook.
 // That formatting-only rewrite must not split one build into two identities.
 const input=path.basename(file)==='Cargo.toml'?Buffer.from(bytes.toString('utf8').replaceAll('\r\n','\n')):bytes;
 return `${path.relative(repo,file).replaceAll('\\','/')}\0${sha(input)}\n`;
}).join(''));}
export function outputHashes(directory){return Object.fromEntries(files(directory).filter(file=>path.relative(directory,file)!=='build-info.json').map(file=>[path.relative(directory,file).replaceAll('\\','/'),sha(fs.readFileSync(file))]));}
export function verifyFrontend(app,output){
 const directory=path.join(repo,app,output),marker=path.join(directory,'build-info.json');
 if(!fs.existsSync(marker))throw new Error(`Missing ${app}/${output}/build-info.json; build the frontend before Maven/Cargo packaging.`);
 const info=JSON.parse(fs.readFileSync(marker,'utf8'));
 if(info.schemaVersion!==1||info.buildId!==sourceIdentity())throw new Error(`${app}/${output} is stale for the current source. Rebuild player-ui, admin web and desktop in order.`);
 const actual=outputHashes(directory);
 if(JSON.stringify(actual)!==JSON.stringify(info.outputs))throw new Error(`${app}/${output} has missing, stale or modified assets. Rebuild it.`);
 return info;
}
if(path.resolve(process.argv[1]||'')===fileURLToPath(import.meta.url)||process.argv.includes('--print'))console.log(sourceIdentity());
