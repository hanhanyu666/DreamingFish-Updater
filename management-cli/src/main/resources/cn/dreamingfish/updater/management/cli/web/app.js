"use strict";

const app = {
  token: "",
  auth: null,
  state: null,
  project: null,
  selectedProjectId: "",
  view: "dashboard",
  busy: false,
  forcedDirectorySelection: new Set(),
  forcedFileSelection: new Set(),
  sourceFileSelection: new Set(),
  sourceExpandedFolders: new Set(),
  forcedDirectoryExpandedFolders: new Set(),
  forcedFileExpandedFolders: new Set(),
  sourceFiles: null,
  pendingUploads: [],
  uploadTargetDirectory: null,
  uploadTargetExpandedFolders: new Set(),
  activeUploads: new Set(),
  sourceUploadCancelled: false,
  pendingCoverFile: null,
  pendingCoverPreviewUrl: null,
  playerPreviewReady: false,
  pathBrowser: {
    targetInput: null,
    kind: "directory",
    title: "选择服务器路径",
    currentPath: "",
    parentPath: null,
    selectedPath: "",
    roots: [],
    entries: [],
    truncated: false
  }
};

const titles = {
  dashboard: "运行概览",
  project: "项目设置",
  personalization: "玩家端个性化",
  publish: "管理文件",
  player: "玩家端程序",
  distribution: "外部托管",
  instance: "玩家实例",
  settings: "服务设置"
};

const kindNames = {
  ADDED: "新增",
  MODIFIED: "修改",
  REMOVED: "删除",
  POLICY_CHANGED: "策略",
  METADATA_CHANGED: "模组信息"
};

const byId = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Accept", "application/json");
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (app.token) headers.set("X-DFS-Token", app.token);
  const response = await fetch(path, {
    method: options.method || "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const contentType = response.headers.get("Content-Type") || "";
  const data = contentType.includes("application/json")
    ? await response.json()
    : null;
  if (!response.ok) {
    throw new Error(data?.message || `请求失败：HTTP ${response.status}`);
  }
  return data;
}

async function initialize() {
  initializeTheme();
  bindEvents();
  try {
    await refreshAuth();
  } catch (error) {
    setConnection(false);
    toast(error.message, true);
  }
}

async function refreshAuth() {
  const status = await api("/api/auth/status");
  app.auth = status || {};
  renderAuthIdentity();
  const registered = Boolean(status.registered ?? status.configured ?? status.hasAccount);
  const authenticated = Boolean(status.authenticated ?? status.loggedIn ?? status.localBypass);
  byId("auth-loading").hidden = true;
  byId("register-form").hidden = registered;
  byId("login-form").hidden = !registered || authenticated;
  if (!registered || !authenticated) {
    app.token = "";
    byId("app-shell").hidden = true;
    byId("auth-screen").hidden = false;
    const loginUsername = byId("login-form").elements.username;
    if (status.username && !loginUsername.value) loginUsername.value = status.username;
    return;
  }
  await enterManagement();
}

async function enterManagement() {
  const session = await api("/api/session");
  app.token = session.token || "";
  byId("admin-version").textContent = `DreamingFish Admin ${session.version || ""}`.trim();
  byId("auth-screen").hidden = true;
  byId("app-shell").hidden = false;
  setConnection(true);
  await refreshState();
}

function bindAuthentication() {
  byId("auth-theme-toggle").addEventListener("click", () => {
    const current = document.documentElement.dataset.theme === "light" ? "light" : "dark";
    applyTheme(current === "light" ? "dark" : "light", true);
  });
  byId("register-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    if (values.get("password") !== values.get("confirmPassword")) {
      showErrorDialog("两次输入的密码不一致。");
      return;
    }
    await runBusy("正在创建管理员账户", async () => {
      await api("/api/auth/register", { method: "POST", body: {
        username: values.get("username"), password: values.get("password"),
        confirmPassword: values.get("confirmPassword"),
        allowLocalBypass: values.get("allowLocalBypass") === "on"
      }});
      form.reset();
      await refreshAuth();
    });
  });
  byId("login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    await runBusy("正在登录", async () => {
      await api("/api/auth/login", { method: "POST", body: {
        username: values.get("username"), password: values.get("password")
      }});
      form.elements.password.value = "";
      await refreshAuth();
    });
  });
  byId("logout-button").addEventListener("click", async () => {
    await runBusy("正在注销", async () => {
      await api("/api/auth/logout", { method: "POST", body: {} });
      app.token = "";
      app.state = null;
      app.project = null;
      await refreshAuth();
    });
  });
  byId("account-settings").addEventListener("click", () => {
    const form = byId("account-form");
    form.reset();
    form.elements.username.value = app.auth?.username || "";
    form.elements.allowLocalBypass.checked = Boolean(app.auth?.allowLocalBypass);
    byId("account-dialog").showModal();
  });
  byId("account-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (event.submitter?.value === "cancel") {
      byId("account-dialog").close("cancel");
      return;
    }
    const form = event.currentTarget;
    const values = new FormData(form);
    const newPassword = String(values.get("newPassword") || "");
    if (newPassword !== String(values.get("confirmPassword") || "")) {
      showErrorDialog("两次输入的新密码不一致。");
      return;
    }
    await runBusy("正在保存账户设置", async () => {
      await api("/api/auth/account", { method: "PUT", body: {
        username: values.get("username"), password: values.get("password"),
        newPassword, confirmPassword: values.get("confirmPassword"),
        allowLocalBypass: values.get("allowLocalBypass") === "on"
      }});
      byId("account-dialog").close();
      form.reset();
      app.auth = await api("/api/auth/status");
      renderAuthIdentity();
      toast("账户安全设置已保存");
    });
  });
}

async function refreshState(preferredProjectId = app.selectedProjectId) {
  app.state = await api("/api/state");
  const projects = app.state.projects || [];
  const available = new Set(projects.map((project) => project.id));
  let nextProject = preferredProjectId;
  if (!nextProject || !available.has(nextProject)) {
    nextProject = available.has(app.state.defaultProjectId)
      ? app.state.defaultProjectId
      : projects[0]?.id || "";
  }
  app.selectedProjectId = nextProject;
  renderState();
  if (nextProject) {
    await loadProject(nextProject);
    if (app.view === "publish") {
      await loadSourceFiles();
    }
  } else {
    app.project = null;
    renderProjectDependentViews();
    if (app.view !== "dashboard" && app.view !== "settings") {
      showView("dashboard");
    }
  }
}

async function loadProject(projectId, platform) {
  const projectChanged = app.project?.id !== projectId;
  const requestedPlatform = platform
    || app.project?.platform
    || "windows-x64";
  app.selectedProjectId = projectId;
  app.project = await api(
    `/api/projects/${encodeURIComponent(projectId)}`
      + `?platform=${encodeURIComponent(requestedPlatform)}`
  );
  app.forcedDirectorySelection = new Set(
    app.project.forcedSyncDirectories || []
  );
  app.forcedFileSelection = new Set(app.project.forcedSyncFiles || []);
  app.sourceFileSelection = new Set();
  app.sourceFiles = null;
  if (projectChanged) {
    app.sourceExpandedFolders.clear();
    app.forcedDirectoryExpandedFolders.clear();
    app.forcedFileExpandedFolders.clear();
    app.uploadTargetExpandedFolders.clear();
    app.uploadTargetDirectory = null;
  }
  renderProjectOptions();
  renderProjectDependentViews();
}

function renderState() {
  renderProjectOptions();
  renderService();
  renderDashboard();
  renderSettings();
  const hasProject = (app.state.projects || []).length > 0;
  document.querySelectorAll(".requires-project").forEach((button) => {
    button.disabled = !hasProject;
  });
}

function renderProjectOptions() {
  const select = byId("project-select");
  const projects = app.state?.projects || [];
  select.replaceChildren();
  if (projects.length === 0) {
    select.append(option("", "尚无项目"));
    select.disabled = true;
    return;
  }
  projects.forEach((project) => {
    const item = option(
      project.id,
      `${project.displayName} · ${project.id}`
    );
    item.selected = project.id === app.selectedProjectId;
    select.append(item);
  });
  select.disabled = false;
}

function renderService() {
  const service = app.state.publicService;
  byId("service-indicator").classList.toggle("running", service.running);
  byId("service-title").textContent = service.running
    ? "HTTP 文件服务运行中"
    : "HTTP 文件服务未启动";
  byId("service-address").textContent = service.address;
  byId("service-address").title = service.address;
  byId("service-start").disabled = service.running;
  byId("service-stop").disabled = !service.running;
}

function renderDashboard() {
  const projects = app.state.projects || [];
  const releases = projects.reduce(
    (total, project) => total + project.releaseCount, 0);
  const selected = projects.find(
    (project) => project.id === app.selectedProjectId);
  byId("metric-projects").textContent = String(projects.length);
  byId("metric-releases").textContent = String(releases);
  byId("metric-current").textContent =
    selected?.latestRelease?.displayVersion || "--";
  byId("metric-sequence").textContent =
    selected ? `#${selected.nextSequence}` : "--";
  byId("project-count-label").textContent = `${projects.length} 个项目`;

  const rows = projects.map((project) => {
    const manage = actionButton("管理", async () => {
      await runBusy("正在读取项目", async () => {
        await loadProject(project.id);
        showView("project");
      });
    });
    return row([
      `${project.displayName}  ·  ${project.id}`,
      pathCell(project.sourceDirectory),
      project.latestRelease?.displayVersion || "尚未发布",
      String(project.releaseCount),
      manage
    ]);
  });
  setRows("project-table", "project-empty", rows);
}

function renderProjectDependentViews() {
  renderProjectForm();
  renderPersonalizationForm();
  renderSourceFiles();
  renderPreview();
  renderForcedDirectories();
  renderForcedFiles();
  renderReleases();
  renderPrograms();
  renderInstanceReleases();
}

async function loadSourceFiles() {
  if (!app.project) return;
  app.sourceFiles = await api(
    `/api/projects/${encodeURIComponent(app.project.id)}/files`
  );
  const selected = app.sourceFileSelection;
  app.sourceFileSelection = new Set(
    (app.sourceFiles.files || [])
      .filter((file) => pathSelected(selected, file.path))
      .map((file) => file.path)
  );
  renderSourceFiles();
  renderForcedDirectories();
  renderForcedFiles();
}

function renderSourceFiles() {
  const result = app.sourceFiles;
  if (!result) {
    byId("source-file-count").textContent = "打开页面后读取";
    byId("source-managed-count").textContent = "--";
    byId("source-total-size").textContent = "--";
    byId("source-forced-count").textContent = "--";
    byId("source-file-selection-count").textContent = "未选择文件";
    byId("source-file-select-all").checked = false;
    byId("source-file-select-all").indeterminate = false;
    byId("source-file-select-all").disabled = true;
    byId("remove-selected-source-files").disabled = true;
    setRows("source-file-table", "source-file-empty", []);
    return;
  }
  const query = byId("source-file-search").value
    .trim().toLocaleLowerCase("zh-CN");
  const allFiles = result.files || [];
  const files = allFiles.filter((file) =>
    !query || file.path.toLocaleLowerCase("zh-CN").includes(query)
  );
  const forcedCount = allFiles.filter(
    (file) => file.forcedByDirectory || file.forcedByFile
  ).length;
  byId("source-file-count").textContent = query
    ? `显示 ${files.length} / ${result.count} 个文件`
    : `${result.count} 个托管文件`;
  byId("source-managed-count").textContent = String(result.count);
  byId("source-total-size").textContent = formatBytes(result.totalBytes);
  byId("source-forced-count").textContent = String(forcedCount);
  byId("source-file-empty").textContent = query
    ? "没有符合搜索条件的文件"
    : "整合包目录中没有文件";
  const rows = fileTreeRows(
    files,
    sourceFolderRow,
    sourceFileRow,
    {
      expandedFolders: app.sourceExpandedFolders,
      expandAll: Boolean(query),
      onToggle: renderSourceFiles
    }
  );
  setRows("source-file-table", "source-file-empty", rows);
  updateSourceFileSelection(files);
}

function sourceFolderRow(node, depth, treeState) {
  const control = treeSelectionCell(
    node.entries,
    (file) => pathSelected(app.sourceFileSelection, file.path),
    () => true,
    (checked, files) => {
      setPathsSelected(app.sourceFileSelection, files, checked);
      renderSourceFiles();
    },
    node.path ? `选择 ${node.path}/ 中的全部文件` : "选择当前列表中的全部文件"
  );
  const status = document.createElement("span");
  status.className = "source-status folder-status";
  status.textContent = node.path ? "文件夹" : "全部";
  const folder = folderPathCell(node, depth, treeState);
  const totalBytes = node.entries.reduce(
    (sum, file) => sum + Number(file.size || 0), 0
  );
  const item = row([
    control,
    status,
    folder,
    formatBytes(totalBytes),
    "--",
    ""
  ]);
  item.className = "file-tree-folder";
  return item;
}

function sourceFileRow(file, depth) {
  const control = document.createElement("td");
  const checkbox = selectionCheckbox(
    pathSelected(app.sourceFileSelection, file.path),
    false,
    `选择 ${file.path}`
  );
  checkbox.addEventListener("change", () => {
    setPathSelected(app.sourceFileSelection, file.path, checkbox.checked);
    renderSourceFiles();
  });
  control.append(checkbox);

  const status = document.createElement("span");
  status.className = "source-status";
  if (file.forcedByDirectory || file.forcedByFile) {
    status.classList.add("forced");
    status.textContent = file.forcedByDirectory ? "目录强制" : "单文件强制";
  } else {
    status.textContent = file.policy === "DEFAULT" ? "默认文件" : "普通托管";
  }
  const filePath = treeFilePathCell(file.path, depth);
  const remove = actionButton("移除", () => removeSourceFile(file));
  const item = row([
    control,
    status,
    filePath,
    formatBytes(file.size),
    formatDate(file.lastModifiedMillis),
    remove
  ]);
  item.className = "file-tree-file";
  return item;
}

function updateSourceFileSelection(visibleFiles = []) {
  const allFiles = app.sourceFiles?.files || [];
  const selected = allFiles.filter(
    (file) => pathSelected(app.sourceFileSelection, file.path)
  );
  byId("source-file-selection-count").textContent = selected.length === 0
    ? "未选择文件"
    : `已选择 ${selected.length} 个文件`;
  byId("remove-selected-source-files").disabled = selected.length === 0;
  applySelectionState(
    byId("source-file-select-all"),
    visibleFiles,
    (file) => pathSelected(app.sourceFileSelection, file.path),
    () => true
  );
}

function renderProjectForm() {
  if (!app.project) return;
  const form = byId("project-form");
  byId("project-identity").textContent =
    `${app.project.displayName} · ${app.project.id}`;
  setFormValue(form, "displayName", app.project.displayName);
  setFormValue(form, "sourceDirectory", app.project.sourceDirectory);
  setFormValue(form, "publicBaseUrl", app.project.publicBaseUrl);
  setFormValue(
    form,
    "forcedSyncDirectories",
    (app.project.forcedSyncDirectories || []).join(", ")
  );
}

function renderPersonalizationForm() {
  if (!app.project) return;
  const form = byId("personalization-form");
  const branding = app.project.branding || {};
  byId("personalization-identity").textContent =
    `${app.project.displayName} · ${app.project.id}`;
  setFormValue(form, "productName", branding.productName);
  setFormValue(form, "brandName", branding.brandName || "梦鱼服");
  setFormValue(
    form,
    "brandEnglishName",
    branding.brandEnglishName || "DreamingFish"
  );
  setFormValue(form, "subtitle", branding.subtitle);
  setFormValue(form, "serverAddress", branding.serverAddress);
  setColor(form, "accentColor", branding.accentColor);
  setColor(
    form,
    "secondaryAccentColor",
    branding.secondaryAccentColor
  );
  clearPendingCover();
  form.elements.removeCover.checked = false;
  const legacy = branding.contentPages == null;
  byId("legacy-news-note").hidden = !legacy;
  renderPlayerPageEditor(legacyPlayerPages(branding));
  renderMusicTracks(branding.musicTracks || []);
  updatePlayerPreview();
}

function renderMusicTracks(tracks) {
  const list = byId("music-track-list");
  const empty = byId("music-empty");
  if (!list || !empty) return;
  list.replaceChildren();
  empty.classList.toggle("visible", !tracks.length);
  tracks.forEach((track) => {
    const item = document.createElement("div");
    item.className = "music-track-row";
    const label = document.createElement("span");
    label.textContent = `${track.title} · ${track.fileName}`;
    label.title = label.textContent;
    const remove = actionButton("删除", async () => {
      if (!app.project) return;
      const accepted = await ask("删除音乐", `确认删除“${track.title}”吗？发布后玩家端也会移除这首歌。`, "删除", true);
      if (!accepted) return;
      await runBusy("正在删除音乐", async () => {
        await api(`/api/projects/${encodeURIComponent(app.project.id)}/music/${encodeURIComponent(track.id)}`, { method: "DELETE" });
        await refreshState(app.project.id);
      });
    });
    item.append(label, remove);
    list.append(item);
  });
}

function legacyPlayerPages(branding) {
  if (Array.isArray(branding.contentPages)) return branding.contentPages;
  const pages = [{
    id: "news",
    navigationLabel: "新闻",
    announcementPage: true,
    eyebrow: `${branding.brandEnglishName || "SERVER"} NEWS`,
    title: `${branding.brandName || "服务器"}新闻`,
    lead: "这里记录服务器动态、版本消息和想与玩家分享的内容。",
    markdown: "",
    articles: branding.newsArticles || []
  }];
  if (branding.customPage?.enabled) {
    pages.push({
      id: "custom",
      navigationLabel: branding.customPage.navigationLabel,
      announcementPage: false,
      eyebrow: branding.customPage.eyebrow,
      title: branding.customPage.title,
      lead: branding.customPage.lead,
      markdown: branding.customPage.markdown,
      articles: []
    });
  }
  return pages;
}

function playerPageField(label, value, options = {}) {
  const wrapper = document.createElement("label");
  wrapper.className = `field${options.wide ? " field-wide" : ""}`;
  const caption = document.createElement("span");
  caption.textContent = label;
  const control = document.createElement(options.multiline ? "textarea" : "input");
  control.dataset[options.scope || "pageField"] = options.name;
  control.value = value || "";
  if (options.type) control.type = options.type;
  if (options.maxLength) control.maxLength = options.maxLength;
  if (options.placeholder) control.placeholder = options.placeholder;
  if (options.required) control.required = true;
  if (options.multiline) control.rows = 7;
  wrapper.append(caption, control);
  return wrapper;
}

function markdownEditor(label, value, scope, placeholder) {
  const wrapper = document.createElement("div");
  wrapper.className = "field field-wide markdown-editor";
  const caption = document.createElement("span");
  caption.textContent = label;
  const toolbar = document.createElement("div");
  toolbar.className = "markdown-toolbar";
  let savedSelectionStart = 0;
  let savedSelectionEnd = 0;
  [
    ["标题", "## ", ""], ["加粗", "**", "**"], ["列表", "- ", ""],
    ["引用", "> ", ""], ["链接", "[文字](", ")"], ["图片", "![说明](", ")"]
  ].forEach(([text, before, after]) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary-button markdown-tool";
    button.textContent = text;
    button.addEventListener("mousedown", (event) => {
      savedSelectionStart = textarea.selectionStart;
      savedSelectionEnd = textarea.selectionEnd;
      event.preventDefault();
    });
    button.addEventListener("click", () => {
      insertMarkdown(textarea, before, after, savedSelectionStart, savedSelectionEnd);
      savedSelectionStart = textarea.selectionStart;
      savedSelectionEnd = textarea.selectionEnd;
    });
    toolbar.append(button);
  });
  const textarea = document.createElement("textarea");
  textarea.dataset[scope] = "markdown";
  textarea.maxLength = 131072;
  textarea.rows = 8;
  textarea.value = value || "";
  textarea.placeholder = placeholder;
  const rememberSelection = () => {
    savedSelectionStart = textarea.selectionStart;
    savedSelectionEnd = textarea.selectionEnd;
  };
  textarea.addEventListener("select", rememberSelection);
  textarea.addEventListener("keyup", rememberSelection);
  textarea.addEventListener("click", rememberSelection);
  textarea.addEventListener("focus", rememberSelection);
  const help = document.createElement("small");
  help.className = "field-help";
  help.textContent = "选中文字后点快捷按钮即可排版，也可以直接粘贴 Markdown。";
  wrapper.append(caption, toolbar, textarea, help);
  return wrapper;
}

function insertMarkdown(textarea, before, after, savedStart, savedEnd) {
  const start = Number.isInteger(savedStart) ? savedStart : textarea.selectionStart;
  const end = Number.isInteger(savedEnd) ? savedEnd : textarea.selectionEnd;
  const selected = textarea.value.slice(start, end);
  textarea.setRangeText(`${before}${selected}${after}`, start, end, "end");
  textarea.focus();
  textarea.dispatchEvent(new Event("input", { bubbles: true }));
}

function renderPlayerPageEditor(pages) {
  const editor = byId("player-page-editor");
  editor.replaceChildren();
  if (pages.length === 0) {
    const empty = document.createElement("div");
    empty.className = "player-news-empty";
    empty.textContent = "还没有添加页面。玩家端只会显示主页和“关于更新器”；需要公告、玩法介绍或服务器规则时，点右上角“添加页面”。";
    editor.append(empty);
    updatePlayerPreview();
    return;
  }
  pages.forEach((page, index) => {
    const card = document.createElement("section");
    card.className = "player-news-card player-page-card";
    card.dataset.pageIndex = String(index);
    const header = document.createElement("div");
    header.className = "player-news-card-header";
    const heading = document.createElement("strong");
    heading.textContent = `页面 ${index + 1} · ${page.navigationLabel || "未命名"}`;
    const actions = document.createElement("div");
    actions.className = "button-row";
    const moveUp = actionButton("↑", () => movePlayerPage(index, -1));
    moveUp.disabled = index === 0;
    const moveDown = actionButton("↓", () => movePlayerPage(index, 1));
    moveDown.disabled = index === pages.length - 1;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "danger-button";
    remove.textContent = "删除";
    remove.addEventListener("click", () => {
      const next = readPlayerPageEditor();
      next.splice(index, 1);
      byId("legacy-news-note").hidden = true;
      renderPlayerPageEditor(next);
    });
    actions.append(moveUp, moveDown, remove);
    header.append(heading, actions);
    const fields = document.createElement("div");
    fields.className = "player-news-card-fields";
    const type = document.createElement("label");
    type.className = "check-field field-wide announcement-page-toggle";
    const typeInput = document.createElement("input");
    typeInput.type = "checkbox";
    typeInput.dataset.pageField = "announcementPage";
    typeInput.checked = Boolean(page.announcementPage);
    const typeText = document.createElement("span");
    typeText.textContent = "设为公告页（可在本页连续添加多条新闻，最新一条会显示在主页）";
    type.append(typeInput, typeText);
    fields.append(
      playerPageField("页面 ID（必填）", page.id, {
        name: "id", maxLength: 64, required: true, placeholder: "server-rules"
      }),
      playerPageField("顶部导航名称（必填）", page.navigationLabel, {
        name: "navigationLabel", maxLength: 12, required: true, placeholder: "服务器规则"
      }),
      type,
      playerPageField("页面顶部小标题", page.eyebrow, {
        name: "eyebrow", maxLength: 48, placeholder: "WELCOME"
      }),
      playerPageField("页面主标题（必填）", page.title, {
        name: "title", maxLength: 120, required: true
      }),
      playerPageField("页面引导语", page.lead, {
        name: "lead", maxLength: 300, wide: true
      })
    );
    const body = document.createElement("div");
    body.className = "player-page-body field-wide";
    renderPlayerPageBody(body, page, index);
    fields.append(body);
    fields.addEventListener("input", () => {
      byId("legacy-news-note").hidden = true;
      updatePlayerPreview();
    });
    typeInput.addEventListener("change", () => {
      const next = readPlayerPageEditor();
      next[index].announcementPage = typeInput.checked;
      next[index].articles ||= [];
      renderPlayerPageEditor(next);
    });
    card.append(header, fields);
    editor.append(card);
  });
  updatePlayerPreview();
}

function renderPlayerPageBody(body, page, pageIndex) {
  if (!page.announcementPage) {
    body.append(markdownEditor("页面正文", page.markdown, "pageBodyField",
      "可以写服务器介绍、玩法说明、规则或加入方式。"));
    return;
  }
  const top = document.createElement("div");
  top.className = "player-news-card-header announcement-list-header";
  const label = document.createElement("strong");
  label.textContent = `${(page.articles || []).length} 条新闻 / 公告`;
  const add = actionButton("＋ 添加新闻", () => addAnnouncement(pageIndex));
  top.append(label, add);
  body.append(top);
  if ((page.articles || []).length === 0) {
    const empty = document.createElement("div");
    empty.className = "player-news-empty";
    empty.textContent = "这个公告页还没有内容，点“添加新闻”开始写第一条。";
    body.append(empty);
  }
  (page.articles || []).forEach((article, articleIndex) => {
    const item = document.createElement("div");
    item.className = "announcement-editor-card";
    item.dataset.articleIndex = String(articleIndex);
    const header = document.createElement("div");
    header.className = "player-news-card-header";
    const title = document.createElement("strong");
    title.textContent = `新闻 ${articleIndex + 1}`;
    const remove = actionButton("删除新闻", () => removeAnnouncement(pageIndex, articleIndex));
    remove.className = "danger-button";
    header.append(title, remove);
    const fields = document.createElement("div");
    fields.className = "player-news-card-fields";
    fields.append(
      playerPageField("标题（必填）", article.title, { scope: "articleField", name: "title", maxLength: 120, required: true }),
      playerPageField("发布日期（必填）", article.publishedOn, { scope: "articleField", name: "publishedOn", type: "date", required: true }),
      playerPageField("文章 ID（必填）", article.id, { scope: "articleField", name: "id", maxLength: 64, required: true }),
      playerPageField("封面图片网址", article.coverUrl, { scope: "articleField", name: "coverUrl", maxLength: 2048 }),
      playerPageField("摘要", article.summary, { scope: "articleField", name: "summary", maxLength: 300, wide: true }),
      markdownEditor("正文", article.markdown, "articleBodyField", "写下完整公告内容。")
    );
    item.append(header, fields);
    body.append(item);
  });
}

function readPlayerPageEditor() {
  return [...byId("player-page-editor").querySelectorAll(".player-page-card")]
    .map((card) => {
      const value = (name) => String(card.querySelector(`[data-page-field="${name}"]`)?.value || "").trim();
      const announcementPage = Boolean(card.querySelector('[data-page-field="announcementPage"]')?.checked);
      const articles = [...card.querySelectorAll(".announcement-editor-card")].map((item) => {
        const articleValue = (name) => String(item.querySelector(`[data-article-field="${name}"]`)?.value || "").trim();
        return {
          id: articleValue("id"), title: articleValue("title"), summary: articleValue("summary"),
          publishedOn: articleValue("publishedOn"), coverUrl: articleValue("coverUrl"),
          markdown: String(item.querySelector('[data-article-body-field="markdown"]')?.value || "").trim()
        };
      });
      return {
        id: value("id"), navigationLabel: value("navigationLabel"), announcementPage,
        eyebrow: value("eyebrow"), title: value("title"), lead: value("lead"),
        markdown: announcementPage ? "" : String(card.querySelector('[data-page-body-field="markdown"]')?.value || "").trim(),
        articles: announcementPage ? articles : []
      };
    });
}

function movePlayerPage(index, offset) {
  const pages = readPlayerPageEditor();
  const target = index + offset;
  if (target < 0 || target >= pages.length) return;
  [pages[index], pages[target]] = [pages[target], pages[index]];
  renderPlayerPageEditor(pages);
}

function addAnnouncement(pageIndex) {
  const pages = readPlayerPageEditor();
  const now = new Date();
  const date = now.toISOString().slice(0, 10);
  const articles = pages[pageIndex].articles || [];
  let suffix = articles.length + 1;
  let id = `news-${date}-${suffix}`;
  const ids = new Set(articles.map((article) => article.id));
  while (ids.has(id)) id = `news-${date}-${++suffix}`;
  articles.push({ id, title: "", summary: "", publishedOn: date, coverUrl: "", markdown: "" });
  pages[pageIndex].articles = articles;
  renderPlayerPageEditor(pages);
}

function removeAnnouncement(pageIndex, articleIndex) {
  const pages = readPlayerPageEditor();
  pages[pageIndex].articles.splice(articleIndex, 1);
  renderPlayerPageEditor(pages);
}

function playerPagesConfig() {
  return {
    schemaVersion: 1,
    description: "DreamingFish Updater 玩家端页面配置",
    pages: readPlayerPageEditor()
  };
}

function exportPlayerPages() {
  const json = `${JSON.stringify(playerPagesConfig(), null, 2)}\n`;
  const blob = new Blob([json], { type: "application/json;charset=utf-8" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `${app.project?.id || "project"}-player-pages.json`;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(link.href), 0);
  toast("页面配置已导出");
}

function playerPagesAiPrompt() {
  return `你正在帮助我修改 DreamingFish Updater 的玩家端页面配置。\n\n` +
    `请只返回完整、有效的 JSON，不要使用 Markdown 代码块，也不要解释。必须保留 schemaVersion=1。` +
    `pages 最多 12 项；id 只能使用英文字母、数字、点、下划线和短横线且不能重复；navigationLabel 最多 12 个字符。` +
    `announcementPage=true 表示公告页，内容写入 articles；false 表示普通页面，正文写入 markdown。` +
    `公告的 publishedOn 使用 YYYY-MM-DD，正文支持 Markdown。不要添加未知字段。\n\n` +
    `我的修改要求：\n【请在这里写您想让 AI 修改的内容】\n\n` +
    `当前配置：\n${JSON.stringify(playerPagesConfig(), null, 2)}`;
}

async function copyPlayerPagesAiPrompt() {
  const prompt = playerPagesAiPrompt();
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(prompt);
  } else {
    const textarea = document.createElement("textarea");
    textarea.value = prompt;
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    document.body.append(textarea);
    textarea.select();
    if (!document.execCommand("copy")) {
      textarea.remove();
      throw new Error("浏览器不允许自动复制，请导出配置后手动复制给 AI。");
    }
    textarea.remove();
  }
  toast("AI 提示词和当前配置已复制，可以直接粘贴给 AI");
}

function validateImportedPlayerPages(value) {
  if (!value || value.schemaVersion !== 1 || !Array.isArray(value.pages)) {
    throw new Error("这不是有效的玩家端页面配置：缺少 schemaVersion=1 或 pages。 ");
  }
  if (value.pages.length > 12) throw new Error("玩家端页面最多只能添加 12 个。");
  const ids = new Set();
  value.pages.forEach((page, index) => {
    if (!page || typeof page !== "object") throw new Error(`第 ${index + 1} 个页面格式不正确。`);
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(String(page.id || "")) || ids.has(page.id)) {
      throw new Error(`第 ${index + 1} 个页面的 ID 无效或重复。`);
    }
    ids.add(page.id);
    if (!String(page.navigationLabel || "").trim() || String(page.navigationLabel).length > 12) {
      throw new Error(`第 ${index + 1} 个页面的导航名称不能为空且最多 12 个字符。`);
    }
    if (!String(page.title || "").trim()) throw new Error(`第 ${index + 1} 个页面缺少主标题。`);
    page.announcementPage = Boolean(page.announcementPage);
    page.eyebrow = String(page.eyebrow || "");
    page.lead = String(page.lead || "");
    page.markdown = String(page.markdown || "");
    page.articles = page.announcementPage && Array.isArray(page.articles) ? page.articles : [];
    if (page.articles.length > 50) throw new Error(`第 ${index + 1} 个公告页最多包含 50 条新闻。`);
    const articleIds = new Set();
    page.articles.forEach((article, articleIndex) => {
      if (!article || !/^[A-Za-z0-9._-]{1,64}$/.test(String(article.id || ""))
          || articleIds.has(article.id)) {
        throw new Error(`第 ${index + 1} 个页面的第 ${articleIndex + 1} 条新闻 ID 无效或重复。`);
      }
      articleIds.add(article.id);
      if (!String(article.title || "").trim()) {
        throw new Error(`第 ${index + 1} 个页面的第 ${articleIndex + 1} 条新闻缺少标题。`);
      }
      if (!/^\d{4}-\d{2}-\d{2}$/.test(String(article.publishedOn || ""))) {
        throw new Error(`第 ${index + 1} 个页面的第 ${articleIndex + 1} 条新闻日期必须使用 YYYY-MM-DD。`);
      }
      article.title = String(article.title);
      article.summary = String(article.summary || "");
      article.coverUrl = String(article.coverUrl || "");
      article.markdown = String(article.markdown || "");
    });
  });
  return value.pages;
}

async function importPlayerPages(file) {
  if (file.size > 1024 * 1024) throw new Error("页面配置文件不能超过 1 MiB。");
  let parsed;
  try {
    parsed = JSON.parse(await file.text());
  } catch {
    throw new Error("JSON 无法读取，请检查逗号、引号和括号是否完整。");
  }
  const pages = validateImportedPlayerPages(parsed);
  const accepted = await ask(
    "导入玩家端页面配置？",
    `已读取 ${pages.length} 个页面。确认后会替换当前编辑区，仍需点击“保存个性化设置”才会写入项目。`,
    "导入并预览"
  );
  if (!accepted) return;
  byId("legacy-news-note").hidden = true;
  renderPlayerPageEditor(pages);
  toast("配置已导入，请检查右侧预览后保存");
}

function clearPendingCover(updatePreview = false) {
  if (app.pendingCoverPreviewUrl) {
    URL.revokeObjectURL(app.pendingCoverPreviewUrl);
  }
  app.pendingCoverFile = null;
  app.pendingCoverPreviewUrl = null;
  const input = byId("cover-upload-input");
  if (input) input.value = "";
  const label = byId("cover-upload-name");
  if (label) label.textContent = "尚未选择新图片，将保留当前背景";
  if (updatePreview) updatePlayerPreview();
}

function selectPendingCover(file) {
  if (!file) {
    clearPendingCover(true);
    return;
  }
  if (file.size === 0) {
    clearPendingCover(true);
    showErrorDialog("所选图片是空文件，请重新选择。");
    return;
  }
  if (file.size > 32 * 1024 * 1024) {
    clearPendingCover(true);
    showErrorDialog("背景图片不能超过 32 MiB。");
    return;
  }
  if (app.pendingCoverPreviewUrl) {
    URL.revokeObjectURL(app.pendingCoverPreviewUrl);
  }
  app.pendingCoverFile = file;
  app.pendingCoverPreviewUrl = URL.createObjectURL(file);
  byId("cover-upload-name").textContent = `已选择：${file.name}`;
  const form = byId("personalization-form");
  form.elements.removeCover.checked = false;
  updatePlayerPreview();
}

function updatePlayerPreview() {
  if (!app.project) return;
  const form = byId("personalization-form");
  const value = (name, fallback) => {
    const text = String(form.elements.namedItem(name)?.value || "").trim();
    return text || fallback;
  };
  const hasCover = Boolean(app.project.branding?.coverObject);
  const removeCover = form.elements.removeCover.checked;
  const pendingCover = app.pendingCoverFile;
  const coverState = byId("personalization-cover-state");
  let backgroundUrl = null;
  if (pendingCover && app.pendingCoverPreviewUrl) {
    backgroundUrl = app.pendingCoverPreviewUrl;
    coverState.textContent = `正在预览本机图片 ${pendingCover.name}；保存后将上传到管理端`;
  } else if (hasCover && !removeCover) {
    const projectId = encodeURIComponent(app.project.id);
    const hash = encodeURIComponent(app.project.branding.coverObject);
    backgroundUrl = `/api/projects/${projectId}/cover?v=${hash}`;
    coverState.textContent = "当前正在预览已保存的自定义背景";
  } else {
    coverState.textContent = removeCover
      ? "保存后将移除自定义背景并恢复内置默认背景"
      : "当前未设置自定义背景，玩家端将使用内置默认背景";
  }
  const payload = {
    type: "dfs-admin-preview",
    branding: {
      productName: value("productName", app.project.displayName),
      subtitle: value("subtitle", "Minecraft 整合包更新"),
      serverAddress: value("serverAddress", ""),
      coverObject: app.project.branding?.coverObject || null,
      accentColor: value("accentColorText", "#2ee8df"),
      secondaryAccentColor: value("secondaryAccentColorText", "#b06cff"),
      brandName: value("brandName", "服务器"),
      brandEnglishName: value("brandEnglishName", "Minecraft"),
      newsArticles: [],
      customPage: null,
      contentPages: readPlayerPageEditor()
    },
    backgroundUrl
  };
  byId("player-preview-frame")?.contentWindow?.postMessage(payload, location.origin);
}

function resizePlayerPreview() {
  const stage = byId("player-preview-stage");
  if (!stage) return;
  const scale = Math.max(0.1, stage.clientWidth / 1180);
  stage.style.setProperty("--player-preview-scale", String(scale));
}

function renderPreview() {
  const preview = app.project?.preview;
  const summary = byId("preview-summary").querySelectorAll("strong");
  if (!preview) {
    summary.forEach((element) => {
      element.textContent = "--";
    });
    byId("preview-time").textContent = "尚未扫描";
    byId("preview-empty").textContent = "扫描后显示文件变更";
    setRows("preview-table", "preview-empty", []);
    byId("release-all-button").disabled = true;
    byId("delete-all-button").disabled = true;
    return;
  }
  summary[0].textContent = String(preview.managedFiles);
  summary[1].textContent = String(preview.changes.length);
  summary[2].textContent = formatBytes(preview.totalManagedBytes);
  summary[3].textContent = formatBytes(preview.estimatedDownloadBytes);
  const removals = preview.changes.filter(
    (change) => change.kind === "REMOVED"
  );
  const undecided = removals.filter(
    (change) => !change.removalAction
  ).length;
  byId("preview-time").textContent =
    `扫描于 ${formatDate(preview.createdAt)}`
      + (removals.length > 0
        ? ` · ${undecided > 0 ? `待决定 ${undecided} 项` : "移除项已确认"}`
        : preview.changes.length === 0 ? " · 本次没有修改" : "");
  byId("preview-empty").textContent = preview.changes.length === 0
    ? "本次没有修改"
    : "扫描后显示文件变更";
  byId("release-all-button").disabled = removals.length === 0;
  byId("delete-all-button").disabled = removals.length === 0;
  const rows = preview.changes.map((change) => {
    const badge = document.createElement("span");
    badge.className =
      `change-badge ${change.kind.toLowerCase()}`;
    badge.textContent = kindNames[change.kind] || change.kind;
    const action = document.createElement("td");
    if (change.kind === "REMOVED") {
      const forced = insideForcedDirectory(change.path);
      const select = document.createElement("select");
      select.className = "removal-action";
      select.dataset.path = change.path;
      select.append(option("", "请选择"));
      select.append(option("DELETE", "从玩家端删除"));
      if (!forced) {
        select.append(option("RELEASE", "放弃管理并保留"));
      }
      if (forced && !change.removalAction) {
        change.removalAction = "DELETE";
      }
      select.value = change.removalAction || "";
      select.title = forced
        ? "该文件位于强制同步目录，只能从玩家端移除"
        : "选择玩家更新到本版本时如何处理";
      select.addEventListener("change", () => {
        change.removalAction = select.value || null;
        renderRemovalStatus();
      });
      action.append(select);
    } else {
      action.textContent = "--";
      action.className = "muted-cell";
    }
    return row([
      badge,
      pathCell(change.path),
      change.downloadSize > 0
        ? formatBytes(change.downloadSize)
        : "--",
      action
    ]);
  });
  setRows("preview-table", "preview-empty", rows);
}

function renderRemovalStatus() {
  const preview = app.project?.preview;
  if (!preview) return;
  const removals = preview.changes.filter(
    (change) => change.kind === "REMOVED"
  );
  const undecided = removals.filter(
    (change) => !change.removalAction
  ).length;
  byId("preview-time").textContent =
    `扫描于 ${formatDate(preview.createdAt)}`
      + (removals.length > 0
        ? ` · ${undecided > 0 ? `待决定 ${undecided} 项` : "移除项已确认"}`
        : "");
}

function renderForcedDirectories() {
  const directories = forcedDirectoryCandidates();
  const rows = [];
  directories.forEach((directory) => {
    const selected = pathSelected(
      app.forcedDirectorySelection, directory.path
    );
    const control = document.createElement("td");
    const checkbox = selectionCheckbox(
      selected,
      directory.missing && !selected,
      selected
        ? `取消强制同步 ${directory.path}/`
        : directory.missing
          ? `${directory.path}/ 当前不存在`
          : `强制同步 ${directory.path}/`
    );
    checkbox.addEventListener("change", () => {
      setPathSelected(
        app.forcedDirectorySelection,
        directory.path,
        checkbox.checked
      );
      renderForcedDirectories();
    });
    control.append(checkbox);
    const effect = directory.missing
      ? "目录不存在，请取消选择后保存"
      : selected
        ? "整个目录保持一致，禁止玩家豁免"
        : "普通管理，不处理玩家额外文件";
    const node = forcedDirectoryTreeNode(directory);
    const treeState = folderExpansionState(node, {
      expandedFolders: app.forcedDirectoryExpandedFolders,
      onToggle: renderForcedDirectories
    });
    const item = row([
      control,
      folderPathCell(node, 0, treeState),
      effect,
      directory.missing ? "--" : String(directory.fileCount)
    ]);
    item.className = "file-tree-folder";
    rows.push(item);
    if (treeState.expanded) {
      rows.push(...forcedDirectoryContentRows(node, directory.path));
    }
  });
  setRows("forced-directory-table", "forced-directory-empty", rows);

  const selectedCount = directories.filter((directory) =>
    pathSelected(app.forcedDirectorySelection, directory.path)
  ).length;
  byId("forced-directory-count").textContent = selectedCount === 0
    ? "未设置强制同步目录"
    : `已选择 ${selectedCount} 个强制同步目录`;
  byId("forced-directory-visible-count").textContent = directories.length === 0
    ? "当前没有可管理的一级目录"
    : `${directories.filter((directory) => !directory.missing).length} 个现有一级目录`;
  applySelectionState(
    byId("forced-directory-select-all"),
    directories,
    (directory) => pathSelected(
      app.forcedDirectorySelection, directory.path
    ),
    (directory) => !directory.missing
  );
}

function forcedDirectoryCandidates() {
  const known = new Map();
  (app.sourceFiles?.files || []).forEach((file) => {
    const normalized = String(file.path || "").replaceAll("\\", "/");
    const slash = normalized.indexOf("/");
    if (slash <= 0) return;
    const path = normalized.slice(0, slash);
    const key = foldPath(path);
    const current = known.get(key);
    if (current) {
      current.fileCount += 1;
      current.entries.push(file);
    } else {
      known.set(key, {
        path,
        fileCount: 1,
        missing: false,
        entries: [file]
      });
    }
  });
  const configured = new Set([
    ...(app.project?.forcedSyncDirectories || []),
    ...app.forcedDirectorySelection
  ]);
  configured.forEach((path) => {
    const key = foldPath(path);
    if (!known.has(key)) {
      known.set(key, {
        path,
        fileCount: 0,
        missing: true,
        entries: []
      });
    }
  });
  return [...known.values()].sort((left, right) =>
    left.path.localeCompare(
      right.path, "zh-CN", { sensitivity: "base" }
    )
  );
}

function forcedDirectoryTreeNode(directory) {
  if (!directory.entries.length) {
    return {
      name: directory.path,
      path: directory.path,
      entries: [],
      files: [],
      folders: new Map()
    };
  }
  const root = buildFileTree(directory.entries);
  return [...root.folders.values()].find(
    (folder) => foldPath(folder.path) === foldPath(directory.path)
  ) || {
    name: directory.path,
    path: directory.path,
    entries: directory.entries,
    files: [],
    folders: new Map()
  };
}

function forcedDirectoryContentRows(node, topLevelDirectory) {
  const rows = [];
  const appendContents = (parent, depth) => {
    [...parent.folders.values()]
      .sort((left, right) => left.name.localeCompare(
        right.name, "zh-CN", { sensitivity: "base" }
      ))
      .forEach((folder) => {
        const state = folderExpansionState(folder, {
          expandedFolders: app.forcedDirectoryExpandedFolders,
          onToggle: renderForcedDirectories
        });
        const item = row([
          document.createElement("td"),
          folderPathCell(folder, depth, state),
          `随 ${topLevelDirectory}/ 一起强制同步`,
          String(folder.entries.length)
        ]);
        item.className = "file-tree-folder file-tree-child";
        rows.push(item);
        if (state.expanded) appendContents(folder, depth + 1);
      });
    parent.files.sort(compareFilePath).forEach((file) => {
      const item = row([
        document.createElement("td"),
        treeFilePathCell(file.path, depth),
        "随一级目录强制同步",
        "1"
      ]);
      item.className = "file-tree-file file-tree-child";
      rows.push(item);
    });
  };
  appendContents(node, 1);
  return rows;
}

function renderForcedFiles() {
  const allFiles = forcedFileCandidates();
  const query = byId("forced-file-search").value.trim().toLocaleLowerCase("zh-CN");
  const files = allFiles
    .filter((file) => !query
      || file.path.toLocaleLowerCase("zh-CN").includes(query))
    .sort(compareFilePath);
  byId("forced-file-empty").textContent = query
    ? "没有符合搜索条件的文件"
    : "请先读取或扫描整合包目录";
  const rows = fileTreeRows(
    files,
    forcedFolderRow,
    forcedFileRow,
    {
      expandedFolders: app.forcedFileExpandedFolders,
      expandAll: Boolean(query),
      onToggle: renderForcedFiles
    }
  );
  setRows("forced-file-table", "forced-file-empty", rows);
  updateForcedFileCount(files, allFiles);
}

function forcedFileCandidates() {
  const previewFiles = app.project?.preview?.files
    || app.sourceFiles?.files
    || [];
  const known = new Map(previewFiles.map(
    (file) => [foldPath(file.path), file]
  ));
  (app.project?.forcedSyncFiles || []).forEach((path) => {
    if (!known.has(foldPath(path))) {
      known.set(foldPath(path), {
        path,
        size: null,
        policy: "MISSING"
      });
    }
  });
  return [...known.values()].sort(compareFilePath);
}

function forcedFolderRow(node, depth, treeState) {
  const control = forcedTreeSelectionCell(node.entries, (checked, files) => {
    setPathsSelected(app.forcedFileSelection, files, checked);
    renderForcedFiles();
  }, node.path
    ? `选择 ${node.path}/ 中全部可设置的文件`
    : "选择当前列表中全部可设置的文件");
  const available = node.entries.filter(forcedFileSelectable);
  const selected = available.filter(
    (file) => pathSelected(app.forcedFileSelection, file.path)
  ).length;
  const existing = node.entries.filter((file) => file.policy !== "MISSING");
  const entirelyDirectoryForced = existing.length > 0
    && existing.every((file) => insideForcedDirectory(file.path));
  const policy = available.length === 0
    ? entirelyDirectoryForced ? "已由目录强制" : "无可选文件"
    : selected === 0
      ? `${available.length} 个可选文件`
      : `已选 ${selected}/${available.length}`;
  const item = row([
    control,
    folderPathCell(node, depth, treeState),
    policy,
    formatBytes(node.entries.reduce(
      (sum, file) => sum + Number(file.size || 0), 0
    ))
  ]);
  item.className = "file-tree-folder";
  return item;
}

function forcedFileRow(file, depth) {
  const directoryForced = insideForcedDirectory(file.path);
  const missing = file.policy === "MISSING";
  const control = document.createElement("td");
  const checkbox = selectionCheckbox(
    directoryForced || pathSelected(app.forcedFileSelection, file.path),
    directoryForced || missing,
    directoryForced
      ? "该文件已由强制同步目录覆盖"
      : missing
        ? "源目录中已找不到该文件；清空旧选择后保存即可移除规则"
        : "玩家不能豁免选中的文件"
  );
  checkbox.classList.add("forced-file-check");
  checkbox.dataset.path = file.path;
  checkbox.addEventListener("change", () => {
    setPathSelected(app.forcedFileSelection, file.path, checkbox.checked);
    renderForcedFiles();
  });
  control.append(checkbox);
  const fileForced = pathSelected(app.forcedFileSelection, file.path);
  const policy = directoryForced
    ? "目录强制"
    : fileForced ? "单文件强制"
      : missing ? "源文件缺失"
        : file.policy === "DEFAULT" ? "默认" : "普通托管";
  const filePath = treeFilePathCell(file.path, depth);
  const item = row([
    control,
    filePath,
    policy,
    file.size === null ? "--" : formatBytes(file.size)
  ]);
  item.className = "file-tree-file";
  return item;
}

function updateForcedFileCount(visibleFiles = [], allFiles = forcedFileCandidates()) {
  const count = app.forcedFileSelection.size;
  byId("forced-file-count").textContent =
    count === 0
      ? "未单独强制任何文件"
      : `已选择 ${count} 个单独强制同步文件`;
  byId("forced-file-visible-count").textContent = visibleFiles.length === allFiles.length
    ? `${allFiles.length} 个文件，文件夹可整组选择`
    : `显示 ${visibleFiles.length} / ${allFiles.length} 个文件`;
  applyForcedSelectionState(byId("forced-file-select-all"), visibleFiles);
}

function renderReleases() {
  const releases = app.project?.releases || [];
  byId("release-count-label").textContent = `${releases.length} 条记录`;
  const rows = releases.map((release) => {
    const rollback = actionButton("回滚", () => openRollback(release));
    const changelog = textCell(release.changelog || "未填写");
    changelog.title = release.changelog || "未填写";
    return row([
      `#${release.sequence}`,
      release.displayVersion,
      formatDate(release.createdAt),
      changelog,
      rollback
    ]);
  });
  setRows("release-table", "release-empty", rows);
}

function renderPrograms() {
  const programs = app.project?.playerPrograms || [];
  byId("program-count-label").textContent = `${programs.length} 个版本`;
  const rows = programs.map((program) => row([
    program.version,
    program.platform,
    formatDate(program.createdAt),
    hashCell(program.manifestSha256)
  ]));
  setRows("program-table", "program-empty", rows);
}

function renderInstanceReleases() {
  const releases = app.project?.releases || [];
  [byId("deployment-form"), byId("instance-form")].forEach((form) => {
    const select = form.elements.releaseId;
    select.replaceChildren();
    releases.forEach((release, index) => {
      const item = option(
        release.releaseId,
        `${release.displayVersion} · #${release.sequence}`
      );
      item.selected = index === 0;
      select.append(item);
    });
    select.disabled = releases.length === 0;
  });
}

function renderSettings() {
  if (!app.state) return;
  const form = byId("settings-form");
  setFormValue(form, "httpHost", app.state.settings.httpHost);
  setFormValue(form, "httpPort", app.state.settings.httpPort);
  setFormValue(form, "webHost", app.state.settings.webHost);
  setFormValue(form, "webPort", app.state.settings.webPort);
  setFormValue(form, "dataDirectory", app.state.dataDirectory);
  setFormValue(form, "settingsFile", app.state.settingsFile);
  const webPort = app.state.settings.webPort;
  byId("ssh-tunnel-command").textContent =
    `ssh -N -L ${webPort}:127.0.0.1:${webPort} 用户名@您的服务器地址`;
}

function bindEvents() {
  bindAuthentication();
  byId("theme-toggle").addEventListener("click", () => {
    const current = document.documentElement.dataset.theme === "light"
      ? "light"
      : "dark";
    applyTheme(current === "light" ? "dark" : "light", true);
  });
  document.querySelectorAll(".nav-button").forEach((button) => {
    button.addEventListener("click", async () => {
      const needsSourceFiles = button.dataset.view === "publish";
      if (needsSourceFiles && app.project && !app.sourceFiles) {
        await runBusy("正在读取整合包文件", async () => {
          await loadSourceFiles();
          showView(button.dataset.view);
        });
        return;
      }
      showView(button.dataset.view);
    });
  });
  byId("project-select").addEventListener("change", async (event) => {
    await runBusy("正在切换项目", async () => {
      await loadProject(event.target.value);
      if (app.view === "publish") await loadSourceFiles();
      renderDashboard();
    });
  });
  byId("refresh-button").addEventListener("click", () =>
    runBusy("正在刷新", async () => {
      await refreshState();
      toast("数据已刷新");
    })
  );
  byId("service-start").addEventListener("click", async () => {
    const accepted = await ask(
      "启动下载服务",
      `将在 ${app.state.publicService.address} 启动只读文件服务。`,
      "启动服务"
    );
    if (!accepted) return;
    await runBusy("正在启动 HTTP 文件服务", async () => {
      await api("/api/public-service/start", { method: "POST", body: {} });
      await refreshState();
      toast("HTTP 文件服务已启动");
    });
  });
  byId("service-stop").addEventListener("click", async () => {
    const accepted = await ask(
      "停止下载服务",
      "玩家在服务停止期间无法检查或下载更新。",
      "停止服务",
      true
    );
    if (!accepted) return;
    await runBusy("正在停止 HTTP 文件服务", async () => {
      await api("/api/public-service/stop", { method: "POST", body: {} });
      await refreshState();
      toast("HTTP 文件服务已停止");
    });
  });

  bindProjectCreate();
  bindErrorDialog();
  bindProjectForm();
  bindPersonalizationForm();
  bindPathPickers();
  bindSourceFiles();
  bindPublish();
  bindPrograms();
  bindDistribution();
  bindDeployment();
  bindInstance();
  bindSettings();
  bindRollback();
}

function initializeTheme() {
  let theme = document.documentElement.dataset.theme === "light"
    ? "light"
    : "dark";
  try {
    theme = localStorage.getItem("dfs-admin-theme") === "light"
      ? "light"
      : "dark";
  } catch (_) {
    // Local storage may be unavailable in hardened browsers; dark remains safe.
  }
  applyTheme(theme, false);
}

function applyTheme(theme, persist) {
  const normalized = theme === "light" ? "light" : "dark";
  document.documentElement.dataset.theme = normalized;
  const toggle = byId("theme-toggle");
  const light = normalized === "light";
  toggle.textContent = light ? "☾" : "☀";
  toggle.title = light ? "切换到夜间模式" : "切换到白天模式";
  toggle.setAttribute("aria-label", toggle.title);
  toggle.setAttribute("aria-pressed", String(light));
  const authToggle = byId("auth-theme-toggle");
  authToggle.textContent = light ? "☾" : "☀";
  authToggle.title = light ? "切换到夜间模式" : "切换到白天模式";
  authToggle.setAttribute("aria-label", authToggle.title);
  if (!persist) return;
  try {
    localStorage.setItem("dfs-admin-theme", normalized);
  } catch (_) {
    // The current page can still switch themes even when persistence is blocked.
  }
}

function renderAuthIdentity() {
  byId("account-username").textContent = app.auth?.username || "管理员";
}

function bindErrorDialog() {
  const dialog = byId("error-dialog");
  dialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    dialog.close("cancel");
  });
  dialog.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    event.preventDefault();
    dialog.close("cancel");
  });
}

function bindDeployment() {
  const form = byId("deployment-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      outputDirectory: textValue(data, "outputDirectory"),
      platform: textValue(data, "platform"),
      releaseId: textValue(data, "releaseId")
    };
    const selected = app.project.releases.find(
      (release) => release.releaseId === payload.releaseId
    );
    const accepted = await ask(
      "生成首次部署包",
      `整合包基线：${selected?.displayVersion || payload.releaseId}\n`
        + `输出父目录：${payload.outputDirectory}`,
      "开始生成"
    );
    if (!accepted) return;
    await runBusy("正在重建签名玩家端与部署基线", async () => {
      const result = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/deployment`,
        { method: "POST", body: payload }
      );
      toast(`首次部署包已生成：${result.outputDirectory}`);
    });
  });
}

function bindSourceFiles() {
  byId("source-file-search").addEventListener("input", renderSourceFiles);
  byId("source-file-select-all").addEventListener("change", (event) => {
    setPathsSelected(
      app.sourceFileSelection,
      visibleSourceFiles(),
      event.target.checked
    );
    renderSourceFiles();
  });
  byId("clear-source-selection").addEventListener("click", () => {
    app.sourceFileSelection.clear();
    renderSourceFiles();
  });
  byId("remove-selected-source-files").addEventListener(
    "click", removeSelectedSourceFiles
  );
  byId("reload-source-files").addEventListener("click", () =>
    runBusy("正在刷新源文件", async () => {
      await loadSourceFiles();
      toast("源文件列表已刷新");
    })
  );

  const dialog = byId("source-add-dialog");
  const form = byId("source-add-form");
  const fileInput = byId("source-upload-input");
  const dropzone = byId("source-dropzone");
  const serverSourceInput = form.elements.serverSourcePath;
  byId("open-source-add").addEventListener("click", async () => {
    try {
      if (!app.sourceFiles) await loadSourceFiles();
      form.reset();
      app.pendingUploads = [];
      app.uploadTargetDirectory = null;
      app.uploadTargetExpandedFolders.clear();
      app.sourceUploadCancelled = false;
      renderUploadTargetTree();
      updateUploadSelection();
      resetUploadProgress();
      dialog.showModal();
    } catch (error) {
      toast(error.message, true);
    }
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (event.submitter?.value === "cancel") {
      app.sourceUploadCancelled = true;
      abortActiveUploads();
      dialog.close();
    }
  });
  dialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    app.sourceUploadCancelled = true;
    abortActiveUploads();
    dialog.close();
  });
  dialog.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    event.preventDefault();
    app.sourceUploadCancelled = true;
    abortActiveUploads();
    dialog.close();
  });
  dialog.addEventListener("close", () => {
    if (app.activeUploads.size === 0) return;
    app.sourceUploadCancelled = true;
    abortActiveUploads();
  });
  byId("choose-source-upload").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    app.pendingUploads = [...fileInput.files];
    updateUploadSelection();
  });
  serverSourceInput.addEventListener("input", updateSourceAddActions);
  dropzone.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      fileInput.click();
    }
  });
  ["dragenter", "dragover"].forEach((name) => {
    dropzone.addEventListener(name, (event) => {
      event.preventDefault();
      dropzone.classList.add("dragging");
    });
  });
  ["dragleave", "drop"].forEach((name) => {
    dropzone.addEventListener(name, (event) => {
      event.preventDefault();
      dropzone.classList.remove("dragging");
    });
  });
  dropzone.addEventListener("drop", (event) => {
    app.pendingUploads = [...event.dataTransfer.files];
    updateUploadSelection();
  });

  byId("upload-source-files").addEventListener("click", uploadSelectedSources);
  byId("import-server-source").addEventListener("click", importServerSource);
}

function updateUploadSelection() {
  const files = app.pendingUploads;
  byId("source-upload-selection").textContent = files.length === 0
    ? "或从当前电脑选择"
    : files.length === 1
      ? `${files[0].name} · ${formatBytes(files[0].size)}`
      : `已选择 ${files.length} 个文件 · ${formatBytes(
        files.reduce((sum, file) => sum + file.size, 0)
      )}`;
  updateSourceAddActions();
}

function renderUploadTargetTree() {
  const container = byId("source-target-tree");
  container.replaceChildren();
  const files = app.sourceFiles?.files || [];
  const root = buildFileTree(files);
  container.append(uploadTargetRow({
    path: "",
    name: "要管理的文件目录（根目录）",
    count: files.length,
    depth: 0,
    root: true
  }));

  const appendFolder = (folder, depth) => {
    const expanded = pathSelected(
      app.uploadTargetExpandedFolders, folder.path
    );
    const nested = [...folder.folders.values()].sort(
      (left, right) => left.name.localeCompare(
        right.name, "zh-CN", { sensitivity: "base" }
      )
    );
    container.append(uploadTargetRow({
      path: folder.path,
      name: `${folder.name}/`,
      count: folder.entries.length,
      depth,
      expanded,
      hasChildren: nested.length > 0,
      onToggle() {
        setPathSelected(
          app.uploadTargetExpandedFolders, folder.path, !expanded
        );
        renderUploadTargetTree();
      }
    }));
    if (expanded) nested.forEach((child) => appendFolder(child, depth + 1));
  };
  [...root.folders.values()]
    .sort((left, right) => left.name.localeCompare(
      right.name, "zh-CN", { sensitivity: "base" }
    ))
    .forEach((folder) => appendFolder(folder, 1));

  byId("source-target-selection").textContent =
    app.uploadTargetDirectory === null
      ? "尚未选择"
      : app.uploadTargetDirectory
        ? `保存到 ${app.uploadTargetDirectory}/`
        : "保存到根目录";
  updateSourceAddActions();
}

function uploadTargetRow({
  path, name, count, depth, root = false,
  expanded = false, hasChildren = false, onToggle = null
}) {
  const item = document.createElement("div");
  item.className = "source-target-row";
  item.style.setProperty("--tree-depth", String(depth));
  item.setAttribute("role", "treeitem");
  item.setAttribute("aria-selected", String(
    app.uploadTargetDirectory !== null
      && foldPath(app.uploadTargetDirectory) === foldPath(path)
  ));

  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "source-target-toggle";
  toggle.textContent = hasChildren ? (expanded ? "▾" : "▸") : "";
  toggle.disabled = !hasChildren;
  toggle.setAttribute("aria-label", expanded ? "收起子目录" : "展开子目录");
  toggle.setAttribute("aria-expanded", String(expanded));
  toggle.addEventListener("click", (event) => {
    event.stopPropagation();
    onToggle?.();
  });

  const icon = document.createElement("span");
  icon.className = `tree-entry-icon ${root ? "root" : "folder"}`;
  icon.setAttribute("aria-hidden", "true");
  const label = document.createElement("strong");
  label.textContent = name;
  const details = document.createElement("span");
  details.textContent = `${count} 个文件`;
  item.append(toggle, icon, label, details);
  item.addEventListener("click", () => {
    app.uploadTargetDirectory = path;
    renderUploadTargetTree();
  });
  return item;
}

function updateSourceAddActions() {
  const uploading = app.activeUploads.size > 0;
  const targetSelected = app.uploadTargetDirectory !== null;
  byId("upload-source-files").disabled = uploading
    || !targetSelected
    || app.pendingUploads.length === 0;
  const serverPath = String(
    byId("source-add-form").elements.serverSourcePath?.value || ""
  ).trim();
  byId("import-server-source").disabled = uploading
    || !targetSelected
    || !serverPath;
}

function resetUploadProgress() {
  byId("source-upload-progress").hidden = true;
  byId("source-upload-meter").value = 0;
  byId("source-upload-percent").textContent = "0%";
  byId("source-upload-label").textContent = "准备上传";
}

async function uploadSelectedSources() {
  if (!app.project || app.pendingUploads.length === 0) {
    toast("请先选择要上传的文件", true);
    return;
  }
  if (app.uploadTargetDirectory === null) {
    toast("请先在目录树中选择文件保存位置", true);
    return;
  }
  const form = byId("source-add-form");
  const targetDirectory = app.uploadTargetDirectory;
  const overwrite = form.elements.overwrite.checked;
  const files = [...app.pendingUploads];
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
  let completedBytes = 0;
  const progress = byId("source-upload-progress");
  const uploadButton = byId("upload-source-files");
  const importButton = byId("import-server-source");
  progress.hidden = false;
  uploadButton.disabled = true;
  importButton.disabled = true;
  app.sourceUploadCancelled = false;
  try {
    for (let index = 0; index < files.length; index += 1) {
      const file = files[index];
      const targetPath = joinSourcePath(targetDirectory, file.name);
      byId("source-upload-label").textContent =
        `正在上传 ${index + 1}/${files.length} · ${file.name}`;
      await uploadSourceFile(file, targetPath, overwrite, false, (loaded) => {
        const current = completedBytes + loaded;
        const percent = totalBytes === 0 ? 100
          : Math.min(100, Math.round(current * 100 / totalBytes));
        byId("source-upload-meter").value = percent;
        byId("source-upload-percent").textContent = `${percent}%`;
      });
      completedBytes += file.size;
    }
    byId("source-upload-label").textContent = "正在扫描整批文件";
    await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/scan`,
      { method: "POST", body: {} }
    );
    await loadProject(app.project.id);
    await loadSourceFiles();
    byId("source-add-dialog").close();
    showView("publish");
    toast(`${files.length} 个文件已加入整合包目录`);
  } catch (error) {
    if (!app.sourceUploadCancelled) toast(error.message, true);
    try {
      await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/scan`,
        { method: "POST", body: {} }
      );
    } catch (_ignored) {
      // Keep the original upload error visible; a later manual scan can recover.
    }
    await loadProject(app.project.id);
    await loadSourceFiles();
  } finally {
    updateSourceAddActions();
  }
}

function uploadSourceFile(file, targetPath, overwrite, refreshPreview, onProgress) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    app.activeUploads.add(request);
    updateSourceAddActions();
    let settled = false;
    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      app.activeUploads.delete(request);
      updateSourceAddActions();
      callback(value);
    };
    request.open("PUT",
      `/api/projects/${encodeURIComponent(app.project.id)}/files/upload`
        + `?path=${encodeURIComponent(targetPath)}`
        + `&overwrite=${overwrite}`
        + `&refreshPreview=${refreshPreview}`);
    request.setRequestHeader("Accept", "application/json");
    request.setRequestHeader("Content-Type", "application/octet-stream");
    request.setRequestHeader("X-DFS-Token", app.token);
    request.upload.addEventListener("progress", (event) => {
      if (event.lengthComputable) onProgress(event.loaded);
    });
    request.addEventListener("load", () => {
      let result = null;
      try {
        result = JSON.parse(request.responseText || "null");
      } catch (_ignored) {
        // The status fallback below is more useful than a JSON parser error.
      }
      if (request.status >= 200 && request.status < 300) finish(resolve, result);
      else finish(reject,
        new Error(result?.message || `上传失败：HTTP ${request.status}`));
    });
    request.addEventListener("error", () =>
      finish(reject, new Error("上传连接中断")));
    request.addEventListener("abort", () =>
      finish(reject, new Error("上传已取消")));
    request.send(file);
  });
}

function abortActiveUploads() {
  [...app.activeUploads].forEach((request) => request.abort());
}

async function importServerSource() {
  if (!app.project) return;
  const form = byId("source-add-form");
  const data = new FormData(form);
  const sourcePath = textValue(data, "serverSourcePath");
  if (!sourcePath) {
    toast("请先选择服务器上的文件", true);
    return;
  }
  if (app.uploadTargetDirectory === null) {
    toast("请先在目录树中选择文件保存位置", true);
    return;
  }
  const payload = {
    sourcePath,
    targetDirectory: app.uploadTargetDirectory,
    overwrite: form.elements.overwrite.checked
  };
  await runBusy("正在导入并扫描源文件", async () => {
    await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/files/import`,
      { method: "POST", body: payload }
    );
    await loadProject(app.project.id);
    await loadSourceFiles();
    byId("source-add-dialog").close();
    showView("publish");
    toast("服务器文件已加入整合包目录");
  });
}

async function removeSourceFile(file) {
  if (!app.project) return;
  const action = await chooseSourceRemoval(file);
  if (!action) return;
  const position = capturePublishPosition();
  await runBusy("正在归档并移除源文件", async () => {
    const result = await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/files/remove`,
      { method: "POST", body: { path: file.path, action } }
    );
    await loadProject(app.project.id);
    await loadSourceFiles();
    showView("publish");
    restorePublishPosition(position);
    const playerResult = action === "RELEASE"
      ? "玩家本地将保留该文件"
      : "玩家更新时将移除该文件";
    toast(`${playerResult}；源文件已归档到 ${result.archivedPreviousFile}`);
  });
}

async function removeSelectedSourceFiles() {
  if (!app.project || !app.sourceFiles) return;
  const files = (app.sourceFiles.files || []).filter(
    (file) => pathSelected(app.sourceFileSelection, file.path)
  );
  if (files.length === 0) {
    toast("请先选择要移除的整合包文件", true);
    return;
  }
  const action = await chooseBulkSourceRemoval(files);
  if (!action) return;
  const position = capturePublishPosition();
  await runBusy(`正在归档并移除 ${files.length} 个文件`, async () => {
    await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/files/remove-batch`,
      {
        method: "POST",
        body: {
          paths: files.map((file) => file.path),
          action
        }
      }
    );
    app.sourceFileSelection.clear();
    await loadProject(app.project.id);
    await loadSourceFiles();
    showView("publish");
    restorePublishPosition(position);
    const playerResult = action === "RELEASE"
      ? "玩家本地将保留这些文件"
      : "玩家更新时将移除这些文件";
    toast(`已移除 ${files.length} 个整合包文件；${playerResult}`);
  });
}

function chooseBulkSourceRemoval(files) {
  const dialog = byId("source-bulk-remove-dialog");
  const release = byId("source-bulk-release-action");
  const forcedCount = files.filter((file) => file.forcedByDirectory).length;
  release.disabled = forcedCount > 0;
  release.title = forcedCount > 0
    ? `所选文件中有 ${forcedCount} 个位于强制同步目录，只能从玩家端删除`
    : "玩家本地已有副本将退出管理并保留";
  byId("source-bulk-remove-message").textContent =
    `已选择 ${files.length} 个文件。\n\n`
      + "所有源文件会先归档，再从整合包目录移出。"
      + (forcedCount > 0
        ? `其中 ${forcedCount} 个位于强制同步目录，不能选择“放弃管理并保留”。`
        : "请选择玩家更新到下一版本时如何处理。");
  dialog.returnValue = "cancel";
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener("close", () => {
      resolve(["DELETE", "RELEASE"].includes(dialog.returnValue)
        ? dialog.returnValue : null);
    }, { once: true });
  });
}

function chooseSourceRemoval(file) {
  const dialog = byId("source-remove-dialog");
  const release = byId("source-release-action");
  release.disabled = file.forcedByDirectory;
  release.title = file.forcedByDirectory
    ? "强制同步目录中的文件只能从玩家端删除"
    : "玩家本地已有副本将退出管理并保留";
  byId("source-remove-message").textContent =
    `${file.path}\n\n文件会先归档，随后从整合包目录移出。`
      + (file.published
        ? "请选择玩家更新到下一版本时如何处理。"
        : "该文件尚未发布，选择仅用于保持操作流程一致。");
  dialog.returnValue = "cancel";
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener("close", () => {
      resolve(["DELETE", "RELEASE"].includes(dialog.returnValue)
        ? dialog.returnValue : null);
    }, { once: true });
  });
}

function joinSourcePath(directory, fileName) {
  const name = String(fileName || "").replaceAll("\\", "/");
  if (!name || name.includes("/")) throw new Error("上传文件名无效");
  return directory ? `${directory}/${name}` : name;
}

function bindPathPickers() {
  const dialog = byId("path-browser-dialog");
  const form = byId("path-browser-form");
  document.querySelectorAll(".path-picker-button").forEach((button) => {
    button.addEventListener("click", async () => {
      const form = button.closest("form");
      const input = form?.elements.namedItem(button.dataset.pathName);
      if (!(input instanceof HTMLInputElement)) return;
      app.pathBrowser.targetInput = input;
      app.pathBrowser.kind = button.dataset.pathKind || "directory";
      app.pathBrowser.title = button.dataset.pathTitle || "选择服务器路径";
      app.pathBrowser.selectedPath = "";
      byId("path-browser-title").textContent = app.pathBrowser.title;
      byId("path-browser-confirm").textContent =
        app.pathBrowser.kind === "directory" ? "选择当前文件夹" : "选择文件";
      dialog.showModal();
      await loadPathBrowser(input.value);
    });
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (event.submitter?.value === "cancel") {
      dialog.close("cancel");
      return;
    }
    await loadPathBrowser(byId("path-browser-address").value);
  });
  dialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    dialog.close("cancel");
  });
  dialog.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    event.preventDefault();
    dialog.close("cancel");
  });
  byId("path-browser-up").addEventListener("click", async () => {
    if (app.pathBrowser.parentPath) {
      await loadPathBrowser(app.pathBrowser.parentPath);
    }
  });
  byId("path-browser-refresh").addEventListener("click", async () => {
    await loadPathBrowser(app.pathBrowser.currentPath);
  });
  byId("path-browser-root").addEventListener("change", async (event) => {
    if (event.target.value) await loadPathBrowser(event.target.value);
  });
  byId("path-browser-confirm").addEventListener("click", () => {
    const selected = app.pathBrowser.kind === "directory"
      ? app.pathBrowser.currentPath
      : app.pathBrowser.selectedPath;
    if (!selected || !(app.pathBrowser.targetInput instanceof HTMLInputElement)) {
      toast("请先选择一个文件", true);
      return;
    }
    app.pathBrowser.targetInput.value = selected;
    app.pathBrowser.targetInput.dispatchEvent(
      new Event("change", { bubbles: true })
    );
    dialog.close("selected");
  });
  dialog.addEventListener("close", () => {
    app.pathBrowser.targetInput = null;
  });
}

async function loadPathBrowser(path) {
  const controls = [
    byId("path-browser-go"),
    byId("path-browser-up"),
    byId("path-browser-refresh"),
    byId("path-browser-confirm")
  ];
  controls.forEach((control) => { control.disabled = true; });
  byId("path-browser-summary").textContent = "正在读取服务器目录…";
  try {
    const result = await api("/api/system/browse-path", {
      method: "POST",
      body: { kind: app.pathBrowser.kind, path: String(path || "") }
    });
    app.pathBrowser.currentPath = result.currentPath;
    app.pathBrowser.parentPath = result.parentPath;
    app.pathBrowser.selectedPath = result.selectedPath || "";
    app.pathBrowser.roots = result.roots || [];
    app.pathBrowser.entries = result.entries || [];
    app.pathBrowser.truncated = Boolean(result.truncated);
    renderPathBrowser();
  } catch (error) {
    toast(error.message, true);
  } finally {
    byId("path-browser-go").disabled = false;
    byId("path-browser-refresh").disabled = false;
    byId("path-browser-up").disabled = !app.pathBrowser.parentPath;
    byId("path-browser-confirm").disabled =
      app.pathBrowser.kind !== "directory" && !app.pathBrowser.selectedPath;
  }
}

function renderPathBrowser() {
  const browser = app.pathBrowser;
  byId("path-browser-address").value = browser.currentPath;
  byId("path-browser-up").disabled = !browser.parentPath;

  const rootSelect = byId("path-browser-root");
  rootSelect.replaceChildren();
  browser.roots.forEach((root) => {
    const option = document.createElement("option");
    option.value = root;
    option.textContent = root;
    if (foldPath(browser.currentPath).startsWith(foldPath(root))) {
      option.selected = true;
    }
    rootSelect.append(option);
  });

  const rows = browser.entries.map((entry) => {
    const tableRow = document.createElement("tr");
    if (!entry.directory && entry.path === browser.selectedPath) {
      tableRow.classList.add("selected");
    }
    const name = document.createElement("td");
    const entryButton = document.createElement("button");
    entryButton.type = "button";
    entryButton.className = "path-browser-entry";
    entryButton.title = `${entry.directory ? "文件夹" : "文件"}：${entry.name}`;
    const entryIcon = document.createElement("span");
    entryIcon.className = `path-entry-icon ${entry.directory ? "folder" : "file"}`;
    entryIcon.setAttribute("aria-hidden", "true");
    const entryName = document.createElement("span");
    entryName.textContent = entry.name;
    entryButton.append(entryIcon, entryName);
    if (entry.directory) {
      entryButton.addEventListener("click", () => loadPathBrowser(entry.path));
    } else if (entry.selectable) {
      entryButton.addEventListener("click", () => {
        browser.selectedPath = entry.path;
        renderPathBrowser();
      });
    } else {
      entryButton.classList.add("file-unavailable");
      entryButton.disabled = true;
    }
    name.append(entryButton);

    const type = document.createElement("td");
    type.textContent = entry.directory
      ? "文件夹"
      : entry.regularFile
        ? (entry.selectable || browser.kind !== "image" ? "文件" : "非图片文件")
        : "其他";
    const size = document.createElement("td");
    size.textContent = entry.directory ? "--" : formatBytes(entry.size);
    const action = document.createElement("td");
    if (entry.directory || entry.selectable) {
      const actionButton = document.createElement("button");
      actionButton.type = "button";
      actionButton.className = "table-button";
      actionButton.textContent = entry.directory ? "进入" : "选择";
      actionButton.addEventListener("click", () => {
        if (entry.directory) loadPathBrowser(entry.path);
        else {
          browser.selectedPath = entry.path;
          renderPathBrowser();
        }
      });
      action.append(actionButton);
    }
    tableRow.append(name, type, size, action);
    return tableRow;
  });
  setRows("path-browser-table", "path-browser-empty", rows);

  byId("path-browser-summary").textContent = browser.truncated
    ? `显示前 ${browser.entries.length} 项，目录内容过多`
    : `${browser.entries.length} 项`;
  const selected = browser.kind === "directory"
    ? browser.currentPath : browser.selectedPath;
  byId("path-browser-selection").textContent = selected
    ? `${browser.kind === "directory" ? "将选择文件夹" : "已选择文件"}：${selected}`
    : "请在列表中选择一个文件";
  byId("path-browser-confirm").disabled =
    browser.kind !== "directory" && !browser.selectedPath;
}

function bindProjectCreate() {
  const dialog = byId("create-project-dialog");
  const form = byId("create-project-form");
  byId("open-create-project").addEventListener("click", () => {
    form.reset();
    setFormValue(form, "subtitle", "Minecraft 整合包更新");
    setFormValue(form, "brandName", "梦鱼服");
    setFormValue(form, "brandEnglishName", "DreamingFish");
    dialog.showModal();
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (event.submitter?.value === "cancel") {
      dialog.close();
      return;
    }
    if (!form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      id: textValue(data, "id"),
      displayName: textValue(data, "displayName"),
      sourceDirectory: textValue(data, "sourceDirectory"),
      publicBaseUrl: textValue(data, "publicBaseUrl"),
      forcedSyncDirectories: directoryList(
        textValue(data, "forcedSyncDirectories")
      ),
      forcedSyncFiles: [],
      productName: textValue(data, "productName")
        || textValue(data, "displayName"),
      subtitle: textValue(data, "subtitle"),
      serverAddress: textValue(data, "serverAddress"),
      brandName: textValue(data, "brandName"),
      brandEnglishName: textValue(data, "brandEnglishName")
    };
    await runBusy("正在创建项目", async () => {
      const created = await api("/api/projects", {
        method: "POST",
        body: payload
      });
      dialog.close();
      await refreshState(created.id);
      await loadSourceFiles();
      showView("publish");
      toast(`项目 ${created.displayName} 已创建，请添加文件并完成首次扫描`);
    });
  });
}

function bindProjectForm() {
  const form = byId("project-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      displayName: textValue(data, "displayName"),
      sourceDirectory: textValue(data, "sourceDirectory"),
      publicBaseUrl: textValue(data, "publicBaseUrl"),
      forcedSyncDirectories: directoryList(
        textValue(data, "forcedSyncDirectories")
      ),
      forcedSyncFiles: [...app.forcedFileSelection]
    };
    await runBusy("正在保存项目设置", async () => {
      await api(`/api/projects/${encodeURIComponent(app.project.id)}`, {
        method: "PUT",
        body: payload
      });
      await refreshState(app.project.id);
      toast("项目设置已保存");
    });
  });
}

function bindPersonalizationForm() {
  const form = byId("personalization-form");
  const coverInput = byId("cover-upload-input");
  bindColorPair(form, "accentColor");
  bindColorPair(form, "secondaryAccentColor");
  byId("choose-cover-upload").addEventListener("click", () => coverInput.click());
  coverInput.addEventListener("change", () => {
    selectPendingCover(coverInput.files?.[0] || null);
  });
  const musicInput = byId("music-upload-input");
  byId("choose-music-upload").addEventListener("click", () => musicInput.click());
  musicInput.addEventListener("change", async () => {
    const file = musicInput.files?.[0];
    musicInput.value = "";
    if (!file || !app.project) return;
    if (!file.name.toLowerCase().endsWith(".mp3")) {
      showErrorDialog("只能上传 MP3 文件。");
      return;
    }
    if (file.size > 20 * 1024 * 1024) {
      showErrorDialog("单首音乐不能超过 20 MiB。");
      return;
    }
    await runBusy("正在上传音乐", async () => {
      const titleInput = byId("music-upload-title");
      const title = titleInput.value.trim() || file.name.replace(/\.mp3$/i, "");
      const response = await fetch(`/api/projects/${encodeURIComponent(app.project.id)}/music/upload?fileName=${encodeURIComponent(file.name)}&title=${encodeURIComponent(title)}`, {
        method: "PUT", headers: { "Accept": "application/json", "Content-Type": "audio/mpeg", "X-DFS-Token": app.token }, body: file
      });
      const data = await response.json().catch(() => null);
      if (!response.ok) throw new Error(data?.message || `音乐上传失败：HTTP ${response.status}`);
      await refreshState(app.project.id);
      titleInput.value = "";
      toast(`已添加音乐：${title}`);
    });
  });
  form.addEventListener("input", updatePlayerPreview);
  form.addEventListener("change", (event) => {
    if (event.target === form.elements.removeCover
        && form.elements.removeCover.checked) {
      clearPendingCover();
    }
    updatePlayerPreview();
  });
  byId("add-player-page").addEventListener("click", () => {
    const pages = readPlayerPageEditor();
    const ids = new Set(pages.map((page) => page.id));
    let suffix = pages.length + 1;
    let id = `page-${suffix}`;
    while (ids.has(id)) id = `page-${++suffix}`;
    pages.push({
      id, navigationLabel: "新页面", announcementPage: false,
      eyebrow: "", title: "新页面", lead: "", markdown: "", articles: []
    });
    byId("legacy-news-note").hidden = true;
    renderPlayerPageEditor(pages);
    byId("player-page-editor").lastElementChild?.scrollIntoView({
      behavior: "smooth", block: "nearest"
    });
  });
  byId("export-player-pages").addEventListener("click", exportPlayerPages);
  byId("copy-player-pages-ai-prompt").addEventListener("click", copyPlayerPagesAiPrompt);
  byId("import-player-pages").addEventListener("click", () => byId("import-player-pages-input").click());
  byId("import-player-pages-input").addEventListener("change", async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (file) await importPlayerPages(file);
  });
  const previewFrame = byId("player-preview-frame");
  previewFrame.addEventListener("load", () => {
    app.playerPreviewReady = true;
    resizePlayerPreview();
    updatePlayerPreview();
  });
  window.addEventListener("message", (event) => {
    if (event.origin !== location.origin
        || event.source !== previewFrame.contentWindow
        || event.data?.type !== "dfs-player-preview-ready") return;
    app.playerPreviewReady = true;
    updatePlayerPreview();
  });
  window.addEventListener("resize", () => requestAnimationFrame(resizePlayerPreview));
  resizePlayerPreview();
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      productName: textValue(data, "productName"),
      brandName: textValue(data, "brandName"),
      brandEnglishName: textValue(data, "brandEnglishName"),
      subtitle: textValue(data, "subtitle"),
      serverAddress: textValue(data, "serverAddress"),
      accentColor: textValue(data, "accentColorText"),
      secondaryAccentColor: textValue(data, "secondaryAccentColorText"),
      removeCover: form.elements.removeCover.checked,
      newsArticles: [],
      customPage: null,
      contentPages: readPlayerPageEditor()
    };
    await runBusy("正在保存玩家端个性化设置", async () => {
      await api(`/api/projects/${encodeURIComponent(app.project.id)}`, {
        method: "PUT",
        body: payload
      });
      if (app.pendingCoverFile) {
        await uploadCoverFile(app.pendingCoverFile);
      }
      await refreshState(app.project.id);
      toast("已保存；文字、配色和页面将在玩家下次启动时生效");
    });
  });
}

async function uploadCoverFile(file) {
  const response = await fetch(
    `/api/projects/${encodeURIComponent(app.project.id)}/cover`,
    {
      method: "PUT",
      headers: {
        "Accept": "application/json",
        "Content-Type": "application/octet-stream",
        "X-DFS-Token": app.token
      },
      body: file
    }
  );
  const contentType = response.headers.get("Content-Type") || "";
  const data = contentType.includes("application/json")
    ? await response.json()
    : null;
  if (!response.ok) {
    throw new Error(data?.message || `背景图片上传失败：HTTP ${response.status}`);
  }
  return data;
}

function bindPublish() {
  byId("release-all-button").addEventListener("click", () => {
    setAllRemovalActions("RELEASE");
  });
  byId("delete-all-button").addEventListener("click", () => {
    setAllRemovalActions("DELETE");
  });
  byId("forced-directory-select-all").addEventListener("change", (event) => {
    forcedDirectoryCandidates()
      .filter((directory) => !directory.missing)
      .forEach((directory) => setPathSelected(
        app.forcedDirectorySelection,
        directory.path,
        event.target.checked
      ));
    renderForcedDirectories();
  });
  byId("clear-forced-directories").addEventListener("click", () => {
    app.forcedDirectorySelection.clear();
    renderForcedDirectories();
  });
  byId("save-forced-directories").addEventListener("click", async () => {
    if (!app.project) return;
    await runBusy("正在保存强制同步目录设置", async () => {
      await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/forced-directories`,
        {
          method: "POST",
          body: { directories: [...app.forcedDirectorySelection] }
        }
      );
      await refreshState(app.project.id);
      showView("publish");
      toast("强制同步目录设置已保存");
    });
  });
  byId("forced-file-search").addEventListener("input", renderForcedFiles);
  byId("forced-file-select-all").addEventListener("change", (event) => {
    setPathsSelected(
      app.forcedFileSelection,
      visibleForcedFiles().filter(forcedFileSelectable),
      event.target.checked
    );
    renderForcedFiles();
  });
  byId("clear-forced-files").addEventListener("click", () => {
    app.forcedFileSelection.clear();
    renderForcedFiles();
  });
  byId("save-forced-files").addEventListener("click", async () => {
    if (!app.project) return;
    await runBusy("正在保存单文件强制同步设置", async () => {
      await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/forced-files`,
        {
          method: "POST",
          body: { files: [...app.forcedFileSelection] }
        }
      );
      await refreshState(app.project.id);
      showView("publish");
      toast("单文件强制同步设置已保存");
    });
  });
  byId("scan-button").addEventListener("click", async () => {
    if (!app.project) return;
    await runBusy("正在扫描并计算差异", async () => {
      await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/scan`,
        { method: "POST", body: {} }
      );
      await refreshState(app.project.id);
      showView("publish");
      toast(app.project.preview?.changes.length === 0
        ? "扫描完成，本次没有修改"
        : "扫描完成");
    });
  });
  const form = byId("publish-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    if (!app.project.preview) {
      toast("请先扫描源目录", true);
      return;
    }
    const removals = app.project.preview.changes.filter(
      (change) => change.kind === "REMOVED"
    );
    if (removals.some((change) => !change.removalAction)) {
      toast("请先决定每个移除文件是删除还是放弃管理", true);
      return;
    }
    const data = new FormData(form);
    const payload = {
      displayVersion: textValue(data, "displayVersion"),
      minimumPlayerVersion: textValue(data, "minimumPlayerVersion"),
      changelog: textValue(data, "changelog")
    };
    const accepted = await ask(
      "创建不可变发布",
      `显示版本：${payload.displayVersion}\n`
        + `文件变更：${app.project.preview.changes.length} 项\n`
        + `从玩家端删除：${removals.filter(
          (change) => change.removalAction === "DELETE"
        ).length} 项\n`
        + `放弃管理并保留：${removals.filter(
          (change) => change.removalAction === "RELEASE"
        ).length} 项\n`
        + "发布后该版本内容不可修改。",
      "确认发布"
    );
    if (!accepted) return;
    await runBusy("正在签名并发布整合包", async () => {
      if (removals.length > 0) {
        await api(
          `/api/projects/${encodeURIComponent(app.project.id)}/removals`,
          {
            method: "POST",
            body: {
              decisions: removals.map((change) => ({
                path: change.path,
                action: change.removalAction
              }))
            }
          }
        );
      }
      const release = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/publish`,
        { method: "POST", body: payload }
      );
      form.reset();
      form.elements.minimumPlayerVersion.value = "0.1.14";
      await refreshState(app.project.id);
      showPublishedToast(release, `版本 ${release.displayVersion} 已发布`);
    });
  });
}

function setAllRemovalActions(action) {
  const preview = app.project?.preview;
  if (!preview) return;
  preview.changes
    .filter((change) => change.kind === "REMOVED")
    .forEach((change) => {
      change.removalAction = action === "RELEASE"
        && insideForcedDirectory(change.path)
        ? "DELETE" : action;
    });
  renderPreview();
}

function bindPrograms() {
  const form = byId("program-form");
  form.elements.platform.addEventListener("change", async () => {
    if (!app.project) return;
    await runBusy("正在读取玩家端程序", async () => {
      await loadProject(app.project.id, form.elements.platform.value);
    });
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      platform: textValue(data, "platform"),
      sourceDirectory: textValue(data, "sourceDirectory"),
      minimumBootstrapVersion:
        textValue(data, "minimumBootstrapVersion")
    };
    const accepted = await ask(
      "发布玩家端程序",
      `${payload.platform}\n将从所选目录自动读取玩家端版本。`,
      "确认发布"
    );
    if (!accepted) return;
    await runBusy("正在校验并签名玩家端程序", async () => {
      const program = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/programs`,
        { method: "POST", body: payload }
      );
      await loadProject(app.project.id, payload.platform);
      toast(`玩家端 ${program.version} 已发布`);
    });
  });
}

function bindDistribution() {
  const form = byId("distribution-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    const data = new FormData(form);
    const outputDirectory = textValue(data, "outputDirectory");
    const accepted = await ask(
      "导出外部托管目录",
      `项目：${app.project.displayName}\n目录：${outputDirectory}\n\n`
        + "第一次必须使用空目录；继续使用以前的导出目录时会增量更新。",
      "确认导出"
    );
    if (!accepted) return;
    await runBusy("正在生成并校验静态分发目录", async () => {
      const result = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/distribution-export`,
        { method: "POST", body: { outputDirectory } }
      );
      const summary = byId("distribution-result");
      summary.hidden = false;
      summary.textContent = `导出完成：${result.outputDirectory}　`
        + `整合包 ${result.releaseCount} 个版本，玩家端程序 ${result.playerProgramCount} 个版本，`
        + `内容对象 ${result.objectCount} 个；本次复制 ${result.copiedObjectCount} 个（${formatBytes(result.copiedObjectBytes)}），复用 ${result.reusedObjectCount} 个。`;
      byId("webdav-upload-form").elements.outputDirectory.value = result.outputDirectory;
      byId("s3-upload-form").elements.outputDirectory.value = result.outputDirectory;
      toast("外部托管目录已导出，请上传目录中的全部文件");
    });
  });

  const webDavForm = byId("webdav-upload-form");
  webDavForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !webDavForm.reportValidity()) return;
    const data = new FormData(webDavForm);
    const payload = {
      outputDirectory: textValue(data, "outputDirectory"),
      baseUrl: textValue(data, "baseUrl"),
      username: textValue(data, "username"),
      password: String(data.get("password") || ""),
      exportFirst: data.get("exportFirst") === "on"
    };
    const accepted = await ask(
      "上传到 WebDAV / HTTP PUT",
      `目标：${payload.baseUrl}\n目录：${payload.outputDirectory}\n\n`
        + "将先上传不可变内容，全部成功后再更新 latest 和个性化内容。",
      "确认上传"
    );
    if (!accepted) return;
    await runBusy("正在导出并上传到 WebDAV", async () => {
      const result = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/distribution-webdav`,
        { method: "POST", body: payload }
      );
      renderDistributionUploadResult("webdav-upload-result", result);
      webDavForm.elements.password.value = "";
      toast("WebDAV 上传完成");
    });
  });

  const s3Form = byId("s3-upload-form");
  s3Form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !s3Form.reportValidity()) return;
    const data = new FormData(s3Form);
    const payload = {
      outputDirectory: textValue(data, "outputDirectory"),
      endpoint: textValue(data, "endpoint"),
      region: textValue(data, "region"),
      bucket: textValue(data, "bucket"),
      prefix: textValue(data, "prefix"),
      addressingStyle: textValue(data, "addressingStyle"),
      accessKeyId: textValue(data, "accessKeyId"),
      secretAccessKey: String(data.get("secretAccessKey") || ""),
      sessionToken: String(data.get("sessionToken") || ""),
      exportFirst: data.get("exportFirst") === "on"
    };
    const accepted = await ask(
      "上传到 OSS / S3 / R2",
      `Endpoint：${payload.endpoint}\nBucket：${payload.bucket}\n前缀：${payload.prefix || "（根目录）"}\n\n`
        + "全部对象成功后才会更新 latest，上传密钥不会保存。",
      "确认上传"
    );
    if (!accepted) return;
    await runBusy("正在导出并上传到对象存储", async () => {
      const result = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/distribution-s3`,
        { method: "POST", body: payload }
      );
      renderDistributionUploadResult("s3-upload-result", result);
      s3Form.elements.secretAccessKey.value = "";
      s3Form.elements.sessionToken.value = "";
      toast("对象存储上传完成");
    });
  });
}

function renderDistributionUploadResult(id, result) {
  const target = byId(id);
  target.hidden = false;
  target.textContent = `上传完成：${result.destination}　`
    + `共 ${result.fileCount} 个文件；本次上传 ${result.uploadedFileCount} 个（${formatBytes(result.uploadedBytes)}），`
    + `跳过未变化文件 ${result.skippedFileCount} 个。请再用玩家公开下载地址访问 healthz，确认外部读取已经开放。`;
}

function bindInstance() {
  const form = byId("instance-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      instanceDirectory: textValue(data, "instanceDirectory"),
      platform: textValue(data, "platform"),
      playerHome: textValue(data, "playerHome"),
      releaseId: textValue(data, "releaseId"),
      bundledCover: textValue(data, "bundledCover")
    };
    const accepted = await ask(
      "制作玩家实例",
      `实例目录：${payload.instanceDirectory}\n`
        + `整合包发布：${payload.releaseId}`,
      "确认写入"
    );
    if (!accepted) return;
    await runBusy("正在核验并制作玩家实例", async () => {
      await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/instance`,
        { method: "POST", body: payload }
      );
      toast("玩家实例制作完成");
    });
  });
}

function bindSettings() {
  const form = byId("settings-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      httpHost: textValue(data, "httpHost"),
      httpPort: Number(textValue(data, "httpPort")),
      webHost: textValue(data, "webHost"),
      webPort: Number(textValue(data, "webPort"))
    };
    const webRestartRequired = payload.webHost !== app.state.settings.webHost
      || payload.webPort !== app.state.settings.webPort;
    await runBusy("正在保存服务设置", async () => {
      await api("/api/settings", { method: "PUT", body: payload });
      await refreshState();
      toast(webRestartRequired
        ? "服务设置已保存；请重启 Web 管理界面使新监听设置生效"
        : "服务设置已保存");
    });
  });
}

function bindRollback() {
  const dialog = byId("rollback-dialog");
  const form = byId("rollback-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (event.submitter?.value === "cancel") {
      dialog.close();
      return;
    }
    if (!form.reportValidity() || !app.project) return;
    const data = new FormData(form);
    const payload = {
      targetReleaseId: textValue(data, "targetReleaseId"),
      displayVersion: textValue(data, "displayVersion"),
      changelog: textValue(data, "changelog")
    };
    dialog.close();
    await runBusy("正在创建回滚发布", async () => {
      const release = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/rollback`,
        { method: "POST", body: payload }
      );
      await refreshState(app.project.id);
      showPublishedToast(release, `回滚版本 ${release.displayVersion} 已发布`);
    });
  });
}

function showPublishedToast(release, message) {
  if (release.serviceWarning) {
    toast(`${message}；${release.serviceWarning}`, true);
  } else if (release.publicServiceRestarted) {
    toast(`${message}，HTTP 文件服务已自动重启`);
  } else {
    toast(message);
  }
}

function openRollback(release) {
  const form = byId("rollback-form");
  form.reset();
  setFormValue(form, "targetReleaseId", release.releaseId);
  setFormValue(
    form,
    "targetLabel",
    `${release.displayVersion} · #${release.sequence}`
  );
  setFormValue(
    form,
    "changelog",
    `回滚到 ${release.displayVersion}`
  );
  byId("rollback-dialog").showModal();
}

function showView(view) {
  if (!titles[view]) return;
  if (!app.project
      && view !== "dashboard"
      && view !== "settings") {
    toast("请先创建项目", true);
    return;
  }
  const changed = app.view !== view;
  app.view = view;
  document.querySelectorAll(".view").forEach((section) => {
    section.classList.toggle("active", section.id === `view-${view}`);
  });
  document.querySelectorAll(".nav-button").forEach((button) => {
    button.classList.toggle("active", button.dataset.view === view);
  });
  byId("page-title").textContent = titles[view];
  if (changed) byId("view-" + view).scrollTop = 0;
  if (view === "personalization") {
    requestAnimationFrame(resizePlayerPreview);
  }
}

function capturePublishPosition() {
  return {
    view: byId("view-publish")?.scrollTop || 0,
    sourceTable: document.querySelector(".source-file-table")?.scrollTop || 0,
    forcedDirectoryTable:
      document.querySelector(".forced-directory-table")?.scrollTop || 0,
    forcedFileTable:
      document.querySelector(".forced-file-table")?.scrollTop || 0
  };
}

function restorePublishPosition(position) {
  window.requestAnimationFrame(() => {
    byId("view-publish").scrollTop = position.view;
    const sourceTable = document.querySelector(".source-file-table");
    const forcedDirectoryTable = document.querySelector(
      ".forced-directory-table"
    );
    const forcedFileTable = document.querySelector(".forced-file-table");
    if (sourceTable) sourceTable.scrollTop = position.sourceTable;
    if (forcedDirectoryTable) {
      forcedDirectoryTable.scrollTop = position.forcedDirectoryTable;
    }
    if (forcedFileTable) forcedFileTable.scrollTop = position.forcedFileTable;
  });
}

async function runBusy(label, operation) {
  if (app.busy) return;
  app.busy = true;
  byId("busy-label").textContent = label;
  byId("busy-layer").classList.add("visible");
  byId("busy-layer").setAttribute("aria-hidden", "false");
  try {
    await operation();
  } catch (error) {
    toast(error.message, true);
  } finally {
    app.busy = false;
    byId("busy-layer").classList.remove("visible");
    byId("busy-layer").setAttribute("aria-hidden", "true");
  }
}

function ask(title, message, actionText, dangerous = false) {
  const dialog = byId("confirm-dialog");
  const action = byId("confirm-action");
  byId("confirm-title").textContent = title;
  byId("confirm-message").textContent = message;
  action.textContent = actionText;
  action.value = "confirm";
  action.className = dangerous ? "danger-button" : "primary-button";
  dialog.returnValue = "cancel";
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener(
      "close",
      () => resolve(dialog.returnValue === "confirm"),
      { once: true }
    );
  });
}

function setRows(bodyId, emptyId, rows) {
  byId(bodyId).replaceChildren(...rows);
  byId(emptyId).classList.toggle("visible", rows.length === 0);
}

function row(values) {
  const tr = document.createElement("tr");
  values.forEach((value) => {
    const td = value instanceof HTMLTableCellElement
      ? value
      : document.createElement("td");
    if (!(value instanceof HTMLTableCellElement)) {
      if (value instanceof Node) {
        td.append(value);
      } else {
        td.textContent = value ?? "";
        td.title = value ?? "";
      }
    }
    tr.append(td);
  });
  return tr;
}

function textCell(value, className) {
  const td = document.createElement("td");
  td.textContent = value ?? "";
  td.title = value ?? "";
  if (className) td.className = className;
  return td;
}

function pathCell(value) {
  return textCell(value, "path-cell");
}

function hashCell(value) {
  return textCell(value, "hash-cell");
}

function actionButton(label, action) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "table-button";
  button.textContent = label;
  button.addEventListener("click", action);
  return button;
}

function option(value, label) {
  const item = document.createElement("option");
  item.value = value;
  item.textContent = label;
  return item;
}

function setFormValue(form, name, value) {
  const control = form.elements.namedItem(name);
  if (control) control.value = value ?? "";
}

function setColor(form, name, value) {
  const normalized = /^#[0-9a-f]{6}$/i.test(value || "")
    ? value
    : name === "accentColor" ? "#2ee8df" : "#b06cff";
  setFormValue(form, name, normalized);
  setFormValue(form, name + "Text", normalized);
}

function bindColorPair(form, name) {
  const picker = form.elements.namedItem(name);
  const text = form.elements.namedItem(name + "Text");
  picker.addEventListener("input", () => {
    text.value = picker.value;
  });
  text.addEventListener("change", () => {
    if (/^#[0-9a-f]{6}$/i.test(text.value)) {
      picker.value = text.value;
    } else {
      text.value = picker.value;
      toast("颜色必须使用 #RRGGBB 格式", true);
    }
  });
}

function textValue(formData, name) {
  return String(formData.get(name) || "").trim();
}

function directoryList(value) {
  return value
    .split(/[,，\n]/)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function visibleSourceFiles() {
  const query = byId("source-file-search").value
    .trim().toLocaleLowerCase("zh-CN");
  return (app.sourceFiles?.files || []).filter((file) =>
    !query || file.path.toLocaleLowerCase("zh-CN").includes(query)
  );
}

function visibleForcedFiles() {
  const query = byId("forced-file-search").value
    .trim().toLocaleLowerCase("zh-CN");
  return forcedFileCandidates().filter((file) =>
    !query || file.path.toLocaleLowerCase("zh-CN").includes(query)
  );
}

function fileTreeRows(files, folderRenderer, fileRenderer, options = {}) {
  if (files.length === 0) return [];
  const root = buildFileTree(files);
  const rows = [];
  const appendContents = (node, depth) => {
    [...node.folders.values()]
      .sort((left, right) => left.name.localeCompare(
        right.name, "zh-CN", { sensitivity: "base" }
      ))
      .forEach((folder) => {
        const treeState = folderExpansionState(folder, options);
        rows.push(folderRenderer(folder, depth, treeState));
        if (treeState.expanded) appendContents(folder, depth + 1);
      });
    node.files
      .sort(compareFilePath)
      .forEach((file) => rows.push(fileRenderer(file, depth)));
  };
  appendContents(root, 0);
  return rows;
}

function folderExpansionState(node, options = {}) {
  const expandedFolders = options.expandedFolders || new Set();
  const expanded = Boolean(options.expandAll)
    || pathSelected(expandedFolders, node.path);
  return {
    expanded,
    hasChildren: node.folders.size > 0 || node.files.length > 0,
    toggle() {
      setPathSelected(expandedFolders, node.path, !expanded);
      options.onToggle?.();
    }
  };
}

function buildFileTree(files) {
  const root = {
    name: "",
    path: "",
    entries: [],
    files: [],
    folders: new Map()
  };
  files.forEach((file) => {
    const parts = String(file.path || "")
      .replaceAll("\\", "/")
      .split("/")
      .filter(Boolean);
    root.entries.push(file);
    let node = root;
    let currentPath = "";
    parts.slice(0, -1).forEach((name) => {
      currentPath = currentPath ? `${currentPath}/${name}` : name;
      const key = name.toLocaleLowerCase("en-US");
      if (!node.folders.has(key)) {
        node.folders.set(key, {
          name,
          path: currentPath,
          entries: [],
          files: [],
          folders: new Map()
        });
      }
      node = node.folders.get(key);
      node.entries.push(file);
    });
    node.files.push(file);
  });
  return root;
}

function folderPathCell(node, depth, treeState) {
  const cell = document.createElement("td");
  cell.className = "path-cell tree-path-cell";
  indentTreeCell(cell, depth);
  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "tree-toggle";
  toggle.textContent = treeState?.expanded ? "▾" : "▸";
  toggle.disabled = !treeState?.hasChildren;
  toggle.title = treeState?.expanded ? "收起文件夹" : "展开文件夹";
  toggle.setAttribute("aria-label", `${toggle.title} ${node.path}/`);
  toggle.setAttribute("aria-expanded", String(Boolean(treeState?.expanded)));
  toggle.addEventListener("click", (event) => {
    event.stopPropagation();
    treeState?.toggle();
  });
  const icon = document.createElement("span");
  icon.className = "tree-entry-icon folder";
  icon.setAttribute("aria-hidden", "true");
  const label = document.createElement("span");
  label.className = "tree-folder-name";
  label.textContent = `${node.name}/`;
  const count = document.createElement("span");
  count.className = "tree-folder-count";
  count.textContent = `${node.entries.length} 个文件`;
  cell.title = `${node.path}/`;
  cell.append(toggle, icon, label, count);
  return cell;
}

function treeFilePathCell(path, depth) {
  const cell = document.createElement("td");
  cell.className = "path-cell tree-path-cell";
  indentTreeCell(cell, depth);
  const spacer = document.createElement("span");
  spacer.className = "tree-toggle-spacer";
  const icon = document.createElement("span");
  icon.className = "tree-entry-icon file";
  icon.setAttribute("aria-hidden", "true");
  const label = document.createElement("span");
  label.className = "tree-file-name";
  label.textContent = String(path || "").replaceAll("\\", "/")
    .split("/").filter(Boolean).at(-1) || String(path || "");
  cell.title = path;
  cell.append(spacer, icon, label);
  return cell;
}

function indentTreeCell(cell, depth) {
  cell.classList.add("tree-indented-cell");
  cell.style.setProperty("--tree-depth", String(Math.max(0, depth)));
}

function treeSelectionCell(
  files, isSelected, isSelectable, onChange, title
) {
  const cell = document.createElement("td");
  const checkbox = selectionCheckbox(false, false, title);
  applySelectionState(checkbox, files, isSelected, isSelectable);
  checkbox.addEventListener("change", () => {
    onChange(checkbox.checked, files.filter(isSelectable));
  });
  cell.append(checkbox);
  return cell;
}

function forcedTreeSelectionCell(files, onChange, title) {
  const cell = document.createElement("td");
  const checkbox = selectionCheckbox(false, false, title);
  const existing = files.filter((file) => file.policy !== "MISSING");
  const selectable = files.filter(forcedFileSelectable);
  const selected = existing.filter((file) =>
    insideForcedDirectory(file.path)
      || pathSelected(app.forcedFileSelection, file.path)
  ).length;
  checkbox.checked = existing.length > 0 && selected === existing.length;
  checkbox.indeterminate = selected > 0 && selected < existing.length;
  checkbox.disabled = selectable.length === 0;
  checkbox.addEventListener("change", () => {
    onChange(checkbox.checked, selectable);
  });
  cell.append(checkbox);
  return cell;
}

function selectionCheckbox(checked, disabled, title) {
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.className = "tree-selection-check";
  checkbox.checked = checked;
  checkbox.disabled = disabled;
  checkbox.title = title;
  checkbox.setAttribute("aria-label", title);
  return checkbox;
}

function applySelectionState(
  checkbox, files, isSelected, isSelectable
) {
  const selectable = files.filter(isSelectable);
  const selected = selectable.filter(isSelected).length;
  checkbox.checked = selectable.length > 0 && selected === selectable.length;
  checkbox.indeterminate = selected > 0 && selected < selectable.length;
  checkbox.disabled = selectable.length === 0;
}

function applyForcedSelectionState(checkbox, files) {
  const existing = files.filter((file) => file.policy !== "MISSING");
  const selected = existing.filter((file) =>
    insideForcedDirectory(file.path)
      || pathSelected(app.forcedFileSelection, file.path)
  ).length;
  checkbox.checked = existing.length > 0 && selected === existing.length;
  checkbox.indeterminate = selected > 0 && selected < existing.length;
  checkbox.disabled = !files.some(forcedFileSelectable);
}

function forcedFileSelectable(file) {
  return file.policy !== "MISSING" && !insideForcedDirectory(file.path);
}

function pathSelected(selection, path) {
  if (selection.has(path)) return true;
  const folded = foldPath(path);
  return [...selection].some((candidate) => foldPath(candidate) === folded);
}

function setPathSelected(selection, path, selected) {
  const folded = foldPath(path);
  [...selection]
    .filter((candidate) => foldPath(candidate) === folded)
    .forEach((candidate) => selection.delete(candidate));
  if (selected) selection.add(path);
}

function setPathsSelected(selection, files, selected) {
  const next = new Map(
    [...selection].map((path) => [foldPath(path), path])
  );
  files.forEach((file) => {
    if (selected) next.set(foldPath(file.path), file.path);
    else next.delete(foldPath(file.path));
  });
  selection.clear();
  next.forEach((path) => selection.add(path));
}

function compareFilePath(left, right) {
  return left.path.localeCompare(
    right.path, "zh-CN", { sensitivity: "base" }
  );
}

function foldPath(value) {
  return String(value || "").replaceAll("\\", "/").toLocaleLowerCase("en-US");
}

function insideForcedDirectory(path) {
  const folded = foldPath(path);
  return (app.project?.forcedSyncDirectories || []).some((directory) => {
    const root = foldPath(directory);
    return folded.startsWith(`${root}/`);
  });
}

function formatBytes(value) {
  const size = Number(value || 0);
  if (size < 1024) return `${size} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let current = size;
  let unit = "B";
  for (const next of units) {
    current /= 1024;
    unit = next;
    if (current < 1024) break;
  }
  return `${current >= 100 ? current.toFixed(0) : current.toFixed(1)} ${unit}`;
}

function formatDate(value) {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(date);
}

function setConnection(online) {
  const dot = byId("connection-dot");
  dot.classList.toggle("online", online);
  dot.classList.toggle("offline", !online);
  byId("connection-label").textContent =
    online ? "本地管理服务已连接" : "管理服务连接失败";
}

function toast(message, error = false) {
  if (error) {
    showErrorDialog(message);
    return;
  }
  const stack = byId("toast-stack");
  const element = document.createElement("div");
  element.className = "toast";
  element.textContent = message;
  stack.append(element);
  window.setTimeout(() => element.remove(), 4200);
}

function showErrorDialog(message) {
  const dialog = byId("error-dialog");
  byId("error-dialog-message").textContent =
    String(message || "操作未能完成，请稍后重试。");
  if (!dialog.open) dialog.showModal();
}

window.addEventListener("error", (event) => {
  toast(event.message || "页面发生错误", true);
});

window.addEventListener("unhandledrejection", (event) => {
  const message = event.reason instanceof Error
    ? event.reason.message
    : String(event.reason || "页面请求未能完成");
  toast(message, true);
});

window.addEventListener("beforeunload", () => {
  app.sourceUploadCancelled = true;
  abortActiveUploads();
});

initialize();
