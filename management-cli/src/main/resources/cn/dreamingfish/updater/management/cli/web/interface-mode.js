"use strict";
(() => {
  const key="dfs-admin-interface";
  const available=document.documentElement.dataset.workbenchAvailable==="true";
  const explicitLegacy=location.pathname.startsWith("/legacy");
  let preferred="workbench";
  try { if(explicitLegacy)localStorage.setItem(key,"legacy");preferred=localStorage.getItem(key)||"workbench"; } catch (_) {}
  try { document.documentElement.dataset.theme=localStorage.getItem("dfs-admin-theme")==="light"?"light":"dark"; } catch (_) {}
  const views={dashboard:["files","files"],content:["files","files"],publish:["changes","changes"],project:["settings","project"],personalization:["appearance","branding"],player:["delivery","programs"],distribution:["delivery","hosting"],instance:["delivery","deployment"],settings:["settings","service"]};
  const destination=(project,view)=>{const params=new URLSearchParams(location.search),route=views[view]||views.content;params.delete("interface");if(project)params.set("project",project);params.set("view",route[0]);params.set("section",route[1]);return "/workbench/?"+params;};
  if(available&&!explicitLegacy&&preferred!=="legacy") { location.replace(destination(new URLSearchParams(location.search).get("project"),new URLSearchParams(location.search).get("view")));return; }
  window.AdminInterface={landing:()=>new URLSearchParams(location.search)};
  document.addEventListener("DOMContentLoaded",()=>{
    const button=document.getElementById("use-new-interface");if(!button)return;button.hidden=!available;
    let dirty=false;document.addEventListener("input",e=>{if(e.target.closest("form"))dirty=true;});document.addEventListener("submit",()=>{dirty=false;});
    button.addEventListener("click",()=>{
      if(typeof app!=="undefined"&&(app.busy||app.transferBusy||app.transferTasks.some(task=>!['COMPLETED','DONE','done','SKIPPED'].includes(task.status||task.state)))) {if(typeof toast==="function")toast("请先完成或移出当前文件任务，再切换界面",true);return;}
      if(dirty&&!confirm("有尚未保存的内容，切换界面并放弃这些修改？"))return;
      try{localStorage.setItem(key,"workbench");}catch(_){}
      const project=document.getElementById("project-select")?.value,view=document.querySelector(".view.active")?.id?.replace("view-","")||"content";
      location.assign(destination(project,view));
    });
  });
})();
