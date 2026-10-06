"use strict";

const workspaceOperations = {entries: [], plan: null};

function operationChanges(changes) {
  const fragment = document.createDocumentFragment();
  for (const change of changes || []) {
    const article = document.createElement("article"); article.className = "operation-change";
    const title = document.createElement("strong"); title.textContent = change.setting;
    const before = document.createElement("span"); before.className = "operation-before"; before.textContent = change.before || "—";
    const after = document.createElement("span"); after.className = "operation-after"; after.textContent = change.after || "—";
    const arrow = document.createElement("span"); arrow.className = "operation-arrow"; arrow.textContent = "→"; arrow.setAttribute("aria-hidden", "true");
    article.append(title, before, arrow, after); fragment.append(article);
  }
  return fragment;
}

function renderWorkspaceHistory() {
  const query = byId("operation-history-search").value.trim().toLowerCase();
  const kind = byId("operation-history-filter").value;
  const entries = workspaceOperations.entries.filter(entry => (kind === "all" || entry.kind === kind)
    && (!query || `${entry.title} ${JSON.stringify(entry.changes)}`.toLowerCase().includes(query)));
  const list = byId("workspace-history-list");
  list.replaceChildren(...entries.map(entry => {
    const article = document.createElement("article"); article.className = "operation-history-entry";
    const body = document.createElement("div"); body.className = "operation-history-body";
    const title = document.createElement("strong"); title.textContent = entry.title;
    const date = document.createElement("span"); date.className = "operation-time";
    date.textContent = formatDate(entry.createdAt);
    const type = {FILES: "文件", SETTINGS: "设置", PUBLISH: "发布"}[entry.kind] || "操作";
    body.append(title, date, tag(entry.undone ? "已撤销" : type, entry.undone ? "published" : ""));
    if (entry.changes?.length) {
      const details = document.createElement("details");
      const summary = document.createElement("summary"); summary.textContent = `查看 ${entry.changes.length} 项变化`;
      const changes = document.createElement("div"); changes.className = "operation-change-list";
      changes.append(operationChanges(entry.changes)); details.append(summary, changes); body.append(details);
    }
    const actions = document.createElement("div"); actions.className = "operation-entry-actions";
    if (entry.canUndo) actions.append(workspaceButton("撤销…", () => showOperationPreview("undo", entry.id), "table-button"));
    else if (entry.undoUnavailableReason) { const reason = document.createElement("small"); reason.textContent = entry.undoUnavailableReason; actions.append(reason); }
    article.append(body, actions); return article;
  }));
  if (!entries.length) list.append(note(query ? "没有匹配的记录。" : "还没有操作记录。"));
  byId("workspace-history-count").textContent = `${entries.length} 条记录 · 最近 200 条`;
}

async function showWorkspaceHistory() {
  if (!app.project) return;
  let entries;
  await runBusy("正在读取操作记录", async () => {
    entries = await api(`/api/projects/${encodeURIComponent(app.project.id)}/operations`);
  });
  if (!entries) return;
  workspaceOperations.entries = entries;
  byId("operation-history-search").value = ""; byId("operation-history-filter").value = "all";
  renderWorkspaceHistory(); byId("workspace-history-dialog").showModal();
}

async function showOperationPreview(mode, id) {
  if (!app.project) return;
  const projectId = app.project.id;
  let result;
  await runBusy("正在核对恢复内容", async () => {
    const url = `/api/projects/${encodeURIComponent(projectId)}/operations/`;
    result = mode === "published" ? await api(`${url}published-preview`)
      : await api(`${url}undo-preview`, {method: "POST", body: {id}});
  });
  if (!result) return;
  workspaceOperations.plan = {mode, id, projectId, ...result};
  byId("operation-restore-title").textContent = mode === "published" ? `恢复 ${result.displayVersion} 的维护设置` : result.title;
  byId("operation-restore-description").textContent = mode === "published"
    ? "维护方式、可选内容与问题处理会恢复为当前已发布版本的设置。"
    : "以下内容将恢复到这次操作之前的状态。";
  const changes = byId("operation-restore-changes"); changes.replaceChildren(operationChanges(result.changes));
  if (!result.changes?.length) changes.append(note("当前设置已经一致，无需恢复。"));
  const submit = byId("confirm-operation-restore");
  submit.textContent = mode === "published" ? "确认恢复设置" : "确认撤销";
  submit.disabled = !result.changes?.length || result.undone;
  if (!byId("operation-restore-dialog").open) byId("operation-restore-dialog").showModal();
}

byId("open-workspace-history").addEventListener("click", showWorkspaceHistory);
byId("restore-published-settings").addEventListener("click", () => showOperationPreview("published"));
byId("operation-history-search").addEventListener("input", renderWorkspaceHistory);
byId("operation-history-filter").addEventListener("change", renderWorkspaceHistory);
byId("refresh-operation-preview").addEventListener("click", () => {
  const plan = workspaceOperations.plan; if (plan) showOperationPreview(plan.mode, plan.id);
});
byId("operation-restore-form").addEventListener("submit", async event => {
  if (event.submitter?.value === "cancel") return;
  event.preventDefault();
  const plan = workspaceOperations.plan; if (!plan || app.project?.id !== plan.projectId) return;
  await runBusy("正在恢复", async () => {
    const action = plan.mode === "published" ? "restore-published" : "undo";
    const result = await api(`/api/projects/${encodeURIComponent(plan.projectId)}/operations/${action}`,
      {method: "POST", body: {id: plan.id, stamp: plan.stamp}});
    byId("operation-restore-dialog").close(); if (byId("workspace-history-dialog").open) byId("workspace-history-dialog").close();
    workspaceOperations.plan = null;
    await loadProject(plan.projectId); await loadSourceFiles();
    if (result.checkError) showErrorDialog(`${result.message}。发布检查未通过：${result.checkError}`);
    else toast(result.message);
  });
});
