export type Profile={id:string;name:string;kind:'local'|'remote'|'ssh';home?:string;url?:string;target?:string;remotePort?:number};

// Tauri's tagged Connection enum rejects fields from a different connection kind.
export function profilePayload(profile:Profile){
  const base={id:profile.id,name:profile.name};
  if(profile.kind==='local')return {...base,kind:'local' as const,home:profile.home};
  if(profile.kind==='ssh')return {...base,kind:'ssh' as const,target:profile.target,remotePort:profile.remotePort};
  return {...base,kind:'remote' as const,url:profile.url};
}
