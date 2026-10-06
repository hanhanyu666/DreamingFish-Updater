"use strict";

const TRANSFER_STATES = {WAITING:"等待检查", CHECKING:"正在暂存检查", READY:"已检查，等待确认", COMMITTING:"正在导入", DONE:"已导入，等待发布", FAILED:"失败，可重试", SKIPPED:"已跳过"};
let transferSequence = 0;

function transferPath(directory, relative) {
  const value = String(relative).replaceAll("\\", "/");
  if (!value || value.startsWith("/") || value.includes(":") || value.split("/").some(part => !part || part === "." || part === ".."))
    throw new Error("文件相对路径无效");
  return directory ? `${directory}/${value}` : value;
}

function addTransferFiles(files, folder = false) {
  if (!app.project || app.transferBusy) return;
  if (folder && !byId("source-keep-folder").dataset.edited) {
    const root=(files[0]?.relativePath || files[0]?.webkitRelativePath || "").split("/")[0];
    byId("source-keep-folder").checked=!app.uploadTargetDirectory && ["mods","config","resourcepacks","shaderpacks","kubejs","defaultconfigs"].includes(root);
  }
  for (const value of files) {
    const file=value.file || value;
    let relative=value.relativePath || file.webkitRelativePath || file.name;
    if (folder && !byId("source-keep-folder").checked && relative.includes("/")) relative=relative.split("/").slice(1).join("/");
    const path = transferPath(app.uploadTargetDirectory || "", relative);
    if (app.transferTasks.some(task => foldPath(task.path) === foldPath(path) && !["DONE","SKIPPED"].includes(task.state))) {
      byId("transfer-result").textContent = `${path} 已在清单中；移除旧项或修改保存路径后再添加。`;
      continue;
    }
    app.transferTasks.push({key:++transferSequence,file,path,state:"WAITING",percent:0,action:"ADD",error:"",plan:null});
  }
  app.pendingUploads = app.transferTasks.filter(task => task.file && task.state === "WAITING").map(task => task.file);
  renderTransferQueue();
}

function openSourceTransfer() {
  if (!app.project) return;
  if (app.transferProjectId && app.transferProjectId !== app.project.id) {
    app.transferTasks = []; app.pendingUploads = [];
  }
  app.transferProjectId = app.project.id;
  app.uploadTargetDirectory = app.sourceDirectory || "";
  renderUploadTargetTree(); renderTransferQueue();
  if (!byId("source-add-dialog").open) byId("source-add-dialog").showModal();
}

function renderTransferQueue() {
  const list = byId("transfer-queue");
  list.replaceChildren(...app.transferTasks.map(task => {
    const article = document.createElement("article"); article.className = `transfer-item state-${task.state.toLowerCase()}`;
    const heading = document.createElement("div"); heading.className = "transfer-item-heading";
    const title = document.createElement("strong"); title.textContent = task.file?.name || task.path.split("/").pop();
    const state = document.createElement("span"); state.textContent = TRANSFER_STATES[task.state] + (task.state === "CHECKING" ? ` · ${task.percent}%` : "");
    heading.append(title, state);
    const path = document.createElement("label"); path.className = "transfer-path field";
    const caption = document.createElement("span"); caption.textContent = "保存路径";
    const input = document.createElement("input"); input.value = task.draftPath ?? task.path; input.disabled = app.transferBusy || ["DONE","SKIPPED"].includes(task.state);
    const rememberPath=()=>{task.draftPath=input.value; updateSourceAddActions();};
    input.addEventListener("input",rememberPath); input.addEventListener("change",rememberPath);
    path.append(caption, input);
    const details = document.createElement("p"); details.className = "transfer-file-info";
    if (task.plan) {
      const metadata = task.plan.metadata;
      const identity = metadata ? `${metadata.displayName || metadata.componentId} · ${metadata.componentId} · ${metadata.version || "未知版本"}` : "普通文件";
      details.textContent = `${identity} · ${formatBytes(task.plan.size)}`
        + (task.plan.existing ? `\n同名文件已存在${task.plan.existingMetadata?.version ? ` · 旧版本 ${task.plan.existingMetadata.version}` : ""}，覆盖前会归档。` : "")
        + (task.plan.replacements.length ? `\n发现同一模组的旧文件：${task.plan.replacements.map(item => `${item.path} (${item.version || "未知版本"})`).join("、")}。替换时沿用维护方式和可选组。` : "");
    } else details.textContent = `${formatBytes(task.file?.size || 0)} · 暂存检查不会改动整合包文件`;
    const controls = document.createElement("div"); controls.className = "transfer-item-actions";
    if (task.plan && !["DONE","SKIPPED"].includes(task.state)) {
      const action = document.createElement("select"); action.setAttribute("aria-label", `${task.path} 的导入动作`); action.disabled = app.transferBusy;
      if (!task.plan.existing && !task.plan.replacements.length) action.append(option("ADD", "新增文件"));
      if (task.plan.existing && !task.plan.replacements.length) action.append(option("OVERWRITE", "归档并覆盖同名文件"));
      if (task.plan.replacements.length) action.append(option("REPLACE_MOD", "替换以上旧模组（保留设置）"));
      action.append(option("SKIP", "跳过这一项")); action.value = task.action;
      action.addEventListener("change", () => {task.action = action.value; updateSourceAddActions();});
      controls.append(action);
    }
    if (["FAILED","WAITING"].includes(task.state)) {
      const retry = workspaceButton(task.state === "FAILED" ? "重新检查这一项" : "检查这一项", () => checkTransferTasks([task])); retry.disabled = app.transferBusy; controls.append(retry);
    }
    if (!["DONE","COMMITTING"].includes(task.state)) {
      const remove = workspaceButton("移出清单", async () => { if (task.plan) await discardTransferPlan(task); app.transferTasks = app.transferTasks.filter(other => other !== task); renderTransferQueue(); });
      remove.disabled = app.transferBusy; controls.append(remove);
    }
    article.append(heading, path, details, controls);
    if (task.error) { const error = document.createElement("p"); error.className = "transfer-error"; error.textContent = task.error; article.append(error); }
    return article;
  }));
  if (!app.transferTasks.length) list.append(note("选择文件后，清单会显示每个文件的保存位置和检查结果。"));
  const done = app.transferTasks.filter(task => task.state === "DONE").length;
  const failed = app.transferTasks.filter(task => task.state === "FAILED").length;
  byId("transfer-summary").textContent = `${app.transferTasks.length} 项 · 已导入 ${done} 项${failed ? ` · 失败 ${failed} 项` : ""}`;
  byId("source-upload-selection").textContent = app.transferTasks.length ? "可以继续添加文件；已导入项不会重复提交" : "所选文件先进入下方清单";
  updateSourceAddActions(); renderWorkflow();
}

function updateSourceAddActions() {
  const tasks = app.transferTasks || [];
  const remaining = tasks.filter(task => !["DONE","SKIPPED"].includes(task.state));
  const pathChanged=task=>task.draftPath!=null && task.draftPath.trim()!==task.path;
  byId("check-source-files").disabled = app.transferBusy || !remaining.some(task => task.state !== "READY" || pathChanged(task));
  byId("upload-source-files").disabled = app.transferBusy || !remaining.length || remaining.some(task => task.state !== "READY" || pathChanged(task));
  byId("upload-source-files").textContent = `确认导入${remaining.length ? ` ${remaining.filter(task => task.action !== "SKIP").length} 项` : ""}`;
  byId("import-server-source").disabled = app.transferBusy || !String(byId("source-add-form").elements.serverSourcePath.value).trim();
  byId("create-source-folder").disabled = app.transferBusy || !byId("source-new-folder-name").value.trim();
  byId("pause-source-transfer").hidden = !app.transferBusy;
  for (const id of ["choose-source-upload","choose-source-folder"]) byId(id).disabled = app.transferBusy;
  byId("project-select").disabled = app.transferBusy || app.packageBusy || !(app.state?.projects || []).length;
}

async function discardTransferPlan(task) {
  const projectId = app.transferProjectId;
  const id = task.plan?.id; task.plan = null;
  if (id) try { await api(`/api/projects/${encodeURIComponent(projectId)}/files/stage?id=${encodeURIComponent(id)}`, {method:"DELETE"}); } catch (_) { /* Plan expiry is safe: it never changed source content. */ }
}

function startTransfer() {
  app.transferBusy = true; app.transferPaused = false;
  byId("transfer-result").textContent = ""; renderTransferQueue();
}
function finishTransfer() { app.transferBusy = false; renderTransferQueue(); }

async function checkTransferTasks(selected = null) {
  if (app.transferBusy || !app.project) return;
  for (const task of app.transferTasks.filter(task=>!["DONE","SKIPPED"].includes(task.state) && task.draftPath!=null && task.draftPath.trim()!==task.path)) {
    try {
      const next=transferPath("",task.draftPath.trim());
      if(app.transferTasks.some(other=>other!==task && foldPath(other.draftPath?.trim() || other.path)===foldPath(next) && !["DONE","SKIPPED"].includes(other.state))) throw new Error("另一个待导入文件正在使用此路径");
      const oldPlan=task.plan; task.plan=null; task.path=next; task.draftPath=next; task.state="WAITING"; task.error="";
      if(oldPlan) try {await api(`/api/projects/${encodeURIComponent(app.transferProjectId)}/files/stage?id=${encodeURIComponent(oldPlan.id)}`,{method:"DELETE"});} catch(_) {}
    } catch(error) {task.state="FAILED"; task.error=error.message; renderTransferQueue(); return;}
  }
  const tasks = selected || app.transferTasks.filter(task => !["DONE","SKIPPED","READY"].includes(task.state));
  const projectId = app.transferProjectId; startTransfer(); let recovered = false;
  try {
    for (const task of tasks) {
      if (app.transferPaused) break;
      task.state = "CHECKING"; task.error = ""; task.percent = 0; renderTransferQueue();
      try {
        let plan;
        if (task.plan) plan = await api(`/api/projects/${encodeURIComponent(projectId)}/files/recheck`, {method:"POST",body:{id:task.plan.id}});
        else if (task.serverPath) plan = await api(`/api/projects/${encodeURIComponent(projectId)}/files/stage-server`, {method:"POST",body:{sourcePath:task.serverPath,targetPath:task.path}});
        else plan = await AdminTransport.upload(`/api/projects/${encodeURIComponent(projectId)}/files/stage?path=${encodeURIComponent(task.path)}`, task.file, app.token, (loaded) => {
          task.percent = task.file.size ? Math.round(100 * loaded / task.file.size) : 100;
          byId("source-upload-progress").hidden = false; byId("source-upload-label").textContent = `暂存 ${task.file.name}，然后由服务器检查`;
          byId("source-upload-meter").value = task.percent; byId("source-upload-percent").textContent = `${task.percent}%`;
        }, app.activeUploads);
        if (plan.undone) throw new Error("该导入已恢复，移出此项并重新选择文件即可开始新的导入");
        task.plan = plan; task.state = plan.committed ? "DONE" : "READY";
        if (plan.committed) recovered = true;
        task.action = plan.existing?.sha256 === plan.sha256 ? "SKIP" : plan.replacements.length ? "REPLACE_MOD" : plan.existing ? "OVERWRITE" : "ADD";
      } catch (error) {
        task.state = app.transferPaused ? "WAITING" : "FAILED";
        task.error = app.transferPaused ? "已暂停，可以继续检查" : error.message;
        if (error.status === 400 && task.plan) task.plan = null;
      }
      renderTransferQueue();
    }
    if (recovered) {
      try { await api(`/api/projects/${encodeURIComponent(projectId)}/files/check-imports`, {method:"POST",body:{ids:app.transferTasks.filter(task=>task.state==="DONE").map(task=>task.plan.id)}}); } catch(error) {byId("transfer-result").textContent=`已导入的文件保留，发布检查未通过：${error.message}`;}
      await loadProject(projectId); await loadSourceFiles();
    }
  } finally { byId("source-upload-progress").hidden = true; finishTransfer(); }
}

async function commitTransferTasks() {
  if (app.transferBusy) return;
  if(app.transferTasks.some(task=>task.state==="READY" && task.draftPath!=null && task.draftPath.trim()!==task.path)) {byId("transfer-result").textContent="保存路径已修改，请先重新检查清单。"; return;}
  const tasks = app.transferTasks.filter(task => task.state === "READY");
  const imports = tasks.filter(task => task.action !== "SKIP");
  const replacements = imports.filter(task => ["REPLACE_MOD","OVERWRITE"].includes(task.action));
  if (replacements.length && !await confirmAction("确认导入与替换", `将导入 ${imports.length} 个文件，其中 ${replacements.length} 项会归档并替换旧文件。\n请确认清单里的目标路径和旧版本；导入后还需发布整合包。`, "确认导入")) return;
  const projectId = app.transferProjectId; startTransfer(); let completed = 0;
  try {
    for (const task of tasks) {
      if (app.transferPaused) break;
      if (task.action === "SKIP") { task.state = "SKIPPED"; await discardTransferPlan(task); continue; }
      task.state = "COMMITTING"; task.error = ""; renderTransferQueue();
      try {
        await api(`/api/projects/${encodeURIComponent(projectId)}/files/commit`, {method:"POST",body:{id:task.plan.id,action:task.action,stamp:task.plan.stamp}});
        task.state = "DONE"; completed++;
      } catch (error) { task.state = "FAILED"; task.error = `${error.message}；重新检查这一项后可再次确认。`; }
      renderTransferQueue();
    }
    if (completed) {
      let checkError = "";
      try { await api(`/api/projects/${encodeURIComponent(projectId)}/files/check-imports`, {method:"POST",body:{ids:app.transferTasks.filter(task=>task.state==="DONE").map(task=>task.plan.id)}}); }
      catch (error) { checkError = error.message; }
      await loadProject(projectId); await loadSourceFiles();
      byId("transfer-result").textContent = checkError ? `${completed} 项已导入，但发布检查未通过：${checkError}` : `${completed} 项已导入并检查完成；到“检查并发布”发布后，玩家才会收到。`;
    }
  } finally { finishTransfer(); }
}

function abortActiveUploads() { [...app.activeUploads].forEach(request => request.abort()); }

function bindSourceFiles() {
  byId("source-keep-folder").addEventListener("change",()=>{byId("source-keep-folder").dataset.edited="true";});
  byId("clear-completed-transfers").addEventListener("click",()=>{app.transferTasks=app.transferTasks.filter(task=>!["DONE","SKIPPED"].includes(task.state)); renderTransferQueue();});
  byId("source-file-select-all").addEventListener("change", event => {setPathsSelected(app.sourceFileSelection, app.sourceScope || [], event.target.checked); renderSourceFiles();});
  byId("clear-source-selection").addEventListener("click", () => {app.sourceFileSelection.clear(); renderSourceFiles();});
  byId("remove-selected-source-files").addEventListener("click", () => removeSourceFiles(selectedSourceFiles()));
  byId("reload-source-files").addEventListener("click", () => runBusy("正在刷新文件", loadSourceFiles));
  byId("toggle-directory-panel").addEventListener("click", () => { const open = byId("file-panel").classList.toggle("directory-open"); byId("toggle-directory-panel").setAttribute("aria-expanded", String(open)); });
  byId("source-directory-up").addEventListener("click", navigateSourceParent);
  byId("open-import-history").addEventListener("click", showImportHistory);
  byId("detail-history").addEventListener("click", () => openHistoryForPath(app.detailPath));
  byId("detail-replace").addEventListener("click", () => {app.sourceDirectory = app.detailPath.split("/").slice(0,-1).join("/"); byId("file-detail-dialog").close(); openSourceTransfer();});
  byId("open-source-add").addEventListener("click", openSourceTransfer);
  byId("show-transfer-task").addEventListener("click", openSourceTransfer);
  const form = byId("source-add-form");
  form.addEventListener("submit", event => {event.preventDefault(); if (event.submitter?.value === "cancel") byId("source-add-dialog").close();});
  byId("source-add-dialog").addEventListener("close", renderSourceFiles);
  for (const [button, input] of [["choose-source-upload","source-upload-input"],["choose-source-folder","source-folder-input"]]) {
    byId(button).addEventListener("click", () => byId(input).click());
    byId(input).addEventListener("change", () => { try {addTransferFiles([...byId(input).files],input==="source-folder-input"); byId(input).value = "";} catch(error) {showErrorDialog(error.message);} });
  }
  const dropzone = byId("source-dropzone");
  dropzone.addEventListener("click",event=>{if(event.target===dropzone || ["STRONG","SPAN"].includes(event.target.tagName)) byId("source-upload-input").click();});
  dropzone.addEventListener("keydown", event => {if (event.target === dropzone && ["Enter"," "].includes(event.key)) {event.preventDefault(); byId("source-upload-input").click();}});
  for (const type of ["dragenter","dragover"]) dropzone.addEventListener(type, event => {event.preventDefault(); dropzone.classList.add("dragging");});
  for (const type of ["dragleave","drop"]) dropzone.addEventListener(type, event => {event.preventDefault(); dropzone.classList.remove("dragging");});
  dropzone.addEventListener("drop", event => {try {addTransferFiles([...event.dataTransfer.files]);} catch(error) {showErrorDialog(error.message);}});
  document.querySelectorAll("[data-source-tab]").forEach(button => button.addEventListener("click", () => {
    document.querySelectorAll("[data-source-tab]").forEach(tab => tab.setAttribute("aria-selected", String(tab === button)));
    document.querySelectorAll("[data-source-pane]").forEach(pane => {pane.hidden = pane.dataset.sourcePane !== button.dataset.sourceTab;});
  }));
  form.elements.serverSourcePath.addEventListener("input", updateSourceAddActions);
  byId("source-new-folder-name").addEventListener("input", updateSourceAddActions);
  byId("create-source-folder").addEventListener("click", createSourceFolder);
  byId("check-source-files").addEventListener("click", () => checkTransferTasks());
  byId("upload-source-files").addEventListener("click", commitTransferTasks);
  byId("pause-source-transfer").addEventListener("click", () => { app.transferPaused = true; abortActiveUploads(); byId("transfer-result").textContent = "已暂停后续任务。已经导入的文件会保留；正在提交的一项会先完成。"; });
  byId("import-server-source").addEventListener("click", async () => {
    const serverPath = form.elements.serverSourcePath.value.trim();
    const path = transferPath(app.uploadTargetDirectory || "", serverPath.replaceAll("\\", "/").split("/").pop());
    if (app.transferTasks.some(task => foldPath(task.path) === foldPath(path) && !["DONE","SKIPPED"].includes(task.state))) {showErrorDialog("此保存路径已在清单中"); return;}
    const task = {key:++transferSequence,serverPath,path,state:"WAITING",percent:0,action:"ADD",error:"",plan:null};
    app.transferTasks.push(task); await checkTransferTasks([task]);
  });
}
