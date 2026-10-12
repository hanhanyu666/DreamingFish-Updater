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
  const project=app.project, preview=project?.preview, releases=project?.releases || [];
  const changes=(preview?.changes || []).filter(change=>change.kind!=="POLICY_CHANGED").length + (preview?.policyChanges || []).length;
  const state=!project ? "none" : app.transferBusy || app.packageBusy ? "busy" : project.previewStale ? "stale" : !preview ? "unchecked" : changes ? "pending" : "ok";
  const status=byId("workspace-status");
  status.textContent={none:"先创建整合包项目",busy:app.transferBusy ? "文件任务进行中" : "玩家端包检查中",stale:"已保存 · 需要重新检查",
    unchecked:"尚未检查整合包",pending:`已保存 · ${changes} 项待发布`,ok:"与当前发布一致"}[state];
  status.dataset.state=state;
  status.title=project ? "前往检查并发布" : "新建整合包项目";
  status.hidden=false;
  status.classList.toggle("pending",["pending","stale"].includes(state));
  byId("transfer-running").hidden=app.transferProjectId!==project?.id || !app.transferBusy;
  byId("transfer-running-label").textContent=app.transferBusy ? "文件任务正在进行，已成功导入的文件会保留。" : "";
  renderNextSteps(state,changes,releases[0]);
  const submit=byId("publish-submit");
  if(app.transferBusy || app.packageBusy) submit.disabled=true;
  else submit.disabled=!project || !preview || Boolean(project.previewStale);
  byId("publish-footer-hint").textContent={none:"",busy:"文件任务进行中，完成后才能发布。",unchecked:"进入本页会自动检查；检查完成后即可发布。",
    stale:"检查结果已过期，请重新检查后再发布。",pending:`将发布 ${changes} 项改动，玩家下次启动时自动更新。`,
    ok:"本次没有内容或规则改动；发布会创建内容相同的新版本。"}[state];
  const version=byId("publish-form").elements.displayVersion;
  if(version.dataset.edited!==project?.id) version.value=project ? suggestNextVersion(releases) : "";
  byId("version-suggestion").textContent=releases[0]
    ? `上一版本是 ${releases[0].displayVersion}，已按顺序建议下一个版本号，可以直接修改；同一项目内不要重复。`
    : "这是第一次发布，可以从 1.0.0 开始；以后会按上一版本自动建议。";
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

/** The dashboard walks through the daily path: organize files, check the changes, publish. */
function renderNextSteps(state,changes,latest) {
  const panel=byId("dashboard-next");
  const head=document.createElement("div"); head.className="next-action-head";
  const intro=document.createElement("div");
  const kicker=document.createElement("span"); kicker.className="page-kicker"; kicker.textContent="NEXT STEP";
  const heading=document.createElement("h2"), text=document.createElement("p");
  const actions=document.createElement("div"); actions.className="button-row";
  intro.append(kicker,heading,text); head.append(intro,actions);
  if(state==="none") {
    heading.textContent="创建第一个整合包项目";
    text.textContent="新建项目并选择要管理的文件目录，之后按“整理文件 → 检查改动 → 发布”完成第一次发布。";
    actions.append(workspaceButton("新建项目",()=>byId("open-create-project").click(),"primary-button"));
    panel.replaceChildren(head);
    return;
  }
  heading.textContent={busy:"文件任务进行中",stale:"下一步：重新检查改动",unchecked:"下一步：检查改动",
    pending:`下一步：发布 ${changes} 项改动`,ok:"整合包已是最新"}[state];
  text.textContent={busy:"已成功导入的文件会保留；任务完成后再检查并发布。",
    stale:"检查之后又修改过文件或维护设置，重新检查后再发布给玩家。",
    unchecked:"检查已保存的文件和维护设置，确认这次会给玩家带来哪些变化。",
    pending:"改动已经检查完毕。确认文件和规则变化，填写更新记录后即可发布。",
    ok:latest ? `玩家下次启动会获得版本 ${latest.displayVersion}。可以继续整理文件，或下载首次部署包发给新玩家。` : "当前没有待发布的改动。"}[state];
  const openView=(view)=>document.querySelector(`.nav-button[data-view="${view}"]`)?.click();
  actions.append(
    workspaceButton(state==="ok" || state==="busy" ? "整理文件" : "查看改动并发布",()=>openView(state==="ok" || state==="busy" ? "content" : "publish"),"primary-button"),
    workspaceButton("发放部署包",()=>openView("instance"),"secondary-button"));
  const steps=[
    {title:"整理文件",view:"content",state:state==="busy" ? "current" : "done",
      detail:state==="busy" ? "文件任务进行中" : "添加、替换文件并设置维护方式"},
    {title:"检查改动",view:"publish",state:state==="stale" || state==="unchecked" ? "current" : state==="busy" ? "pending" : "done",
      detail:state==="stale" ? "文件或设置有变化，需要重新检查" : state==="unchecked" || state==="busy" ? "尚未检查" : changes ? `发现 ${changes} 项改动` : "没有新的改动"},
    {title:"发布给玩家",view:"publish",state:state==="pending" ? "current" : state==="ok" ? "done" : "pending",
      detail:state==="pending" ? "填写版本与更新记录后发布" : latest ? `当前版本 ${latest.displayVersion}` : "尚未发布"}
  ];
  const list=document.createElement("ol"); list.className="workflow-steps";
  steps.forEach((step,index)=>{
    const item=document.createElement("li"); item.className=`workflow-step ${step.state}`;
    if(step.state==="current") item.setAttribute("aria-current","step");
    const button=workspaceButton("",()=>openView(step.view),"workflow-step-button");
    const marker=document.createElement("span"); marker.className="workflow-step-marker"; marker.setAttribute("aria-hidden","true");
    if(step.state==="done") marker.innerHTML='<svg viewBox="0 0 24 24"><path d="M6.5 12.5l3.5 3.5 7.5-8"/></svg>';
    else marker.textContent=String(index+1);
    const copy=document.createElement("span"); copy.className="workflow-step-copy";
    const title=document.createElement("strong"); title.textContent=step.title;
    const detail=document.createElement("small"); detail.textContent=step.detail;
    copy.append(title,detail); button.append(marker,copy); item.append(button); list.append(item);
  });
  panel.replaceChildren(head,list);
}

/** Suggests the version after the newest release by counting up its last number, skipping used ones. */
function suggestNextVersion(releases) {
  const latest=releases[0]?.displayVersion;
  if(!latest) return "1.0.0";
  const match=/^(.*?)(\d+)(\D*)$/.exec(latest);
  if(!match) return "";
  const [,prefix,digits,suffix]=match, used=new Set(releases.map(release=>release.displayVersion));
  for(let step=1n; step<=100n; step++) {
    const candidate=`${prefix}${String(BigInt(digits)+step).padStart(digits.length,"0")}${suffix}`;
    if(!used.has(candidate)) return candidate;
  }
  return "";
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
  byId("publish-form").elements.displayVersion.addEventListener("input",event=>{event.target.dataset.edited=app.project?.id || "";});
  byId("workspace-status").addEventListener("click",()=>{
    if(!app.project) byId("open-create-project").click();
    else document.querySelector('.nav-button[data-view="publish"]')?.click();
  });
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
  document.querySelectorAll('input[name="hostingMethod"]').forEach(choice=>choice.addEventListener("change",()=>{
    byId("hosting-method").value=choice.value; selectHostingMethod();
  }));
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
