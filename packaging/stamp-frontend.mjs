import fs from 'node:fs';
import path from 'node:path';
import {repo,sourceIdentity,outputHashes} from './build-identity.mjs';
const [app,output]=process.argv.slice(2);
if(!['player-ui','admin-desktop'].includes(app)||!['dist','web-dist'].includes(output))throw new Error('Unexpected frontend target');
const directory=path.join(repo,app,output);
const marker={schemaVersion:1,component:`${app}/${output}`,buildId:sourceIdentity(),outputs:outputHashes(directory)};
fs.writeFileSync(path.join(directory,'build-info.json'),JSON.stringify(marker,null,2)+'\n');
console.log(`Stamped ${marker.component}: ${marker.buildId}`);
