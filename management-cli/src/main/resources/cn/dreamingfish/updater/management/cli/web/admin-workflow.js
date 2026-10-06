"use strict";

// A small transport boundary: the same controllers can be hosted in a browser or a desktop webview.
const AdminTransport = Object.freeze({
  async request(path, options = {}, token = "") {
    const headers = new Headers(options.headers || {}); headers.set("Accept", "application/json");
    if (options.body !== undefined) headers.set("Content-Type", "application/json");
    if (token) headers.set("X-DFS-Token", token);
    const response = await fetch(path, {method:options.method || "GET",headers,body:options.body === undefined ? undefined : JSON.stringify(options.body)});
    const data = (response.headers.get("Content-Type") || "").includes("application/json") ? await response.json() : null;
    if (!response.ok) { const error = new Error(data?.message || `请求失败：HTTP ${response.status}`); error.status = response.status; error.code = data?.error; throw error; }
    return data;
  },
  upload(path, file, token, progress, active = null) {
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest(); active?.add(request);
      const finish = (callback, value) => {active?.delete(request); callback(value);};
      request.open("PUT", path); request.setRequestHeader("Accept","application/json"); request.setRequestHeader("Content-Type","application/octet-stream");
      if (token) request.setRequestHeader("X-DFS-Token",token);
      request.upload.addEventListener("progress",event => {if(event.lengthComputable) progress?.(event.loaded);});
      request.addEventListener("load",()=>{
        let data; try {data=JSON.parse(request.responseText);} catch (_) {data=null;}
        if(request.status>=200 && request.status<300) finish(resolve,data);
        else {const error=new Error(data?.message || `上传失败：HTTP ${request.status}`); error.status=request.status; finish(reject,error);}
      });
      request.addEventListener("error",()=>finish(reject,new Error("传输连接中断，可以重试这一项")));
      request.addEventListener("abort",()=>finish(reject,new Error("传输已暂停")));
      request.send(file);
    });
  }
});

function confirmAction(...args) { return ask(...args); }

function renderWorkflow() {
  const project=app.project, preview=project?.preview;
  const changes=(preview?.changes || []).filter(change=>change.kind!=="POLICY_CHANGED").length + (preview?.policyChanges || []).length;
  const status=!project ? "先创建整合包项目" : app.transferBusy ? "文件任务进行中" : app.packageBusy ? "玩家端包检查中"
    : project.previewStale ? "已保存 · 需要重新检查" : !preview ? "尚未检查整合包" : changes ? `已保存 · ${changes} 项待发布` : "与当前发布一致";
  byId("workspace-status").textContent=status;
  byId("workspace-status").classList.toggle("pending",Boolean(changes || project?.previewStale));
  byId("transfer-running").hidden=app.transferProjectId!==project?.id || !app.transferBusy;
  byId("transfer-running-label").textContent=app.transferBusy ? "文件任务正在进行，已成功导入的文件会保留。" : "";
  const dashboard=byId("dashboard-next");
  const heading=document.createElement("h2"); heading.textContent="下一步";
  const text=document.createElement("p"); text.textContent=!project ? "新建项目，选择要管理的文件目录。" : project.previewStale || !preview ? "检查已保存的文件和维护设置，再发布给玩家。"
    : changes ? `有 ${changes} 项待发布改动。先查看文件和规则变化，再发布新版本。` : "当前没有待发布改动，可以继续整理文件或下载部署包。";
  const actions=document.createElement("div"); actions.className="button-row";
  if(project) {
    actions.append(workspaceButton(changes || project.previewStale ? "查看改动并发布" : "整理文件",async()=>{
      if(changes || project.previewStale || !preview) showView("publish");
      else {if(!app.sourceFiles) await loadSourceFiles(); showView("content");}
    },"primary-button"), workspaceButton("发放部署包",()=>showView("instance"),"secondary-button"));
  } else actions.append(workspaceButton("新建项目",()=>byId("open-create-project").click(),"primary-button"));
  dashboard.replaceChildren(heading,text,actions);
  const submit=byId("publish-submit");
  if(app.transferBusy || app.packageBusy) submit.disabled=true;
  else submit.disabled=!project || !preview || Boolean(project.previewStale);
  const minimum=byId("publish-form").elements.minimumPlayerVersion;
  if (!minimum.dataset.edited) minimum.value=preview?.policyPlayerVersion || "0.2.0";
  byId("project-select").disabled=Boolean(app.transferBusy || app.packageBusy) || !(app.state?.projects || []).length;
  const forms=[byId("deployment-form"),byId("instance-form")];
  for(const form of forms) {
    const select=form.elements.platform;
    if(project?.platform && [...select.options].some(option=>option.value===project.platform)) select.value=project.platform;
  }
  const table=byId("preview-table");
  if(table?.closest(".table-wrap")) table.closest(".table-wrap").classList.toggle("empty-preview", !(preview?.changes || []).filter(change=>change.kind!=="POLICY_CHANGED").length);
}

function selectPersonalPane(name) {
  app.personalTab=name;
  document.querySelectorAll("[data-personal-tab]").forEach(tab=>tab.setAttribute("aria-selected",String(tab.dataset.personalTab===name)));
  document.querySelectorAll("[data-personal-tab]").forEach(tab=>{tab.tabIndex=tab.dataset.personalTab===name ? 0 : -1;});
  document.querySelectorAll("[data-personal-pane]").forEach(pane=>{
    pane.hidden=pane.dataset.personalPane!==name;
    let lifecycle=pane.querySelector(".lifecycle-label");
    if(!lifecycle) {lifecycle=document.createElement("p"); lifecycle.className="lifecycle-label"; pane.prepend(lifecycle);}
    lifecycle.textContent=["brand","pages"].includes(pane.dataset.personalPane)
      ? "文字与页面：保存后，玩家下次启动获取。" : pane.dataset.personalPane==="music"
        ? "音乐文件：导入或移除后，还需检查并发布整合包。" : "配色保存后获取；背景图片导入或移除后，还需检查并发布。";
  });
  byId("view-personalization").classList.remove("preview-only");
  byId("toggle-player-preview").setAttribute("aria-pressed","false");
}

function selectHostingMethod() {
  const selected=byId("hosting-method").value;
  for(const [name,id] of Object.entries({export:"distribution-form",webdav:"webdav-upload-form",s3:"s3-upload-form"})) byId(id).hidden=name!==selected;
}

function bindWorkspaceWorkflow() {
  const clearPackage=workspaceButton("清除暂存包",async()=>{
    if(app.packagePlan) try {await api(`/api/projects/${encodeURIComponent(app.packageProjectId)}/program-package?id=${encodeURIComponent(app.packagePlan.id)}`,{method:"DELETE"});} catch(error) {showErrorDialog(error.message); return;}
    app.packagePlan=null; byId("program-package-input").value=""; byId("program-package-result").textContent="";
    byId("check-program-package").disabled=true; byId("publish-program-package").disabled=true; clearPackage.hidden=true;
  },"secondary-button");
  clearPackage.hidden=true; byId("check-program-package").after(clearPackage);
  document.querySelectorAll('[role="tablist"]').forEach((list,group)=>{
    const tabs=[...list.querySelectorAll('[role="tab"]')];
    tabs.forEach((tab,index)=>{
      tab.id=`workspace-tab-${group}-${index}`;
      const pane=tab.dataset.personalTab ? document.querySelector(`[data-personal-pane="${tab.dataset.personalTab}"]`) : document.querySelector(`[data-source-pane="${tab.dataset.sourceTab}"]`);
      if(pane) {pane.id=`workspace-pane-${group}-${index}`; pane.setAttribute("role","tabpanel"); pane.setAttribute("aria-labelledby",tab.id); tab.setAttribute("aria-controls",pane.id);}
    });
    const sync=()=>tabs.forEach(tab=>{tab.tabIndex=tab.getAttribute("aria-selected")==="true" ? 0 : -1;});
    list.addEventListener("click",sync); sync();
    tabs.forEach((tab,index)=>tab.addEventListener("keydown",event=>{
      if(!["ArrowLeft","ArrowRight","Home","End"].includes(event.key)) return;
      event.preventDefault();
      const next=event.key==="Home" ? 0 : event.key==="End" ? tabs.length-1 : (index+(event.key==="ArrowRight" ? 1 : -1)+tabs.length)%tabs.length;
      tabs[next].click(); tabs[next].focus(); sync();
    }));
  });
  byId("publish-form").elements.minimumPlayerVersion.addEventListener("input",event=>{event.target.dataset.edited="true";});
  byId("personalization-form").addEventListener("invalid",event=>{
    const pane=event.target.closest("[data-personal-pane]"); if(pane) selectPersonalPane(pane.dataset.personalPane);
  },true);
  byId("personalization-form").addEventListener("input",()=>{app.personalDirty=true; app.personalDirtyProjectId=app.project?.id;});
  byId("personalization-form").addEventListener("change",()=>{app.personalDirty=true; app.personalDirtyProjectId=app.project?.id;});
  byId("add-player-page").addEventListener("click",()=>{app.personalDirty=true; app.personalDirtyProjectId=app.project?.id;});
  document.querySelectorAll(".nav-button").forEach(button=>{button.title=button.textContent.trim();});
  document.querySelectorAll("[data-personal-tab]").forEach(button=>button.addEventListener("click",()=>selectPersonalPane(button.dataset.personalTab)));
  selectPersonalPane(app.personalTab);
  byId("toggle-player-preview").addEventListener("click",()=>{
    const preview=byId("view-personalization").classList.toggle("preview-only");
    byId("toggle-player-preview").setAttribute("aria-pressed",String(preview));
    byId("toggle-player-preview").textContent=preview ? "返回编辑" : "查看预览";
    requestAnimationFrame(resizePlayerPreview);
  });
  byId("hosting-method").addEventListener("change",selectHostingMethod); selectHostingMethod();
  for(const form of [byId("distribution-form"),byId("webdav-upload-form"),byId("s3-upload-form")]) {
    form.elements.outputDirectory.addEventListener("input",()=>{
      for(const other of [byId("distribution-form"),byId("webdav-upload-form"),byId("s3-upload-form")]) if(other!==form) other.elements.outputDirectory.value=form.elements.outputDirectory.value;
    });
  }
  byId("check-project-address").addEventListener("click",async()=>{
    if(!app.project) return;
    const button=byId("check-project-address"),output=byId("address-check-result"); button.disabled=true; output.textContent="管理服务器正在检测更新接口…";
    try {
      const result=await api(`/api/projects/${encodeURIComponent(app.project.id)}/check-address`,{method:"POST",body:{url:byId("project-form").elements.publicBaseUrl.value}});
      output.textContent=`${result.message} · ${result.milliseconds}ms。检测位置是管理服务器；玩家的公网连通性需要另行确认。`;
      output.dataset.state=result.ok ? "ok" : "error";
    } catch(error) {output.textContent=error.message; output.dataset.state="error";}
    finally {button.disabled=false;}
  });
  byId("program-package-input").addEventListener("change",async()=>{
    if(app.packagePlan && app.packageProjectId===app.project?.id) try {await api(`/api/projects/${encodeURIComponent(app.packageProjectId)}/program-package?id=${encodeURIComponent(app.packagePlan.id)}`,{method:"DELETE"});} catch(_) {}
    app.packagePlan=null; byId("check-program-package").disabled=!byId("program-package-input").files.length;
    clearPackage.hidden=true;
    byId("publish-program-package").disabled=true; byId("program-package-result").textContent="";
  });
  byId("check-program-package").addEventListener("click",async()=>{
    const file=byId("program-package-input").files[0]; if(!file || !app.project || app.packageBusy) return;
    app.packageProjectId=app.project.id; app.packageBusy=true; renderWorkflow();
    byId("check-program-package").disabled=true; byId("program-package-input").disabled=true;
    byId("program-package-progress").hidden=false; byId("program-package-result").textContent="正在上传并检查发行包…";
    try {
      app.packagePlan=await AdminTransport.upload(`/api/projects/${encodeURIComponent(app.packageProjectId)}/program-package?fileName=${encodeURIComponent(file.name)}`,file,app.token,
        loaded=>{byId("program-package-progress").value=file.size ? Math.round(100*loaded/file.size) : 100;});
      const plan=app.packagePlan;
      byId("program-package-result").textContent=`识别成功：${plan.version} · ${plan.platform} · ${formatBytes(plan.size)}\n${plan.publishable===false ? plan.problem : "程序目录已校验，确认后签名发布；当前整合包版本保持不变。"}`;
      byId("publish-program-package").disabled=plan.publishable===false;
    } catch(error) {byId("program-package-result").textContent=error.message; app.packagePlan=null;}
    finally {app.packageBusy=false; byId("program-package-input").disabled=false; byId("check-program-package").disabled=false; byId("program-package-progress").hidden=true; clearPackage.hidden=!app.packagePlan; renderWorkflow();}
  });
  byId("publish-program-package").addEventListener("click",async()=>{
    const plan=app.packagePlan; if(!plan || !app.project || app.packageProjectId!==app.project.id) return;
    if(!await confirmAction("发布玩家端程序",`${plan.version} · ${plan.platform}\n发布后，玩家会获得新的更新器程序。`,"确认发布")) return;
    await runBusy("正在校验并签名玩家端程序",async()=>{
      const result=await api(`/api/projects/${encodeURIComponent(app.project.id)}/program-package`,{method:"POST",body:{id:plan.id,minimumBootstrapVersion:byId("package-agent-version").value}});
      const projectId=app.project.id;
      try {await api(`/api/projects/${encodeURIComponent(projectId)}/program-package?id=${encodeURIComponent(plan.id)}`,{method:"DELETE"});} catch(_) { /* Publication succeeded; temporary cleanup must not report it as failed. */ }
      app.packagePlan=null; byId("publish-program-package").disabled=true;
      await loadProject(projectId,result.platform); toast(`玩家端 ${result.version} 已发布`);
    });
  });
}

initialize();
