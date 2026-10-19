import fs from 'node:fs';
import path from 'node:path';
import {repo,sourceIdentity,verifyFrontend} from './build-identity.mjs';
const target=process.argv[2];
if(target==='admin-desktop')verifyFrontend('admin-desktop','dist');
else if(target==='player-ui')verifyFrontend('player-ui','dist');
else {
 if(!['management-cli','player-app','bootstrap-agent'].includes(target))throw new Error('Unexpected Maven artifact');
 if(target==='management-cli'){verifyFrontend('player-ui','dist');verifyFrontend('admin-desktop','web-dist');}
 const directory=path.join(repo,target,'target/generated-resources/META-INF/dreamingfish/build');
 fs.mkdirSync(directory,{recursive:true});
 const admin=JSON.parse(fs.readFileSync(path.join(repo,'admin-desktop/package.json'))).version;
 const player=JSON.parse(fs.readFileSync(path.join(repo,'player-ui/package.json'))).version;
 fs.writeFileSync(path.join(directory,`${target}.json`),JSON.stringify({schemaVersion:1,component:target,buildId:sourceIdentity(),versions:{admin,player,bootstrap:'0.1.2'}},null,2)+'\n');
}
console.log(`Build resources verified: ${target}`);
