const defaultSections:Record<string,string>={files:'files',changes:'changes',delivery:'deployment',appearance:'branding',settings:'project'};
const allowedSections:Record<string,string[]>={changes:['changes','releases','operations','problems'],delivery:['deployment','programs','hosting'],appearance:['branding','colors','pages','music'],settings:['project','service','account']};
export function interfaceLanding(search:string){
 const params=new URLSearchParams(search),requested=params.get('view')||'files';
 const view=Object.hasOwn(defaultSections,requested)?requested:'files';
 const section=params.get('section')||defaultSections[view];
 return {projectId:params.get('project')||'',view,section:allowedSections[view]?.includes(section)?section:defaultSections[view]};
}
