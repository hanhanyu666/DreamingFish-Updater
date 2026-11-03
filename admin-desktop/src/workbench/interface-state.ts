import {viewSections} from './presentation.ts';
const defaultSections:Record<string,string>={files:'files',changes:'changes',delivery:'deployment',appearance:'branding',settings:'project'};
export function interfaceLanding(search:string){
 const params=new URLSearchParams(search),requested=params.get('view')||'files';
 const view=Object.hasOwn(defaultSections,requested)?requested:'files';
 const section=params.get('section')||defaultSections[view];
 return {projectId:params.get('project')||'',view,section:viewSections[view]?.some(item=>item.id===section)?section:defaultSections[view]};
}
