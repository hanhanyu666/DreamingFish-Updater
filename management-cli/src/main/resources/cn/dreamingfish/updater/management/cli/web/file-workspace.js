"use strict";

function workspaceButton(text, onClick, className = "table-button") {
  const button = document.createElement("button");
  button.type = "button"; button.className = className; button.textContent = text;
  button.addEventListener("click", onClick); return button;
}

function sourceDirectories() {
  const directories = new Set(app.sourceFiles?.directories || []);
  for (const file of app.sourceFiles?.files || []) {
    const parts = file.path.split("/"); parts.pop();
    for (let length = 1; length <= parts.length; length++) directories.add(parts.slice(0, length).join("/"));
  }
  return [...directories].sort((a, b) => a.localeCompare(b, "zh-CN"));
}

function navigateSourceDirectory(path) {
  app.sourceDirectory = path;
  for (const ancestor of [...app.collapsedSourceDirectories]) {
    if (path.startsWith(`${ancestor}/`)) app.collapsedSourceDirectories.delete(ancestor);
  }
  byId("source-file-search").value = "";
  byId("file-panel").classList.remove("directory-open");
  byId("toggle-directory-panel").setAttribute("aria-expanded", "false");
  renderSourceFiles();
  document.querySelector("#file-panel .content-table").scrollTo({top:0, left:0});
}

function navigateSourceParent() {
  if (!app.sourceDirectory) return;
  navigateSourceDirectory(app.sourceDirectory.split("/").slice(0, -1).join("/"));
}

function sourceChange(file) {
  if (app.project?.previewStale) return { text: "待检查", kind: "pending" };
  const change = (app.project?.preview?.changes || []).find(item => foldPath(item.path) === foldPath(file.path));
  if (change) return { text: kindNames[change.kind] === "新增" ? "待发布 · 新增" : `待发布 · ${kindNames[change.kind] || "修改"}`, kind: "pending" };
  if (!file.published) return { text: "待发布 · 新增", kind: "pending" };
  return { text: "已发布", kind: "published" };
}

function workspaceFileRow(file) {
  const check = document.createElement("td");
  const checkbox = selectionCheckbox(pathSelected(app.sourceFileSelection, file.path), false, `选择 ${file.path}`);
  checkbox.addEventListener("change", () => { setPathSelected(app.sourceFileSelection, file.path, checkbox.checked); renderSourceFiles(); });
  check.append(checkbox);
  const name = document.createElement("td"); name.className = "workspace-file-name";
  const heading = document.createElement("div"); heading.className = "workspace-file-heading";
  const fileIcon = document.createElement("span"); fileIcon.className = "tree-entry-icon file"; fileIcon.setAttribute("aria-hidden", "true");
  const link = workspaceButton(file.path.split("/").pop(), () => openFileDetail(file.path), "file-name-button");
  link.title = file.path;
  const metadata = document.createElement("small");
  metadata.textContent = [file.displayName || file.componentId, file.version,
    byId("source-file-search").value.trim() ? file.path : ""].filter(Boolean).join(" · ");
  const state = sourceChange(file);
  heading.append(fileIcon, link); name.append(heading, metadata, tag(state.text, state.kind));
  const preset = document.createElement("td"); preset.append(presetSelect(file.path, false, file));
  const group = document.createElement("td");
  const owner = groupById(file.optionalGroup); if (owner) group.append(tag(owner.title, "group"));
  const remove = workspaceButton("移除…", () => removeSourceFiles([file]));
  remove.setAttribute("aria-label", `移除 ${file.path}`);
  const result = row([check, name, preset, group, formatBytes(file.size), remove]);
  result.className = "workspace-file-row"; return result;
}

function workspaceFolderRow(path, files) {
  const members = files.filter(file => foldPath(file.path).startsWith(`${foldPath(path)}/`));
  const check = treeSelectionCell(members, file => pathSelected(app.sourceFileSelection, file.path), () => true,
    (selected, entries) => { setPathsSelected(app.sourceFileSelection, entries, selected); renderSourceFiles(); }, `选择 ${path}/ 中的全部文件`);
  const name = document.createElement("td"); name.className = "workspace-file-name";
  const icon = document.createElement("span"); icon.className = "tree-entry-icon folder"; icon.setAttribute("aria-hidden", "true");
  const link = workspaceButton(`${path.split("/").pop()}/`, () => navigateSourceDirectory(path), "file-name-button");
  const detail = document.createElement("small");
  const exceptions = (app.project?.maintenance?.presets || []).filter(rule => !rule.directory && rule.path.startsWith(`${path}/`)).length;
  detail.textContent = `${members.length} 个文件${exceptions ? ` · ${exceptions} 项单独设置` : ""}`;
  name.append(icon, link, detail);
  const preset = document.createElement("td"); preset.append(presetSelect(path, true));
  return row([check, name, preset, "", formatBytes(members.reduce((sum, file) => sum + file.size, 0)),
    workspaceButton("打开", () => navigateSourceDirectory(path))]);
}

function renderFileWorkspace() {
  if (!app.sourceFiles) { byId("source-file-count").textContent = "正在读取文件…"; return; }
  const files = app.sourceFiles?.files || [];
  const directories = sourceDirectories();
  while (app.sourceDirectory && !directories.includes(app.sourceDirectory)) app.sourceDirectory = app.sourceDirectory.split("/").slice(0, -1).join("/");
  const query = byId("source-file-search").value.trim();
  const filtered = visibleSourceFiles().filter(file => query || !app.sourceDirectory || file.path.startsWith(`${app.sourceDirectory}/`));
  const pending = byId("content-filter").value === "pending";
  const scoped = pending ? filtered.filter(file => sourceChange(file).kind === "pending") : filtered;
  app.sourceScope = scoped;
  renderDirectoryNavigation(directories, files);
  renderDirectoryContext(files, query);
  byId("source-file-count").textContent = `${files.length} 个文件 · ${directories.length} 个文件夹`;
  byId("source-managed-count").textContent = String(files.length);
  byId("source-total-size").textContent = formatBytes(app.sourceFiles?.totalBytes || 0);
  byId("summary-required").textContent = String(files.filter(file => file.preset === "REQUIRED").length);
  byId("summary-soft").textContent = String(files.filter(file => file.preset === "INITIAL").length);
  byId("summary-optional").textContent = String(files.filter(file => file.optionalGroup).length);
  const prefix = app.sourceDirectory ? `${app.sourceDirectory}/` : "";
  const children = directories.filter(path => path.startsWith(prefix) && !path.slice(prefix.length).includes("/"));
  const selectedFilter = byId("content-filter").value;
  document.querySelector(".content-table").classList.toggle("no-optional-content", !(app.project?.maintenance?.optionalGroups || []).length);
  const rows = [];
  if (!query && !pending) for (const path of children) {
    if (selectedFilter === "all" || scoped.some(file => file.path.startsWith(`${path}/`))) rows.push(workspaceFolderRow(path, files));
  }
  for (const file of scoped) if (query || pending || !file.path.slice(prefix.length).includes("/")) rows.push(workspaceFileRow(file));
  for (const change of app.project?.preview?.changes || []) {
    if (change.kind !== "REMOVED" || (query && !change.path.toLowerCase().includes(query.toLowerCase()))
      || (!query && !change.path.startsWith(prefix)) || (!query && !pending && change.path.slice(prefix.length).includes("/"))
      || !["all", "pending"].includes(selectedFilter)) continue;
    const name = document.createElement("td"); name.className = "workspace-file-name";
    name.append(workspaceButton(change.path, () => openHistoryForPath(change.path), "file-name-button"), tag("待发布 · 移除", "pending"));
    rows.push(row(["", name, "等待发布", "", "", workspaceButton("查看历史", () => openHistoryForPath(change.path))]));
  }
  setRows("source-file-table", "source-file-empty", rows);
  byId("source-selection-toolbar").hidden = selectedSourceFiles().length === 0;
  updateSourceFileSelection(scoped);
  const hiddenCount = selectedSourceFiles().filter(file => !scoped.some(visible => visible.path === file.path)).length;
  if (hiddenCount) byId("source-file-selection-count").textContent += ` · ${hiddenCount} 项在其他范围`;
  if (byId("file-detail-dialog").open && files.some(file => file.path === app.detailPath)) renderFileDetail();
}

function renderDirectoryNavigation(directories, files) {
  const panel = byId("source-directory-tree");
  const root = workspaceButton("全部文件", () => navigateSourceDirectory(""), "directory-entry root-directory");
  root.setAttribute("aria-current", app.sourceDirectory === "" ? "page" : "false");
  const children = new Map([["", []]]);
  for (const path of directories) {
    const parent = path.split("/").slice(0, -1).join("/");
    if (!children.has(parent)) children.set(parent, []);
    children.get(parent).push(path);
  }
  function branch(parent) {
    const list = document.createElement("ul"); list.className = "directory-branches";
    for (const path of children.get(parent) || []) {
      const item = document.createElement("li"); item.className = "directory-node";
      const hasChildren = (children.get(path) || []).length > 0;
      const expanded = !app.collapsedSourceDirectories.has(path);
      const line = document.createElement("div"); line.className = "directory-node-line";
      line.dataset.current = String(app.sourceDirectory === path);
      line.dataset.ancestor = String(app.sourceDirectory.startsWith(`${path}/`));
      if (hasChildren) {
        const toggle = workspaceButton("", () => {
          if (expanded) app.collapsedSourceDirectories.add(path);
          else app.collapsedSourceDirectories.delete(path);
          renderDirectoryNavigation(directories, files);
        }, "directory-branch-toggle");
        toggle.setAttribute("aria-label", `${expanded ? "收起" : "展开"} ${path}/`);
        toggle.setAttribute("aria-expanded", String(expanded));
        toggle.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="m6 3 5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        line.append(toggle);
      } else {
        const spacer = document.createElement("span"); spacer.className = "directory-branch-spacer"; line.append(spacer);
      }
      const button = workspaceButton("", () => navigateSourceDirectory(path), "directory-entry");
      button.title = `${path}/`;
      button.setAttribute("aria-label", `打开文件夹 ${path}/`);
      button.setAttribute("aria-current", app.sourceDirectory === path ? "page" : "false");
      const name = document.createElement("span"); name.className = "directory-name"; name.textContent = path.split("/").pop();
      const count = document.createElement("small"); count.textContent = String(files.filter(file => file.path.startsWith(`${path}/`)).length);
      const icon = document.createElement("span"); icon.className = "tree-entry-icon folder"; icon.setAttribute("aria-hidden", "true");
      button.append(icon, name, count); line.append(button); item.append(line);
      if (hasChildren && expanded) item.append(branch(path));
      list.append(item);
    }
    return list;
  }
  panel.replaceChildren(root, branch(""));
}

function renderDirectoryContext(files, query) {
  const crumbs = byId("file-breadcrumbs");
  const root = workspaceButton(query ? "搜索全部文件" : "整合包", () => navigateSourceDirectory(""), "breadcrumb-button");
  const parts = app.sourceDirectory.split("/").filter(Boolean);
  const parentButton = byId("source-directory-up");
  parentButton.disabled = parts.length === 0;
  parentButton.title = parts.length ? `返回 ${parts.slice(0, -1).join("/") || "整合包根目录"}` : "当前已在整合包根目录";
  crumbs.replaceChildren(root, ...parts.map((part, index) => workspaceButton(part,
    () => navigateSourceDirectory(parts.slice(0, index + 1).join("/")), "breadcrumb-button")));
  const folder = byId("folder-preset"); folder.replaceChildren();
  if (app.sourceDirectory && !query) folder.append(presetSelect(app.sourceDirectory, true));
  const members = files.filter(file => !app.sourceDirectory || file.path.startsWith(`${app.sourceDirectory}/`));
  const exceptions = (app.project?.maintenance?.presets || []).filter(rule => !rule.directory
    && (!app.sourceDirectory || rule.path.startsWith(`${app.sourceDirectory}/`))).length;
  byId("folder-context").textContent = `${query ? "搜索范围：所有目录" : `${app.sourceDirectory || "根目录"} · ${members.length} 个文件`}`
    + (exceptions ? ` · ${exceptions} 项单独设置` : "") + "。文件与规则保存后，检查并发布才会到达玩家。";
}

function openFileDetail(path) {
  app.detailPath = path; renderFileDetail(); byId("file-detail-dialog").showModal();
}

function renderFileDetail() {
  const file = (app.sourceFiles?.files || []).find(file => file.path === app.detailPath);
  if (!file) { byId("file-detail-dialog").close(); return; }
  const body = byId("file-detail-body");
  const list = document.createElement("dl"); list.className = "file-facts";
  const facts = { "完整路径": file.path, "模组名称": file.displayName || "普通文件", "模组 ID": file.componentId || "—",
    "文件版本": file.version || "—", "大小": formatBytes(file.size), "最后修改": formatDate(new Date(file.lastModifiedMillis).toISOString()),
    "发布状态": sourceChange(file).text, "维护方式来源": file.presetSource === "FILE" ? "此文件单独设置" : file.presetSource === "DEFAULT" ? "默认普通同步" : file.presetSource?.startsWith("DIRECTORY:") ? `继承文件夹 ${file.presetSource.slice(10)}/` : "来自可选内容组",
    "可选内容": groupById(file.optionalGroup)?.title || "未加入可选内容" };
  for (const [label, value] of Object.entries(facts)) { const term = document.createElement("dt"); term.textContent = label; const description = document.createElement("dd"); description.textContent = value; list.append(term, description); }
  const field = document.createElement("label"); field.className = "field";
  const label = document.createElement("span"); label.textContent = "维护方式"; field.append(label, presetSelect(file.path, false, file));
  const remove = workspaceButton("移除此文件…", () => { byId("file-detail-dialog").close(); removeSourceFiles([file]); }, "danger-button");
  body.replaceChildren(list, field, remove);
}

async function openHistoryForPath(path) {
  if (byId("file-detail-dialog").open) byId("file-detail-dialog").close();
  await openFileHistory();
  if (byId("file-history-dialog").open) { byId("file-history-search").value = path; renderFileHistory(); }
}

async function confirmFolderPreset(items, preset) {
  const folders = items.filter(item => item.directory);
  if (!folders.length) return true;
  const members = (app.sourceFiles?.files || []).filter(file => folders.some(folder => file.path.startsWith(`${folder.path}/`)));
  const exceptions = (app.project?.maintenance?.presets || []).filter(rule => !rule.directory
    && folders.some(folder => rule.path.startsWith(`${folder.path}/`))).length;
  return confirmAction("修改整个文件夹的维护方式", `${folders.map(folder => `${folder.path}/`).join("、")}\n涉及 ${members.length} 个文件，${exceptions} 项单独设置会统一。`
    + (preset === "REQUIRED" ? "\n强制同步会将该范围内玩家自加的额外文件移入备份。" : "")
    + "\n保存后仍需检查并发布。", "确认保存");
}

async function showImportHistory() {
  if (!app.project) return;
  await runBusy("正在读取导入记录", async () => {
    const entries = await api(`/api/projects/${encodeURIComponent(app.project.id)}/files/import-history`);
    byId("import-history-list").replaceChildren(...entries.map(entry => {
      const article = document.createElement("article"); article.className = "import-history-entry";
      const title = document.createElement("strong"); title.textContent = entry.path;
      const description = document.createElement("p"); description.textContent = `${formatDate(entry.createdAt)} · ${formatBytes(entry.size)} · ${entry.undone ? "已恢复导入前内容" : `归档了 ${entry.previousPaths.length} 个旧文件`}`;
      article.append(title, description);
      if (!entry.undone) article.append(workspaceButton("恢复导入前内容", async () => {
        const accepted = await confirmAction("恢复导入前内容", `${entry.path}\n恢复前会校验后续修改。恢复结果需要检查并发布，才能到达玩家。`, "确认恢复");
        if (!accepted) return;
        await runBusy("正在恢复归档文件", async () => {
          const result = await api(`/api/projects/${encodeURIComponent(app.project.id)}/files/undo-import`, {method:"POST",body:{id:entry.id}});
          await loadProject(app.project.id); await loadSourceFiles();
          if (result.checkError) showErrorDialog(`文件已恢复，但发布检查未通过：${result.checkError}`);
          else toast("已恢复导入前内容，等待发布");
          byId("import-history-dialog").close();
        });
      }));
      return article;
    }));
    if (!entries.length) byId("import-history-list").append(note("还没有通过新版文件清单导入的记录。"));
    byId("import-history-dialog").showModal();
  });
}
