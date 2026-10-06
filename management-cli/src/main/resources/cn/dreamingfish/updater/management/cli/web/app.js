"use strict";

const app = {
  token: "",
  auth: null,
  state: null,
  project: null,
  selectedProjectId: "",
  view: "dashboard",
  busy: false,
  sourceFileSelection: new Set(),
  sourceExpandedFolders: new Set(),
  sourceFiles: null,
  history: null,
  withdrawSelection: new Set(),
  correctSelection: new Set(),
  pendingUploads: [],
  sourceDirectory: "",
  collapsedSourceDirectories: new Set(),
  detailPath: "",
  transferTasks: [],
  transferBusy: false,
  transferPaused: false,
  transferProjectId: "",
  personalTab: "brand",
  packagePlan: null,
  uploadTargetDirectory: null,
  uploadTargetExpandedFolders: new Set(),
  activeUploads: new Set(),
  sourceUploadCancelled: false,
  pendingCoverFile: null,
  pendingCoverPreviewUrl: null,
  playerPreviewReady: false,
  playerEditorProjectId: "",
  servicePollId: null,
  expandedPlayerPages: new Set(),
  expandedPlayerArticles: new Set(),
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

const DEFAULT_PLAYER_APPEARANCE = Object.freeze({
  accentColor: "#2ee8df",
  secondaryAccentColor: "#b06cff",
  titleColor: "#fff8dc",
  topBarColor: "#030708",
  topBarOpacity: 0.22,
  cardColor: "#030708"
});

const titles = {
  dashboard: "运行概览",
  content: "管理内容",
  publish: "检查并发布",
  project: "项目设置",
  personalization: "玩家端个性化",
  player: "玩家端程序",
  distribution: "外部托管",
  instance: "玩家实例",
  settings: "系统设置"
};

const kindNames = {
  ADDED: "新增",
  MODIFIED: "更新",
  REMOVED: "移除",
  POLICY_CHANGED: "维护方式",
  METADATA_CHANGED: "模组信息"
};

const PRESETS = Object.freeze({
  REQUIRED: Object.freeze({
    label: "强制同步", css: "required",
    help: "和服主完全一致，玩家不能停用或自行管理"
  }),
  SYNC: Object.freeze({
    label: "普通同步", css: "sync",
    help: "跟随服主更新，玩家可以停用模组或改为自行管理"
  }),
  INITIAL: Object.freeze({
    label: "首次提供", css: "initial",
    help: "只在玩家没有时放一份，之后归玩家所有"
  }),
  DEFAULT_CONFIG: Object.freeze({
    label: "旧版：未修改才更新", css: "default-config",
    help: "玩家没改过就跟着更新，改过就保留玩家的"
  })
});

const REMOVAL_LABELS = Object.freeze({
  DELETE: "移除玩家副本（持续生效，先备份）",
  RELEASE: "停止维护，留给玩家"
});

const REMOVAL_HELP = Object.freeze({
  DELETE: "活动、停用、豁免和首次提供副本先备份再移出；以后装回仍会处理",
  RELEASE: "玩家已有的文件留在原处，从此由玩家自己处理"
});

const WARNING_TITLES = Object.freeze({
  PLAYER_PROGRAM_REQUIRED: "玩家端需要升级",
  CONTENT_MOD_REMOVED: "移除了可能影响存档的模组",
  STALE_RULE: "有规则指向已经不存在的文件"
});

const byId = (id) => document.getElementById(id);

async function api(path, options = {}) { return AdminTransport.request(path, options, app.token); }

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
  startServicePolling();
}

function startServicePolling() {
  if (app.servicePollId != null) window.clearInterval(app.servicePollId);
  app.servicePollId = window.setInterval(async () => {
    if (app.busy || app.state == null || document.hidden
        || byId("app-shell").hidden) return;
    try {
      app.state.publicService = await api("/api/public-service/status");
      renderService();
    } catch {
      // The global connection state is handled by explicit page requests. A
      // transient background probe must not interrupt the administrator.
    }
  }, 10_000);
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
    if (app.view === "content") {
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
  app.sourceFileSelection = new Set();
  app.sourceFiles = null;
  if (projectChanged) {
    app.sourceExpandedFolders.clear();
    app.sourceDirectory = "";
    app.collapsedSourceDirectories.clear();
    app.packagePlan = null;
    app.uploadTargetExpandedFolders.clear();
    app.uploadTargetDirectory = null;
    app.history = null;
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
  byId("service-indicator").classList.toggle(
    "warning", service.portOccupied && !service.running);
  byId("service-title").textContent = service.running
    ? service.managed
      ? "HTTP 文件服务运行中"
      : "HTTP 文件服务已在其他进程中运行"
    : service.portOccupied
      ? "下载服务端口已占用，但服务无响应"
      : "HTTP 文件服务未启动";
  byId("service-address").textContent = service.address;
  byId("service-address").title = service.address;
  byId("service-detail").textContent = service.detail || "";
  byId("service-start").disabled = service.running || service.portOccupied;
  byId("service-stop").disabled = !service.running || !service.controllable;
  byId("service-restart").disabled = !service.running || !service.controllable;
  byId("service-stop").title = service.running && !service.controllable
    ? "这个旧版或不同数据目录中的服务无法安全远程停止"
    : "停止已识别的 DreamingFish 下载服务";
  byId("service-restart").title = service.running && !service.controllable
    ? "这个服务没有注册管理控制通道"
    : "停止后由当前 Web 管理端重新启动下载服务";
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
  renderContent();
  renderPreview();
  renderReleases();
  renderPrograms();
  renderInstanceReleases();
}

async function loadSourceFiles() {
  if (!app.project) return;
  app.sourceFiles = await api(
    `/api/projects/${encodeURIComponent(app.project.id)}/files`
  );
  pruneSourceSelection();
  renderContent();
}

function pruneSourceSelection() {
  const selected = app.sourceFileSelection;
  app.sourceFileSelection = new Set(
    (app.sourceFiles?.files || [])
      .filter((file) => pathSelected(selected, file.path))
      .map((file) => file.path)
  );
}

function renderContent() {
  renderSourceFiles();
  renderCleanup();
  renderGroups();
  renderDirectives();
}

function renderSourceFiles() { renderFileWorkspace(); }

function contentFolderRow(node, depth, treeState) {
  const control = treeSelectionCell(
    node.entries,
    (file) => pathSelected(app.sourceFileSelection, file.path),
    () => true,
    (checked, files) => {
      setPathsSelected(app.sourceFileSelection, files, checked);
      renderSourceFiles();
    },
    `选择 ${node.path}/ 中的全部文件`
  );
  const name = folderPathCell(node, depth, treeState);
  if (depth === 0 && cleanupEnabled(node.path)) {
    name.append(tag("强制同步目录", "cleanup",
      "玩家在这个目录里自己添加的文件会在更新时移入备份"));
  }
  const preset = document.createElement("td");
  preset.append(presetSelect(node.path, true));
  const group = document.createElement("td");
  const owner = groupForDirectory(node.path);
  if (owner) group.append(tag(owner.title, "group", "整个文件夹属于这组可选内容"));
  const totalBytes = node.entries.reduce(
    (sum, file) => sum + Number(file.size || 0), 0
  );
  const item = row([control, name, preset, group, formatBytes(totalBytes), ""]);
  item.className = "file-tree-folder";
  return item;
}

function contentFileRow(file, depth) { return workspaceFileRow(file); }

function presetSelect(path, directory, file = null) {
  const select = document.createElement("select");
  select.className = "preset-select";
  const own = ownPresetRule(path, directory);
  const inherited = file?.presetSource === "GROUP"
    ? { preset: "SYNC", source: null, optional: inheritedPreset(path, directory).source }
    : inheritedPreset(path, directory);
  const inheritedLabel = PRESETS[inherited.preset]?.label || inherited.preset;
  select.append(option("", inherited.optional
    ? `可选内容：${inheritedLabel}`
    : inherited.source
      ? `随 ${inherited.source}/：${inheritedLabel}`
      : directory
        ? `不单独设置（${inheritedLabel}）`
        : `默认：${inheritedLabel}`));
  Object.entries(PRESETS).filter(([key]) => key !== "DEFAULT_CONFIG").forEach(([value, preset]) => {
    const choice = option(value, preset.label);
    choice.disabled = inherited.preset === "REQUIRED" && value !== "REQUIRED";
    select.append(choice);
  });
  select.value = own?.preset || "";
  const effective = own?.preset || inherited.preset;
  select.classList.add(`preset-${PRESETS[effective]?.css || "sync"}`);
  select.classList.toggle("inherited", !own);
  select.title = `${PRESETS[effective]?.label}：${PRESETS[effective]?.help}`
    + (directory ? "。改变文件夹方式会统一其下的文件设置；强制同步还会移出额外文件" : "")
    + (inherited.preset === "REQUIRED"
      ? `。如需普通同步或首次提供，请先改变 ${inherited.source}/ 的强制同步设置` : "");
  select.setAttribute("aria-label", `${path}${directory ? "/" : ""} 的维护方式`);
  select.addEventListener("change", () => {
    applyPresets([{ path, directory }], select.value || "DEFAULT");
  });
  return select;
}

function ownPresetRule(path, directory) {
  const folded = foldPath(path);
  return (app.project?.maintenance?.presets || []).find((rule) =>
    Boolean(rule.directory) === directory && foldPath(rule.path) === folded
  ) || null;
}

/** The preset a path gets from enclosing folders, ignoring its own rule. */
function inheritedPreset(path, directory) {
  const folded = foldPath(path);
  let best = null;
  (app.project?.maintenance?.presets || []).forEach((rule) => {
    if (!rule.directory) return;
    const root = foldPath(rule.path);
    if (directory && root === folded) return;
    if (folded.startsWith(`${root}/`)
        && (!best || rule.path.length > best.path.length)) {
      best = rule;
    }
  });
  return best
    ? { preset: best.preset, source: best.path }
    : { preset: "SYNC", source: null };
}

function cleanupEnabled(path) {
  const folded = foldPath(path);
  return (app.project?.maintenance?.cleanupDirectories || [])
    .some((directory) => foldPath(directory) === folded);
}

function groupById(id) {
  if (!id) return null;
  return (app.project?.maintenance?.optionalGroups || [])
    .find((group) => group.id === id) || null;
}

function groupForDirectory(path) {
  const folded = foldPath(path);
  return (app.project?.maintenance?.optionalGroups || []).find((group) =>
    (group.directories || []).some((directory) => {
      const root = foldPath(directory);
      return folded === root || folded.startsWith(`${root}/`);
    })
  ) || null;
}

function tag(text, kind, title) {
  const element = document.createElement("span");
  element.className = `inline-tag ${kind}`;
  element.textContent = text;
  if (title) element.title = title;
  return element;
}

function presetTag(preset) {
  return tag(PRESETS[preset]?.label || preset, `preset-${PRESETS[preset]?.css || "sync"}`,
    PRESETS[preset]?.help);
}

function note(text) {
  const element = document.createElement("p");
  element.className = "list-note";
  element.textContent = text;
  return element;
}

function updateSourceFileSelection(visibleFiles = []) {
  const selected = selectedSourceFiles();
  const none = selected.length === 0;
  byId("source-file-selection-count").textContent = none
    ? "未选择文件"
    : `已选择 ${selected.length} 个文件`;
  byId("remove-selected-source-files").disabled = none;
  byId("selection-preset").disabled = none;
  byId("selection-add-group").disabled = none;
  byId("selection-remove-group").disabled =
    !selected.some((file) => file.optionalGroup);
  applySelectionState(
    byId("source-file-select-all"),
    visibleFiles,
    (file) => pathSelected(app.sourceFileSelection, file.path),
    () => true
  );
}

function selectedSourceFiles() {
  return (app.sourceFiles?.files || []).filter(
    (file) => pathSelected(app.sourceFileSelection, file.path)
  );
}

async function maintenance(action, body, label, done) {
  if (!app.project) return null;
  const projectId = app.project.id;
  const position = captureContentPosition();
  let result = null;
  await runBusy(label, async () => {
    result = await api(
      `/api/projects/${encodeURIComponent(projectId)}/maintenance/${action}`,
      { method: "POST", body }
    );
    applyMaintenanceResult(result);
    restoreContentPosition(position);
    if (done) toast(typeof done === "function" ? done(result) : done);
  });
  // A refused change re-renders so switches show the saved state again.
  if (!result) renderContent();
  return result;
}

function applyMaintenanceResult(result) {
  if (!app.project || !result) return;
  if (result.project) Object.assign(app.project, result.project);
  if (typeof result.previewStale === "boolean") {
    app.project.previewStale = result.previewStale;
  }
  if (result.sourceFiles) {
    app.sourceFiles = result.sourceFiles;
    pruneSourceSelection();
  }
  renderContent();
  renderPreview();
}

async function applyPresets(items, preset) {
  if (!await confirmFolderPreset(items, preset)) { renderSourceFiles(); return null; }
  const label = preset === "DEFAULT" ? "跟随文件夹" : PRESETS[preset]?.label;
  return maintenance("presets", { items, preset }, "正在保存维护方式",
    items.length === 1
      ? `已设为“${label}”，发布新版本后生效`
      : `${items.length} 项已设为“${label}”，发布新版本后生效`);
}

function topLevelDirectories() {
  const known = new Map();
  const remember = (path, missing) => {
    const key = foldPath(path);
    if (!known.has(key)) known.set(key, { path, fileCount: 0, missing });
    return known.get(key);
  };
  (app.sourceFiles?.files || []).forEach((file) => {
    const slash = file.path.indexOf("/");
    if (slash > 0) remember(file.path.slice(0, slash), false).fileCount += 1;
  });
  (app.sourceFiles?.directories || []).forEach((directory) => {
    if (!directory.includes("/")) remember(directory, false);
  });
  (app.project?.maintenance?.cleanupDirectories || []).forEach((directory) => {
    remember(directory, true);
  });
  return [...known.values()].sort((left, right) =>
    left.path.localeCompare(right.path, "zh-CN", { sensitivity: "base" })
  );
}

function renderCleanup() {
  const list = byId("cleanup-list");
  const enabled = app.project?.maintenance?.cleanupDirectories || [];
  byId("cleanup-count").textContent = enabled.length === 0
    ? "未开启"
    : `已对 ${enabled.length} 个目录开启`;
  if (!app.sourceFiles) {
    list.replaceChildren(note("打开页面后读取目录"));
    return;
  }
  const directories = topLevelDirectories();
  if (directories.length === 0) {
    list.replaceChildren(note("整合包目录中还没有文件夹"));
    return;
  }
  list.replaceChildren(...directories.map((directory) => {
    const on = cleanupEnabled(directory.path);
    const item = document.createElement("label");
    item.className = `cleanup-item${on ? " active" : ""}`;
    const toggle = document.createElement("input");
    toggle.type = "checkbox";
    toggle.className = "switch";
    toggle.checked = on;
    toggle.setAttribute("aria-label", `清理 ${directory.path}/ 中的多余文件`);
    toggle.addEventListener("change", () => setCleanup(directory.path, toggle.checked));
    const text = document.createElement("span");
    const name = document.createElement("strong");
    name.textContent = `${directory.path}/`;
    const detail = document.createElement("small");
    detail.textContent = directory.missing
      ? "目录已不存在，可以关闭"
      : on
        ? `玩家自己添加的文件会移入备份 · ${directory.fileCount} 个托管文件`
        : `玩家自己添加的文件不受影响 · ${directory.fileCount} 个托管文件`;
    text.append(name, detail);
    item.append(toggle, text);
    return item;
  }));
}

async function setCleanup(directory, enabled) {
  if (enabled) {
    const accepted = await ask(
      "开启清理多余文件",
      `开启后，玩家在 ${directory}/ 里自己添加的文件会在下次更新时移入备份，不会再留在游戏里。\n\n`
        + `请确认 ${directory}/ 不是存档、截图、日志等玩家自己的目录。`,
      "开启清理"
    );
    if (!accepted) {
      renderCleanup();
      return;
    }
  }
  await maintenance("cleanup", { directory, enabled }, "正在保存清理设置",
    enabled ? `已开启 ${directory}/ 的清理，发布新版本后生效`
      : `已关闭 ${directory}/ 的清理，发布新版本后生效`);
}

function renderGroups() {
  const list = byId("group-list");
  const groups = app.project?.maintenance?.optionalGroups || [];
  byId("group-count").textContent = groups.length === 0
    ? "没有可选内容"
    : `${groups.length} 组可选内容`;
  if (groups.length === 0) {
    list.replaceChildren(note("还没有可选内容。新建一组后，在上方列表中选择文件加入。"));
    return;
  }
  list.replaceChildren(...groups.map(groupCard));
}

function groupCard(group) {
  const card = document.createElement("article");
  card.className = "group-card";
  const header = document.createElement("div");
  header.className = "group-card-header";
  const title = document.createElement("div");
  title.className = "group-card-title";
  const name = document.createElement("strong");
  name.textContent = group.title;
  title.append(name, tag(group.defaultInstall ? "默认安装" : "默认不安装",
    group.defaultInstall ? "on" : "off"));
  const actions = document.createElement("div");
  actions.className = "button-row";
  actions.append(
    actionButton("编辑", () => openGroupDialog(group)),
    actionButton("删除", () => deleteGroup(group))
  );
  header.append(title, actions);
  card.append(header);
  if (group.description) {
    const description = document.createElement("p");
    description.textContent = group.description;
    card.append(description);
  }
  const members = groupMembers(group);
  const list = document.createElement("ul");
  list.className = "group-members";
  if (members.length === 0) {
    const empty = document.createElement("li");
    empty.className = "group-members-empty";
    empty.textContent = "还没有内容：在上方列表中选择文件，再点“加入可选内容”";
    list.append(empty);
  }
  members.forEach((member) => {
    const item = document.createElement("li");
    const label = document.createElement("span");
    label.textContent = member.label;
    label.title = member.title;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "member-remove";
    remove.textContent = "×";
    remove.title = `把 ${member.label} 移出这组可选内容`;
    remove.setAttribute("aria-label", remove.title);
    remove.addEventListener("click", () => maintenance("group-members",
      { groupId: group.id, add: false, items: [member.item] },
      "正在移出可选内容", `已把 ${member.label} 移出“${group.title}”`));
    item.append(label, remove);
    list.append(item);
  });
  card.append(list);
  if (app.sourceFiles) {
    const count = (app.sourceFiles.files || [])
      .filter((file) => file.optionalGroup === group.id).length;
    const footer = document.createElement("small");
    footer.className = "group-card-footer";
    footer.textContent = `当前包含 ${count} 个文件`;
    card.append(footer);
  }
  return card;
}

function groupMembers(group) {
  const files = app.sourceFiles?.files || [];
  const members = [];
  (group.modIds || []).forEach((modId) => {
    const jar = files.find((file) => file.componentId
      && file.componentId.toLowerCase() === modId.toLowerCase());
    members.push({
      label: jar?.displayName ? `${jar.displayName}（${modId}）` : `模组 ${modId}`,
      title: jar ? `${jar.path}\n按 modid 加入，改名后的新版本仍然属于这组`
        : `modid：${modId}（整合包目录中暂时没有这个模组）`,
      item: { modId }
    });
  });
  (group.files || []).forEach((path) => members.push({
    label: path, title: path, item: { path, directory: false }
  }));
  (group.directories || []).forEach((path) => members.push({
    label: `${path}/`, title: `${path}/ 中的全部文件`, item: { path, directory: true }
  }));
  return members;
}

function openGroupDialog(group) {
  const dialog = byId("group-dialog");
  const form = byId("group-form");
  form.reset();
  byId("group-dialog-title").textContent = group ? "编辑可选内容" : "新建可选内容";
  setFormValue(form, "id", group?.id || "");
  setFormValue(form, "title", group?.title || "");
  setFormValue(form, "description", group?.description || "");
  form.elements.defaultInstall.checked = group ? Boolean(group.defaultInstall) : true;
  dialog.showModal();
}

async function deleteGroup(group) {
  const accepted = await ask(
    "删除可选内容",
    `删除“${group.title}”后，里面的文件会变回普通内容，按各自的维护方式同步给所有玩家；`
      + "玩家之前关闭这组内容的选择也会失效。",
    "删除",
    true
  );
  if (!accepted) return;
  await maintenance("group-delete", { id: group.id }, "正在删除可选内容",
    `已删除“${group.title}”`);
}

async function pickGroup(message) {
  const dialog = byId("group-pick-dialog");
  const form = byId("group-pick-form");
  const groups = app.project?.maintenance?.optionalGroups || [];
  form.reset();
  byId("group-pick-message").textContent = message;
  const list = byId("group-pick-list");
  list.replaceChildren(
    ...groups.map((group, index) => choiceRadio("groupChoice", group.id, group.title,
      group.description || (group.defaultInstall ? "默认安装" : "默认不安装"), index === 0)),
    choiceRadio("groupChoice", "__new__", "新建一组可选内容", "在下方填写名称",
      groups.length === 0)
  );
  const fields = byId("group-pick-new");
  const sync = () => {
    fields.hidden = form.querySelector('input[name="groupChoice"]:checked')?.value
      !== "__new__";
  };
  list.querySelectorAll("input").forEach((input) => {
    input.addEventListener("change", sync);
  });
  sync();
  dialog.returnValue = "cancel";
  dialog.showModal();
  const confirmed = await new Promise((resolve) => {
    dialog.addEventListener("close",
      () => resolve(dialog.returnValue === "confirm"), { once: true });
  });
  if (!confirmed) return null;
  const choice = form.querySelector('input[name="groupChoice"]:checked')?.value;
  if (choice && choice !== "__new__") return choice;
  const data = new FormData(form);
  const title = textValue(data, "title");
  if (!title) {
    toast("请填写新可选内容的名称", true);
    return null;
  }
  const result = await maintenance("group", {
    title,
    description: textValue(data, "description"),
    defaultInstall: form.elements.defaultInstall.checked
  }, "正在创建可选内容");
  return result?.groupId || null;
}

function choiceRadio(name, value, title, detail, checked) {
  const label = document.createElement("label");
  label.className = "choice";
  const input = document.createElement("input");
  input.type = "radio";
  input.name = name;
  input.value = value;
  input.checked = checked;
  const text = document.createElement("span");
  const strong = document.createElement("strong");
  strong.textContent = title;
  text.append(strong);
  if (detail) {
    const small = document.createElement("small");
    small.textContent = detail;
    text.append(small);
  }
  label.append(input, text);
  return label;
}

async function addFilesToGroup(files, message) {
  const groupId = await pickGroup(message);
  if (!groupId) return false;
  const required = files.filter((file) => file.preset === "REQUIRED"
    && file.presetSource === "FILE");
  if (required.length > 0) {
    // A file set to required on its own cannot be left to players; it becomes normal sync first.
    const changed = await maintenance("presets", {
      items: required.map((file) => ({ path: file.path, directory: false })),
      preset: "SYNC"
    }, "正在把强制同步改为普通同步");
    if (!changed) return false;
  }
  const group = groupById(groupId);
  const result = await maintenance("group-members", {
    groupId,
    add: true,
    items: files.map((file) => ({ path: file.path, directory: false }))
  }, "正在加入可选内容",
  `${files.length} 个文件已加入“${group?.title || groupId}”，发布新版本后生效`);
  return Boolean(result);
}

async function removeFilesFromGroups(files) {
  const grouped = new Map();
  files.filter((file) => file.optionalGroup).forEach((file) => {
    if (!grouped.has(file.optionalGroup)) grouped.set(file.optionalGroup, []);
    grouped.get(file.optionalGroup).push(file);
  });
  for (const [groupId, members] of grouped) {
    const result = await maintenance("group-members", {
      groupId,
      add: false,
      items: members.map((file) => ({ path: file.path, directory: false }))
    }, "正在移出可选内容");
    if (!result) return;
  }
  const remaining = files.filter((file) => (app.sourceFiles?.files || []).some(
    (current) => foldPath(current.path) === foldPath(file.path) && current.optionalGroup
  ));
  if (remaining.length > 0) {
    toast(`有 ${remaining.length} 个文件所在的文件夹整体属于可选内容，请在“可选内容”卡片中移除那个文件夹`, true);
  } else {
    toast("已移出可选内容，发布新版本后生效");
  }
}

async function removeSourceFiles(files) {
  if (!app.project || files.length === 0) return;
  const action = await chooseRemoval(files);
  if (!action) return;
  if (action === "OPTIONAL") {
    await addFilesToGroup(files, files.length === 1
      ? `“${files[0].path}”会留在整合包里，由玩家决定是否安装。加入哪组可选内容？`
      : `所选的 ${files.length} 个文件会留在整合包里，由玩家决定是否安装。加入哪组可选内容？`);
    return;
  }
  const position = captureContentPosition();
  await runBusy(`正在归档并移除 ${files.length} 个文件`, async () => {
    await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/files/remove-batch`,
      {
        method: "POST",
        body: { paths: files.map((file) => file.path), action }
      }
    );
    files.forEach((file) => setPathSelected(app.sourceFileSelection, file.path, false));
    await loadProject(app.project.id);
    await loadSourceFiles();
    restoreContentPosition(position);
    const published = files.some((file) => file.published);
    toast(published
      ? `已从整合包移除 ${files.length} 个文件；玩家那边：${REMOVAL_LABELS[action]}（发布新版本后生效）`
      : `已从整合包移除 ${files.length} 个文件`);
  });
}

function chooseRemoval(files) {
  const dialog = byId("removal-dialog");
  const form = byId("removal-form");
  const published = files.filter((file) => file.published).length;
  const inCleanup = files.some((file) => file.cleanup);
  form.reset();
  byId("removal-title").textContent = files.length === 1
    ? "从整合包移除"
    : `从整合包移除 ${files.length} 个文件`;
  byId("removal-message").textContent =
    (files.length === 1 ? `${files[0].path}\n` : "")
      + "源文件会先归档到管理端的备份目录，再从整合包目录移出。"
      + (published === 0
        ? "这些文件还没有发布过，玩家那边不受影响。"
        : "玩家更新到下一个版本时怎么处理？");
  form.querySelectorAll('input[name="removalAction"]').forEach((input) => {
    const keepsCopy = input.value === "RELEASE"
      || input.value === "DELETE_KEEP_SELF_MANAGED";
    input.disabled = keepsCopy && (inCleanup || published === 0);
    input.closest(".choice").classList.toggle("disabled", input.disabled);
  });
  byId("removal-cleanup-note").hidden = !inCleanup || published === 0;
  const confirm = byId("removal-confirm");
  const sync = () => {
    const optional = form.querySelector('input[name="removalAction"]:checked')
      ?.value === "OPTIONAL";
    confirm.textContent = optional ? "选择可选内容…" : "确认移除";
    confirm.className = optional ? "primary-button" : "danger-button";
  };
  form.onchange = sync;
  sync();
  dialog.returnValue = "cancel";
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener("close", () => {
      resolve(dialog.returnValue === "confirm"
        ? form.querySelector('input[name="removalAction"]:checked')?.value || null
        : null);
    }, { once: true });
  });
}

function latestReleaseTime() {
  return (app.project?.releases || []).reduce((latest, release) => {
    const time = new Date(release.createdAt).getTime();
    return Number.isFinite(time) && time > latest ? time : latest;
  }, 0);
}

function renderDirectives() {
  const list = byId("directive-list");
  const withdrawals = (app.project?.maintenance?.withdrawals || [])
    .filter((withdrawal) => withdrawal.kind !== "REMOVAL");
  const corrections = app.project?.maintenance?.corrections || [];
  const total = withdrawals.length + corrections.length;
  byId("directive-count").textContent = total === 0
    ? "没有正在处理的问题"
    : `问题处理 ${total} 项`;
  if (total === 0) {
    list.replaceChildren(note("没有正在处理的问题。以前停止维护、留给玩家的文件，后来出问题时，也可以从历史发布中选中坏版本。"));
    return;
  }
  const published = latestReleaseTime();
  const stateOf = (createdAt) => new Date(createdAt).getTime() <= published
    ? "已随发布生效" : "等待下一次发布";
  list.replaceChildren(
    ...withdrawals.map((withdrawal) => directiveCard({
      kind: "withdrawal",
      label: "移出问题版本",
      reason: withdrawal.reason,
      lines: (withdrawal.items || []).map((item) => [item.path, item.version].filter(Boolean).join(" · ")),
      detail: `创建于 ${formatDate(withdrawal.createdAt)} · ${stateOf(withdrawal.createdAt)}`,
      revoke: () => revokeDirective("withdrawal-revoke", withdrawal.id, "结束问题处理",
        "撤销后，玩家手里的这些版本不再被移走；已经移入玩家备份的文件不会自动放回。")
    })),
    ...corrections.map((correction) => directiveCard({
      kind: "correction",
      label: correction.mode === "ONCE" ? "修正 · 各覆盖一次" : "修正 · 替换旧版本",
      reason: correction.reason,
      lines: [correction.mode === "KNOWN_BAD"
        ? `${correction.path} · 替换 ${(correction.badSha256 || []).length} 个旧版本`
        : correction.path],
      detail: `创建于 ${formatDate(correction.createdAt)} · ${stateOf(correction.createdAt)}`,
      revoke: () => revokeDirective("correction-revoke", correction.id, "撤销修正",
        "撤销后不再覆盖玩家的这个文件；已经覆盖过的不会还原。")
    }))
  );
}

function directiveCard({ kind, label, reason, lines, detail, revoke }) {
  const card = document.createElement("article");
  card.className = `directive-card ${kind}`;
  const header = document.createElement("div");
  header.className = "directive-card-header";
  const text = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = reason;
  text.append(tag(label, kind), title);
  header.append(text, actionButton("结束处理", revoke));
  const items = document.createElement("ul");
  lines.forEach((line) => {
    const item = document.createElement("li");
    item.textContent = line;
    item.title = line;
    items.append(item);
  });
  const footer = document.createElement("small");
  footer.textContent = detail;
  card.append(header, items, footer);
  return card;
}

async function revokeDirective(action, id, title, message) {
  if (!(await ask(title, message, title, true))) return;
  const result = await maintenance(action, { id }, "正在撤销",
    `${title}已保存，发布新版本后生效`);
  if (result) app.history = null;
}

function shortHash(value) {
  return value ? String(value).slice(0, 8) : "";
}

async function ensureHistory() {
  if (app.history?.projectId === app.project?.id) return app.history;
  const result = await api(
    `/api/projects/${encodeURIComponent(app.project.id)}/history`
  );
  app.history = { projectId: app.project.id, files: result.files || [] };
  return app.history;
}

function fillHistoryReleases(select, includeAll = false, preferred = select.value) {
  const releases = [...(app.project?.releases || [])]
    .sort((a, b) => Number(b.sequence) - Number(a.sequence));
  select.replaceChildren(...(includeAll ? [option("", "全部历史发布")] : []),
    ...releases.map((release) => option(release.releaseId,
      `${release.displayVersion} · #${release.sequence}`)));
  select.value = releases.some((release) => release.releaseId === preferred)
    ? preferred : includeAll ? "" : releases[0]?.releaseId || "";
}

function versionsInRelease(history, releaseId) {
  return (history?.versions || []).filter((version) => !releaseId
    || (version.releaseIds || []).includes(releaseId));
}

function historyVersionState(version) {
  if (version.withdrawnKind === "VERSION") return "问题版本处理中";
  if (version.withdrawnKind === "REMOVAL") return "已移除（自动处理）";
  return version.currentlyPublished ? "当前仍在提供" : "当前已不再提供此版本";
}

async function openFileHistory() {
  if (!app.project) return;
  if (!(app.project.releases || []).length) {
    toast("还没有发布版本，发布后可在这里管理历史文件");
    return;
  }
  let loaded = false;
  await runBusy("正在读取历史文件", async () => { await ensureHistory(); loaded = true; });
  if (!loaded) return;
  fillHistoryReleases(byId("file-history-release"));
  byId("file-history-search").value = "";
  renderFileHistory();
  byId("file-history-dialog").showModal();
}

function renderFileHistory() {
  const releaseId = byId("file-history-release").value;
  const query = byId("file-history-search").value.trim().toLocaleLowerCase("zh-CN");
  const entries = (app.history?.files || []).flatMap((history) =>
    versionsInRelease(history, releaseId).map((version) => ({ history, version })))
    .filter(({ history, version }) => !query ||
      [history.path, version.componentId, version.version].filter(Boolean).join(" ")
        .toLocaleLowerCase("zh-CN").includes(query));
  const selected = (app.project?.releases || []).find((release) => release.releaseId === releaseId);
  byId("file-history-count").textContent =
    `${selected?.displayVersion || "所选发布"} 当时提供的文件 · ${entries.length} 项`
      + (query ? "（筛选后）" : "");
  const list = byId("file-history-list");
  if (!entries.length) {
    list.replaceChildren(note(query ? "没有匹配的历史文件" : "这个发布没有托管文件"));
    return;
  }
  list.replaceChildren(...entries.map(({ history, version }) => {
    const item = document.createElement("article");
    item.className = "release-history-file";
    const info = document.createElement("div");
    const name = document.createElement("strong");
    name.textContent = history.path;
    const detail = document.createElement("small");
    detail.textContent = [version.componentId,
      version.version ? `文件版本 ${version.version}` : `内容 ${shortHash(version.sha256)}`,
      formatBytes(version.size), historyVersionState(version)].filter(Boolean).join(" · ");
    info.append(name, detail);
    const actions = document.createElement("div");
    actions.className = "history-file-actions";
    const treat = actionButton("处理这个版本", async () => {
      byId("file-history-dialog").close();
      await openCorrect({ releaseId, path: history.path, sha256: version.sha256 });
    });
    treat.disabled = Boolean(version.withdrawnBy) && version.withdrawnKind !== "REMOVAL";
    if (treat.disabled) treat.textContent = "已在处理";
    actions.append(treat);
    if (version.withdrawnKind === "REMOVAL" && version.withdrawnBy) {
      const more = document.createElement("details");
      const summary = document.createElement("summary");
      summary.textContent = "移除要求";
      const allow = actionButton("允许玩家保留", async () => {
        await revokeDirective("withdrawal-revoke", version.withdrawnBy, "允许玩家保留这个资源",
          "结束这条自动移除要求，发布后允许玩家保留或恢复副本。强制同步目录中的额外文件仍按目录规则处理。");
        await ensureHistory();
        renderFileHistory();
      });
      more.append(summary, allow);
      actions.append(more);
    }
    item.append(info, actions);
    return item;
  }));
}

function renderCorrectPaths(preferred = byId("correct-path").value) {
  const releaseId = byId("correct-release").value;
  const histories = [...(app.history?.files || [])]
    .filter((history) => versionsInRelease(history, releaseId).length)
    .sort((a, b) => a.path.localeCompare(b.path, "zh-CN"));
  byId("correct-path").replaceChildren(option("", "请选择问题文件"),
    ...histories.map((history) => option(history.path, history.path)));
  byId("correct-path").value = histories.some((history) => history.path === preferred) ? preferred : "";
}

async function openWithdraw() {
  if (!app.project) return;
  if ((app.project.releases || []).length === 0) {
    toast("还没有发布过任何版本，没有可以撤回的内容", true);
    return;
  }
  let loaded = false;
  await runBusy("正在读取发布历史", async () => {
    await ensureHistory();
    loaded = true;
  });
  if (!loaded) return;
  byId("withdraw-form").reset();
  byId("withdraw-search").value = "";
  app.withdrawSelection = new Set();
  renderWithdrawVersions();
  byId("withdraw-dialog").showModal();
}

function versionKey(path, sha256) {
  return `${path}\n${sha256}`;
}

function renderWithdrawVersions() {
  const container = byId("withdraw-versions");
  const query = byId("withdraw-search").value.trim().toLocaleLowerCase("zh-CN");
  const files = (app.history?.files || [])
    .filter((history) => history.versions.some((version) => !version.currentlyPublished))
    .filter((history) => !query || [history.path, ...history.versions.map((version) =>
      `${version.componentId || ""} ${version.version || ""}`)]
      .join(" ").toLocaleLowerCase("zh-CN").includes(query));
  if (files.length === 0) {
    container.replaceChildren(note(query
      ? "没有符合搜索条件的历史版本"
      : "没有可以撤回的历史版本（当前发布中的版本不能撤回）"));
  } else {
    const shown = files.slice(0, 80);
    container.replaceChildren(...shown.map((history) => versionGroup(history, {
      selectable: (version) => !version.currentlyPublished && !version.withdrawnBy,
      selected: (version) => app.withdrawSelection.has(
        versionKey(history.path, version.sha256)),
      onToggle: (version, checked) => {
        const key = versionKey(history.path, version.sha256);
        if (checked) app.withdrawSelection.add(key);
        else app.withdrawSelection.delete(key);
        updateWithdrawSelection();
      },
      state: (version) => version.currentlyPublished ? "当前发布中"
        : version.withdrawnBy ? "已撤回" : ""
    })));
    if (files.length > shown.length) {
      container.append(note(`还有 ${files.length - shown.length} 个文件，请输入关键字缩小范围`));
    }
  }
  updateWithdrawSelection();
}

function updateWithdrawSelection() {
  const count = app.withdrawSelection?.size || 0;
  byId("withdraw-selection").textContent = count === 0
    ? "未选择版本"
    : `已选择 ${count} 个版本`;
}

function versionGroup(history, options) {
  const group = document.createElement("section");
  group.className = "version-group";
  const title = document.createElement("strong");
  title.textContent = history.path;
  title.title = history.path;
  group.append(title);
  [...history.versions].reverse().forEach((version) => {
    const item = document.createElement("label");
    item.className = "version-item";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.disabled = !options.selectable(version);
    checkbox.checked = options.selected(version);
    checkbox.addEventListener("change", () => options.onToggle(version, checkbox.checked));
    const name = document.createElement("span");
    name.className = "version-name";
    name.textContent = version.version || `内容 ${shortHash(version.sha256)}`;
    name.title = version.sha256;
    const range = document.createElement("small");
    range.textContent = version.firstRelease === version.lastRelease
      ? `发布于 ${version.firstRelease}`
      : `${version.firstRelease} → ${version.lastRelease}`;
    const size = document.createElement("small");
    size.textContent = formatBytes(version.size);
    item.append(checkbox, name, range, size);
    const state = options.state(version);
    if (state) {
      item.append(tag(state, version.currentlyPublished ? "current" : "withdrawn"));
    }
    item.classList.toggle("disabled", checkbox.disabled);
    group.append(item);
  });
  return group;
}

async function openCorrect(context = {}) {
  if (!app.project) return;
  let loaded = false;
  await runBusy("正在读取文件历史", async () => { await ensureHistory(); await loadSourceFiles(); loaded = true; });
  if (!loaded) return;
  const form = byId("correct-form");
  form.reset();
  fillHistoryReleases(byId("correct-release"), true, context.releaseId || "");
  renderCorrectPaths(context.path || "");
  app.correctSelection = new Set();
  const selectedHistory = (app.history?.files || []).find((history) => history.path === context.path);
  if (context.sha256 && versionsInRelease(selectedHistory, context.releaseId)
    .some((version) => version.sha256 === context.sha256
      && (!version.withdrawnBy || version.withdrawnKind === "REMOVAL"))) {
    app.correctSelection.add(context.sha256);
  }
  renderCorrectVersions();
  byId("correct-dialog").showModal();
}

function renderCorrectVersions() {
  const form = byId("correct-form");
  const action = form.querySelector('input[name="action"]:checked')?.value;
  const mode = form.querySelector('input[name="mode"]:checked')?.value;
  byId("problem-replacement").hidden = action !== "REPLACE";
  const path = byId("correct-path").value;
  const history = (app.history?.files || []).find((entry) => entry.path === path);
  const candidates = app.sourceFiles?.files || [];
  const target = byId("correct-target");
  const chosen = target.value;
  target.replaceChildren(option("", "请选择准备好的正确文件"), ...candidates.map((file) => option(file.path, file.path)));
  const suggested = candidates.find((file) => file.path === path) || candidates.find((file) =>
    file.componentId && history?.versions.some((version) => version.componentId === file.componentId));
  target.value = candidates.some((file) => file.path === chosen) ? chosen : suggested?.path || "";
  const container = byId("correct-versions");
  container.hidden = action === "REPLACE" && mode === "ONCE";
  if (container.hidden) return;
  if (!history) { container.replaceChildren(note("请选择问题文件，再勾选有问题的历史版本")); return; }
  const versions = versionsInRelease(history, byId("correct-release").value);
  container.replaceChildren(versionGroup({ ...history, versions }, {
    selectable: (version) => !version.withdrawnBy || version.withdrawnKind === "REMOVAL",
    selected: (version) => app.correctSelection.has(version.sha256),
    onToggle: (version, checked) => { if (checked) app.correctSelection.add(version.sha256); else app.correctSelection.delete(version.sha256); },
    state: (version) => version.withdrawnKind === "REMOVAL" ? "已移除，仍可标记问题版本"
      : version.withdrawnBy ? "已在处理" : version.currentlyPublished ? "上次发布" : "历史版本"
  }));
}

function bindContent() {
  byId("file-history-form").addEventListener("submit", (event) => event.preventDefault());
  byId("open-file-history").addEventListener("click", openFileHistory);
  byId("file-history-release").addEventListener("change", renderFileHistory);
  byId("file-history-search").addEventListener("input", renderFileHistory);
  ["close-file-history", "finish-file-history"].forEach((id) =>
    byId(id).addEventListener("click", () => byId("file-history-dialog").close()));
  byId("content-filter").addEventListener("change", renderSourceFiles);
  byId("selection-preset").addEventListener("change", async (event) => {
    const value = event.target.value;
    event.target.value = "";
    if (!value) return;
    const files = selectedSourceFiles();
    if (files.length === 0) return;
    await applyPresets(
      files.map((file) => ({ path: file.path, directory: false })), value);
  });
  byId("selection-add-group").addEventListener("click", async () => {
    const files = selectedSourceFiles();
    if (files.length === 0) return;
    await addFilesToGroup(files,
      `把所选的 ${files.length} 个文件加入哪组可选内容？模组会按 modid 加入，改名后的新版本仍然属于这组。`);
  });
  byId("selection-remove-group").addEventListener("click", () =>
    removeFilesFromGroups(selectedSourceFiles()));
  byId("create-group").addEventListener("click", () => openGroupDialog(null));
  byId("group-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const dialog = byId("group-dialog");
    const form = event.currentTarget;
    if (event.submitter?.value === "cancel") {
      dialog.close();
      return;
    }
    if (!form.reportValidity()) return;
    const data = new FormData(form);
    const id = textValue(data, "id");
    dialog.close();
    await maintenance("group", {
      id: id || null,
      title: textValue(data, "title"),
      description: textValue(data, "description"),
      defaultInstall: form.elements.defaultInstall.checked
    }, "正在保存可选内容",
    id ? "可选内容已保存" : "可选内容已创建，在上方列表中选择文件加入");
  });

  byId("open-withdraw").addEventListener("click", openWithdraw);
  byId("withdraw-search").addEventListener("input", renderWithdrawVersions);
  byId("withdraw-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const dialog = byId("withdraw-dialog");
    if (event.submitter?.value !== "confirm") {
      dialog.close();
      return;
    }
    const reason = textValue(new FormData(event.currentTarget), "reason");
    if (!app.withdrawSelection?.size) {
      toast("请至少选择一个要撤回的版本", true);
      return;
    }
    if (!reason) {
      toast("请填写原因，玩家会在更新器里看到它", true);
      return;
    }
    const versions = [...app.withdrawSelection].map((key) => {
      const [path, sha256] = key.split("\n");
      return { path, sha256 };
    });
    dialog.close();
    const result = await maintenance("withdraw", { reason, versions },
      "正在保存撤回", `已撤回 ${versions.length} 个版本，发布新版本后生效`);
    if (result) app.history = null;
  });

  byId("open-correct").addEventListener("click", () => openCorrect());
  byId("correct-release").addEventListener("change", () => {
    app.correctSelection = new Set();
    renderCorrectPaths();
    renderCorrectVersions();
  });
  byId("correct-path").addEventListener("change", () => {
    app.correctSelection = new Set();
    renderCorrectVersions();
  });
  byId("correct-form").querySelectorAll('input[name="action"]').forEach((input) => input.addEventListener("change", renderCorrectVersions));
  byId("correct-form").querySelectorAll('input[name="mode"]').forEach((input) => {
    input.addEventListener("change", renderCorrectVersions);
  });
  byId("correct-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const dialog = byId("correct-dialog");
    const form = event.currentTarget;
    if (event.submitter?.value !== "confirm") { dialog.close(); return; }
    const data = new FormData(form);
    const path = textValue(data, "path");
    const reason = textValue(data, "reason");
    const action = textValue(data, "action");
    const mode = textValue(data, "mode");
    if (!path || !reason) { toast("请选择问题文件并填写原因", true); return; }
    if ((action === "REMOVE" || mode === "KNOWN_BAD") && !app.correctSelection?.size) { toast("请勾选有问题的历史版本", true); return; }
    const target = textValue(data, "target");
    if (action === "REPLACE" && !target) { toast("请先在文件列表中准备正确文件，再选择它", true); return; }
    dialog.close();
    const hashes = [...(app.correctSelection || [])];
    const result = action === "REMOVE"
      ? await maintenance("withdraw", { reason, versions: hashes.map((sha256) => ({ path, sha256 })) }, "正在准备移出问题版本", "处理已保存，检查并发布后生效")
      : await maintenance("correct", { path: target, mode, reason, badSha256: mode === "KNOWN_BAD" ? hashes : [] }, "正在准备替换问题版本", "处理已保存，检查并发布后生效");
    if (result) app.history = null;
  });
}

function captureContentPosition() {
  return {
    content: document.querySelector(".content")?.scrollTop || 0,
    table: document.querySelector(".content-table")?.scrollTop || 0
  };
}

function restoreContentPosition(position) {
  window.requestAnimationFrame(() => {
    const content = document.querySelector(".content");
    const table = document.querySelector(".content-table");
    if (content) content.scrollTop = position.content;
    if (table) table.scrollTop = position.table;
  });
}

function renderProjectForm() {
  if (!app.project) return;
  const form = byId("project-form");
  byId("project-identity").textContent =
    `${app.project.displayName} · ${app.project.id}`;
  setFormValue(form, "displayName", app.project.displayName);
  setFormValue(form, "sourceDirectory", app.project.sourceDirectory);
  setFormValue(form, "publicBaseUrl", app.project.publicBaseUrl);
}

function renderPersonalizationForm() {
  if (!app.project) return;
  if (app.personalDirty && app.personalDirtyProjectId === app.project.id) return;
  app.personalDirty = false;
  const form = byId("personalization-form");
  const branding = app.project.branding || {};
  byId("personalization-identity").textContent =
    `${app.project.displayName} · ${app.project.id}`;
  setFormValue(form, "productName", branding.productName);
  setFormValue(form, "brandName", branding.brandName || "梦鱼更新器");
  setFormValue(
    form,
    "brandEnglishName",
    branding.brandEnglishName || "DreamingFish"
  );
  setFormValue(form, "welcomeText", branding.welcomeText || "欢迎来到");
  setFormValue(form, "subtitle", branding.subtitle);
  setFormValue(form, "serverAddress", branding.serverAddress);
  setColor(form, "accentColor", branding.accentColor);
  setColor(
    form,
    "secondaryAccentColor",
    branding.secondaryAccentColor
  );
  setColor(form, "titleColor", branding.titleColor);
  setColor(form, "topBarColor", branding.topBarColor);
  setOpacity(form, "topBarOpacity", branding.topBarOpacity);
  setColor(form, "cardColor", branding.cardColor);
  clearPendingCover();
  form.elements.removeCover.checked = false;
  const legacy = branding.contentPages == null;
  byId("legacy-news-note").hidden = !legacy;
  const pages = legacyPlayerPages(branding);
  if (app.playerEditorProjectId !== app.project.id) {
    app.playerEditorProjectId = app.project.id;
    app.expandedPlayerPages.clear();
    app.expandedPlayerArticles.clear();
    if (pages[0]) app.expandedPlayerPages.add(playerPageEditorKey(pages[0], 0));
  }
  renderPlayerPageEditor(pages);
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
        const updated = await api(`/api/projects/${encodeURIComponent(app.project.id)}/music/${encodeURIComponent(track.id)}`, { method: "DELETE" });
        app.project.branding = updated.branding;
        renderMusicTracks(updated.branding?.musicTracks || []);
        updatePlayerPreview();
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
    const pageKey = playerPageEditorKey(page, index);
    const pageExpanded = app.expandedPlayerPages.has(pageKey);
    const card = document.createElement("section");
    card.className = "player-news-card player-page-card";
    card.classList.toggle("collapsed", !pageExpanded);
    card.dataset.pageIndex = String(index);
    // Keep both editing modes in memory. Only one body is visible at a time,
    // but switching between a normal page and an announcement page must never
    // discard the user's unpublished Markdown or announcement drafts.
    card.pageDraft = {
      ...page,
      markdown: String(page.markdown || ""),
      articles: Array.isArray(page.articles)
        ? page.articles.map((article) => ({ ...article }))
        : []
    };
    const header = document.createElement("div");
    header.className = "player-news-card-header";
    const headingGroup = document.createElement("div");
    headingGroup.className = "player-editor-card-heading";
    const heading = document.createElement("strong");
    heading.textContent = `页面 ${index + 1} · ${page.navigationLabel || "未命名"}`;
    heading.title = heading.textContent;
    const typeLabel = document.createElement("span");
    typeLabel.className = `player-editor-type ${page.announcementPage ? "announcement" : "content"}`;
    typeLabel.textContent = page.announcementPage ? "公告页" : "普通页面";
    headingGroup.append(heading, typeLabel);
    const actions = document.createElement("div");
    actions.className = "button-row player-editor-card-actions";
    const toggle = actionButton(pageExpanded ? "收起" : "展开", () => {
      setPlayerPageExpanded(pageKey, !app.expandedPlayerPages.has(pageKey), card, fields, toggle);
    });
    toggle.classList.add("player-editor-toggle");
    toggle.setAttribute("aria-expanded", String(pageExpanded));
    const moveUp = actionButton("上移", () => movePlayerPage(index, -1));
    moveUp.disabled = index === 0;
    moveUp.title = "将这个页面向前移动";
    const moveDown = actionButton("下移", () => movePlayerPage(index, 1));
    moveDown.disabled = index === pages.length - 1;
    moveDown.title = "将这个页面向后移动";
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "danger-button";
    remove.textContent = "删除";
    remove.addEventListener("click", () => {
      const next = readPlayerPageEditor();
      next.splice(index, 1);
      app.expandedPlayerPages.delete(pageKey);
      removePlayerArticleExpansion(pageKey);
      byId("legacy-news-note").hidden = true;
      renderPlayerPageEditor(next);
    });
    actions.append(toggle, moveUp, moveDown, remove);
    header.append(headingGroup, actions);
    const fields = document.createElement("div");
    fields.className = "player-news-card-fields";
    fields.hidden = !pageExpanded;
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
      const nextPageKey = playerPageEditorKey(next[index], index);
      if (nextPageKey !== pageKey) {
        app.expandedPlayerPages.delete(pageKey);
        removePlayerArticleExpansion(pageKey);
      }
      app.expandedPlayerPages.add(nextPageKey);
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
    const articleKey = playerArticleEditorKey(page, pageIndex, article, articleIndex);
    const articleExpanded = app.expandedPlayerArticles.has(articleKey);
    const item = document.createElement("div");
    item.className = "announcement-editor-card";
    item.classList.toggle("collapsed", !articleExpanded);
    item.dataset.articleIndex = String(articleIndex);
    const header = document.createElement("div");
    header.className = "player-news-card-header";
    const headingGroup = document.createElement("div");
    headingGroup.className = "player-editor-card-heading announcement-editor-heading";
    const title = document.createElement("strong");
    title.textContent = `新闻 ${articleIndex + 1} · ${article.title || "未命名"}`;
    title.title = title.textContent;
    const date = document.createElement("span");
    date.className = "player-editor-card-summary";
    date.textContent = article.publishedOn || "尚未填写日期";
    headingGroup.append(title, date);
    const actions = document.createElement("div");
    actions.className = "button-row player-editor-card-actions";
    const toggle = actionButton(articleExpanded ? "收起" : "展开", () => {
      setPlayerArticleExpanded(articleKey, !app.expandedPlayerArticles.has(articleKey), item, fields, toggle);
    });
    toggle.classList.add("player-editor-toggle");
    toggle.setAttribute("aria-expanded", String(articleExpanded));
    const remove = actionButton("删除新闻", () => removeAnnouncement(pageIndex, articleIndex));
    remove.className = "danger-button";
    actions.append(toggle, remove);
    header.append(headingGroup, actions);
    const fields = document.createElement("div");
    fields.className = "player-news-card-fields";
    fields.hidden = !articleExpanded;
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

function playerPageEditorKey(page, index) {
  return String(page?.id || `page-index-${index}`);
}

function playerArticleEditorKey(page, pageIndex, article, articleIndex) {
  return `${playerPageEditorKey(page, pageIndex)}::${String(article?.id || `article-index-${articleIndex}`)}`;
}

function setPlayerPageExpanded(key, expanded, card, fields, button) {
  if (expanded) app.expandedPlayerPages.add(key);
  else app.expandedPlayerPages.delete(key);
  card.classList.toggle("collapsed", !expanded);
  fields.hidden = !expanded;
  button.textContent = expanded ? "收起" : "展开";
  button.setAttribute("aria-expanded", String(expanded));
}

function setPlayerArticleExpanded(key, expanded, card, fields, button) {
  if (expanded) app.expandedPlayerArticles.add(key);
  else app.expandedPlayerArticles.delete(key);
  card.classList.toggle("collapsed", !expanded);
  fields.hidden = !expanded;
  button.textContent = expanded ? "收起" : "展开";
  button.setAttribute("aria-expanded", String(expanded));
}

function removePlayerArticleExpansion(pageKey) {
  const prefix = `${pageKey}::`;
  [...app.expandedPlayerArticles]
    .filter((key) => key.startsWith(prefix))
    .forEach((key) => app.expandedPlayerArticles.delete(key));
}

function readPlayerPageEditor() {
  return [...byId("player-page-editor").querySelectorAll(".player-page-card")]
    .map((card) => {
      const draft = card.pageDraft || {};
      const value = (name) => String(card.querySelector(`[data-page-field="${name}"]`)?.value || "").trim();
      const announcementPage = Boolean(card.querySelector('[data-page-field="announcementPage"]')?.checked);
      const articleCards = [...card.querySelectorAll(".announcement-editor-card")];
      const visibleArticles = articleCards.map((item) => {
        const articleValue = (name) => String(item.querySelector(`[data-article-field="${name}"]`)?.value || "").trim();
        return {
          id: articleValue("id"), title: articleValue("title"), summary: articleValue("summary"),
          publishedOn: articleValue("publishedOn"), coverUrl: articleValue("coverUrl"),
          markdown: String(item.querySelector('[data-article-body-field="markdown"]')?.value || "").trim()
        };
      });
      const markdownInput = card.querySelector('[data-page-body-field="markdown"]');
      return {
        id: value("id"), navigationLabel: value("navigationLabel"), announcementPage,
        eyebrow: value("eyebrow"), title: value("title"), lead: value("lead"),
        markdown: markdownInput == null
          ? String(draft.markdown || "")
          : String(markdownInput.value || "").trim(),
        articles: articleCards.length === 0
          ? (Array.isArray(draft.articles)
              ? draft.articles.map((article) => ({ ...article }))
              : [])
          : visibleArticles
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
  app.expandedPlayerPages.add(playerPageEditorKey(pages[pageIndex], pageIndex));
  app.expandedPlayerArticles.add(playerArticleEditorKey(
    pages[pageIndex], pageIndex, articles[articles.length - 1], articles.length - 1
  ));
  renderPlayerPageEditor(pages);
}

function removeAnnouncement(pageIndex, articleIndex) {
  const pages = readPlayerPageEditor();
  const page = pages[pageIndex];
  const article = page.articles[articleIndex];
  app.expandedPlayerArticles.delete(playerArticleEditorKey(page, pageIndex, article, articleIndex));
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
    page.articles = Array.isArray(page.articles) ? page.articles : [];
    if (page.articles.length > 50) throw new Error(`第 ${index + 1} 个页面最多保留 50 条新闻。`);
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
  await applyImportedPlayerPages(parsed);
}

async function applyImportedPlayerPages(parsed) {
  const pages = validateImportedPlayerPages(parsed);
  const accepted = await ask(
    "导入玩家端页面配置？",
    `已读取 ${pages.length} 个页面。确认后会替换当前编辑区，仍需点击“保存个性化设置”才会写入项目。`,
    "导入并预览"
  );
  if (!accepted) return;
  byId("legacy-news-note").hidden = true;
  app.expandedPlayerPages.clear();
  app.expandedPlayerArticles.clear();
  if (pages[0]) app.expandedPlayerPages.add(playerPageEditorKey(pages[0], 0));
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
      welcomeText: value("welcomeText", "欢迎来到"),
      subtitle: value("subtitle", "Minecraft 整合包更新"),
      serverAddress: value("serverAddress", ""),
      coverObject: app.project.branding?.coverObject || null,
      accentColor: value("accentColorText", "#2ee8df"),
      secondaryAccentColor: value("secondaryAccentColorText", "#b06cff"),
      titleColor: value("titleColorText", "#fff8dc"),
      topBarColor: value("topBarColorText", "#030708"),
      topBarOpacity: opacityValue(form, "topBarOpacity"),
      cardColor: value("cardColorText", "#030708"),
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
  byId("preview-stale").hidden = !preview || !app.project?.previewStale;
  byId("publish-ack-field").hidden = !preview?.requiresPlayerUpgrade;
  renderWarnings(preview);
  renderPolicyChanges(preview);
  renderPublishBadge();
  if (!preview) {
    summary.forEach((element) => {
      element.textContent = "--";
    });
    byId("preview-time").textContent = "尚未检查";
    byId("preview-empty").textContent =
      "点击“检查整合包内容”，查看这次发布会给玩家带来哪些变化";
    byId("removal-bulk").hidden = true;
    setRows("preview-table", "preview-empty", []);
    return;
  }
  const changes = (preview.changes || [])
    .filter((change) => change.kind !== "POLICY_CHANGED");
  const count = (kind) => changes.filter((change) => change.kind === kind).length;
  const removals = changes.filter((change) => change.kind === "REMOVED");
  removals.forEach((change) => {
    if (change.insideCleanup && !change.removalAction) change.removalAction = "DELETE";
  });
  const undecided = removals.filter((change) => !change.removalAction).length;
  const policies = preview.policyChanges || [];
  summary[0].textContent = String(count("ADDED"));
  summary[1].textContent = String(count("MODIFIED"));
  summary[2].textContent = String(removals.length);
  summary[3].textContent = String(policies.length);
  summary[4].textContent = formatBytes(preview.estimatedDownloadBytes);
  byId("preview-time").textContent =
    `检查于 ${formatDate(preview.createdAt)} · 共 ${preview.managedFiles} 个文件，`
      + formatBytes(preview.totalManagedBytes)
      + (removals.length > 0
        ? undecided > 0
          ? ` · 还有 ${undecided} 个移除的文件需要决定`
          : " · 移除的文件都已决定"
        : "");
  byId("removal-bulk").hidden = removals.length === 0;
  byId("removal-bulk-label").textContent = `移除的 ${removals.length} 个文件：`;
  byId("preview-empty").textContent = changes.length === 0 && policies.length === 0
    ? "本次没有修改"
    : "文件内容没有变化，只有下方的维护规则变化";
  const order = { REMOVED: 0, ADDED: 1, MODIFIED: 2, METADATA_CHANGED: 3 };
  const rows = [...changes]
    .sort((left, right) => (order[left.kind] ?? 9) - (order[right.kind] ?? 9)
      || compareFilePath(left, right))
    .map(previewRow);
  setRows("preview-table", "preview-empty", rows);
}

function previewRow(change) {
  const badge = document.createElement("span");
  badge.className = `change-badge ${change.kind.toLowerCase()}`;
  badge.textContent = kindNames[change.kind] || change.kind;
  const file = document.createElement("td");
  file.className = "preview-file-cell";
  file.title = change.path;
  const name = document.createElement("strong");
  name.textContent = change.displayName
    || String(change.path).split("/").filter(Boolean).at(-1);
  const path = document.createElement("small");
  path.textContent = change.path;
  file.append(name, path);
  const versions = change.kind === "MODIFIED" && change.previousVersion
      && change.version && change.previousVersion !== change.version
    ? `${change.previousVersion} → ${change.version}`
    : change.kind === "ADDED" ? change.version : null;
  if (versions) {
    const version = document.createElement("small");
    version.className = "preview-version";
    version.textContent = versions;
    file.append(version);
  }
  const maintenanceCell = document.createElement("td");
  if (change.preset) maintenanceCell.append(presetTag(change.preset));
  if (change.optionalGroup) {
    maintenanceCell.append(tag(groupById(change.optionalGroup)?.title
      || change.optionalGroup, "group", "可选内容"));
  }
  if (!change.preset && !change.optionalGroup) {
    maintenanceCell.textContent = "--";
    maintenanceCell.className = "muted-cell";
  }
  const action = document.createElement("td");
  if (change.kind === "REMOVED") {
    action.append(removalControl(change));
  } else {
    action.textContent = change.kind === "METADATA_CHANGED" ? "只更新模组信息，无需下载" : "--";
    action.className = "muted-cell";
  }
  return row([
    badge,
    file,
    maintenanceCell,
    change.downloadSize > 0 ? formatBytes(change.downloadSize) : "--",
    action
  ]);
}

function removalControl(change) {
  const wrapper = document.createElement("div");
  wrapper.className = "removal-control";
  const select = document.createElement("select");
  select.className = "removal-action";
  select.dataset.path = change.path;
  select.append(option("", "请选择…"));
  Object.entries(REMOVAL_LABELS).forEach(([value, label]) => {
    if (change.insideCleanup && value !== "DELETE") return;
    select.append(option(value, label));
  });
  select.value = change.removalAction || "";
  select.classList.toggle("undecided", !change.removalAction);
  select.title = change.insideCleanup
    ? "这个文件位于强制同步目录中，玩家副本会移入备份；要留给玩家，请先将目录改为普通同步"
    : REMOVAL_HELP[change.removalAction] || "选择玩家更新到这个版本时怎么处理";
  select.addEventListener("change", () => {
    change.removalAction = select.value || null;
    renderPreview();
  });
  wrapper.append(select);
  return wrapper;
}

async function makeOptional(change) {
  const groupId = await pickGroup(
    `“${change.displayName || change.path}”会放回整合包，由玩家决定是否安装。加入哪组可选内容？`);
  if (!groupId) return;
  const result = await maintenance("make-optional",
    { path: change.path, groupId }, "正在放回整合包并重新检查");
  if (!result) return;
  await runBusy("正在刷新检查结果", async () => {
    await loadProject(app.project.id);
    if (app.view === "content") await loadSourceFiles();
    toast(`已改为可选内容：${change.path}`);
  });
}

function setAllRemovalActions(action) {
  const preview = app.project?.preview;
  if (!preview) return;
  preview.changes
    .filter((change) => change.kind === "REMOVED")
    .forEach((change) => {
      change.removalAction = change.insideCleanup ? "DELETE" : action;
    });
  renderPreview();
}

function renderWarnings(preview) {
  const list = byId("preview-warnings");
  const warnings = preview?.warnings || [];
  list.hidden = warnings.length === 0;
  const byCode = new Map();
  warnings.forEach((warning) => {
    if (!byCode.has(warning.code)) byCode.set(warning.code, []);
    byCode.get(warning.code).push(warning);
  });
  list.replaceChildren(...[...byCode.entries()].map(([code, items]) => {
    const item = document.createElement("div");
    item.className = `warning-item${code === "PLAYER_PROGRAM_REQUIRED" ? " critical" : ""}`;
    const text = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = (WARNING_TITLES[code] || "请注意")
      + (items.length > 1 ? `（${items.length} 项）` : "");
    text.append(title);
    if (items.length === 1) {
      const message = document.createElement("span");
      message.textContent = items[0].message;
      text.append(message);
    } else {
      const details = document.createElement("ul");
      items.slice(0, 8).forEach((warning) => {
        const line = document.createElement("li");
        line.textContent = warning.message;
        details.append(line);
      });
      if (items.length > 8) {
        const more = document.createElement("li");
        more.textContent = `还有 ${items.length - 8} 项`;
        details.append(more);
      }
      text.append(details);
    }
    item.append(text);
    if (code === "PLAYER_PROGRAM_REQUIRED") {
      item.append(actionButton("去发布玩家端", () => showView("player")));
    }
    return item;
  }));
}

function renderPolicyChanges(preview) {
  const container = byId("policy-change-list");
  const changes = preview?.policyChanges || [];
  container.hidden = changes.length === 0;
  if (changes.length === 0) {
    container.replaceChildren();
    return;
  }
  const sections = new Map([
    ["维护方式", []], ["清理多余文件", []], ["可选内容", []], ["问题处理", []], ["其他", []]
  ]);
  const presetGroups = new Map();
  changes.forEach((change) => {
    switch (change.kind) {
      case "PRESET": {
        const key = `${change.previous || ""}>${change.current || ""}`;
        if (!presetGroups.has(key)) {
          presetGroups.set(key, { previous: change.previous, current: change.current, paths: [] });
        }
        presetGroups.get(key).paths.push(change.subject);
        break;
      }
      case "CLEANUP_DIRECTORY":
        sections.get("清理多余文件").push(change.current
          ? `开始清理 ${change.subject}/：玩家自己添加的文件会移入备份`
          : `不再清理 ${change.subject}/ 中玩家自己添加的文件`);
        break;
      case "OPTIONAL_GROUP":
        sections.get("可选内容").push(change.previous == null
          ? `新增可选内容：${change.current}`
          : change.current == null
            ? `不再提供可选内容：${change.previous}（里面的文件变回普通内容）`
            : `可选内容调整：${change.previous} → ${change.current}`);
        break;
      case "OPTIONAL_MEMBERSHIP":
        sections.get("可选内容").push(
          `${change.subject}：${groupLabel(change.previous)} → ${groupLabel(change.current)}`);
        break;
      case "WITHDRAWAL":
        sections.get("问题处理").push(withdrawalLine(change));
        break;
      case "CORRECTION":
        sections.get("问题处理").push(correctionLine(change));
        break;
      case "CORRECTION_DROPPED":
        sections.get("问题处理").push(
          `修正不会生效：${change.previous} 已不在整合包中`);
        break;
      default:
        sections.get("其他").push(`${change.kind}：${change.subject}`);
    }
  });
  presetGroups.forEach((group) => {
    const transition = `${behaviorName(group.previous)} → ${behaviorName(group.current)}`;
    sections.get("维护方式").push(group.paths.length <= 3
      ? `${group.paths.join("、")}：${transition}`
      : `${group.paths.length} 个文件：${transition}（${group.paths.slice(0, 3).join("、")} 等）`);
  });
  const heading = document.createElement("h3");
  heading.textContent = `维护规则变化（${changes.length} 项）`;
  const blocks = [...sections.entries()]
    .filter(([, lines]) => lines.length > 0)
    .map(([label, lines]) => {
      const block = document.createElement("section");
      const title = document.createElement("strong");
      title.textContent = label;
      const list = document.createElement("ul");
      lines.forEach((line) => {
        const item = document.createElement("li");
        item.textContent = line;
        list.append(item);
      });
      block.append(title, list);
      return block;
    });
  container.replaceChildren(heading, ...blocks);
}

function behaviorName(value) {
  if (!value) return "新文件";
  if (value === "LEGACY_MISSING_ONLY") return "旧版：只补齐缺失";
  return PRESETS[value]?.label || value;
}

function groupLabel(id) {
  if (!id) return "不在可选内容中";
  return `可选内容“${groupById(id)?.title || id}”`;
}

function withdrawalLine(change) {
  const withdrawal = (app.project?.maintenance?.withdrawals || [])
    .find((item) => item.id === change.subject);
  if (!change.current) return `撤销撤回：${change.subject}`;
  return withdrawal
    ? `撤回问题版本：${withdrawal.reason}（${(withdrawal.items || []).length} 个版本会从玩家那里移入备份）`
    : `撤回问题版本：${change.subject}`;
}

function correctionLine(change) {
  const correction = (app.project?.maintenance?.corrections || [])
    .find((item) => item.id === change.subject);
  if (!change.current) return `撤销修正：${change.subject}`;
  return correction
    ? `修正玩家文件 ${correction.path}：${correction.reason}`
    : `修正玩家文件：${change.subject}`;
}

function renderPublishBadge() {
  const badge = byId("publish-nav-badge");
  const preview = app.project?.preview;
  let text = "";
  if (preview && app.project?.previewStale) {
    text = "待检查";
  } else if (preview && ((preview.changes || []).length > 0
      || (preview.policyChanges || []).length > 0)) {
    text = "待发布";
  }
  badge.textContent = text;
  badge.hidden = !text;
  renderWorkflow();
}

async function scanProject() {
  if (!app.project) return;
  await runBusy("正在检查整合包内容", async () => {
    await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/scan`,
      { method: "POST", body: {} }
    );
    await loadProject(app.project.id);
    const preview = app.project.preview;
    toast(preview?.changes.length === 0 && (preview?.policyChanges || []).length === 0
      ? "检查完成，本次没有修改"
      : "检查完成");
  });
}

function bindPublish() {
  byId("release-all-button").addEventListener("click", () => {
    setAllRemovalActions("RELEASE");
  });
  byId("delete-all-button").addEventListener("click", () => {
    setAllRemovalActions("DELETE");
  });
  byId("keep-self-managed-all-button").addEventListener("click", () => {
    setAllRemovalActions("DELETE_KEEP_SELF_MANAGED");
  });
  byId("scan-button").addEventListener("click", scanProject);
  const form = byId("publish-form");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    if (!app.project.preview) {
      toast("请先检查整合包内容", true);
      return;
    }
    if (app.project.previewStale) {
      toast("检查结果已过期，请先重新检查整合包内容", true);
      return;
    }
    const confirmedPreview = structuredClone(app.project.preview);
    const confirmedProjectId = app.project.id;
    const changes = confirmedPreview.changes
      .filter((change) => change.kind !== "POLICY_CHANGED");
    const removals = changes.filter((change) => change.kind === "REMOVED");
    if (removals.some((change) => !change.removalAction)) {
      toast("请先决定每个移除的文件在玩家那边怎么处理", true);
      return;
    }
    const acknowledged = form.elements.acknowledgePlayerUpgrade.checked;
    if (confirmedPreview.requiresPlayerUpgrade && !acknowledged) {
      toast(`这次发布需要玩家端 ${confirmedPreview.policyPlayerVersion} 或更新版本。`
        + "请先在“玩家端程序”中发布新版玩家端，或勾选确认玩家会获得新版玩家端。", true);
      return;
    }
    const data = new FormData(form);
    const payload = {
      displayVersion: textValue(data, "displayVersion"),
      minimumPlayerVersion: textValue(data, "minimumPlayerVersion"),
      changelog: textValue(data, "changelog"),
      previewId: confirmedPreview.previewId,
      previewDigest: confirmedPreview.previewDigest,
      acknowledgePlayerUpgrade: acknowledged
    };
    const count = (kind) => changes.filter((change) => change.kind === kind).length;
    const lines = [
      `显示版本：${payload.displayVersion}`,
      `新增 ${count("ADDED")} · 更新 ${count("MODIFIED")} · 移除 ${removals.length}`
        + ` · 规则变化 ${(confirmedPreview.policyChanges || []).length}`,
      ...Object.entries(REMOVAL_LABELS).map(([action, label]) => {
        const total = removals.filter((change) => change.removalAction === action).length;
        return total > 0 ? `${label}：${total} 个` : null;
      }).filter(Boolean),
      "发布后该版本内容不可修改。"
    ];
    const accepted = await ask("发布新版本", lines.join("\n"), "确认发布");
    if (!accepted) return;
    await runBusy("正在签名并发布整合包", async () => {
      if (removals.length > 0) {
        const savedPreview = await api(
          `/api/projects/${encodeURIComponent(confirmedProjectId)}/removals`,
          {
            method: "POST",
            body: {
              previewId: payload.previewId,
              previewDigest: payload.previewDigest,
              decisions: removals.map((change) => ({
                path: change.path,
                action: change.removalAction
              }))
            }
          }
        );
        payload.previewDigest = savedPreview.previewDigest;
      }
      const release = await api(
        `/api/projects/${encodeURIComponent(confirmedProjectId)}/publish`,
        { method: "POST", body: payload }
      );
      form.reset();
      form.elements.minimumPlayerVersion.value = "0.2.0";
      app.history = null;
      await refreshState(confirmedProjectId);
      showPublishedToast(release, `版本 ${release.displayVersion} 已发布`);
    });
  });
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
    program.version === app.project?.currentProgramVersion ? tag(`${program.version} · 当前选中`, "published") : program.version,
    program.platform,
    formatDate(program.createdAt),
    hashCell(program.manifestSha256)
  ]));
  setRows("program-table", "program-empty", rows);
}

function renderInstanceReleases() {
  const releases = app.project?.releases || [];
  const downloadButton = byId("download-deployment-button");
  if (downloadButton) downloadButton.disabled = releases.length === 0;
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
  bindWorkspaceWorkflow();
  byId("theme-toggle").addEventListener("click", () => {
    const current = document.documentElement.dataset.theme === "light"
      ? "light"
      : "dark";
    applyTheme(current === "light" ? "dark" : "light", true);
  });
  document.querySelectorAll(".nav-button").forEach((button) => {
    button.addEventListener("click", async () => {
      const needsSourceFiles = button.dataset.view === "content";
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
    if (app.personalDirty && app.personalDirtyProjectId === app.project?.id && event.target.value !== app.project?.id) {
      if (!await ask("切换项目", "当前个性化内容还未保存，切换项目会丢弃这些输入。", "切换并丢弃输入")) {event.target.value=app.project.id; return;}
      app.personalDirty=false;
    }
    await runBusy("正在切换项目", async () => {
      await loadProject(event.target.value);
      if (app.view === "content") await loadSourceFiles();
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
  byId("service-restart").addEventListener("click", async () => {
    const accepted = await ask(
      "重启下载服务",
      "下载服务会短暂停止，然后由当前 Web 管理端重新启动。正在下载的玩家可能需要自动重试。",
      "重启服务",
      true
    );
    if (!accepted) return;
    await runBusy("正在重启 HTTP 文件服务", async () => {
      await api("/api/public-service/restart", { method: "POST", body: {} });
      await refreshState();
      toast("HTTP 文件服务已重启");
    });
  });

  bindProjectCreate();
  bindErrorDialog();
  bindProjectForm();
  bindPersonalizationForm();
  bindPathPickers();
  bindSourceFiles();
  bindContent();
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
  const downloadButton = byId("download-deployment-button");
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
  downloadButton.addEventListener("click", async () => {
    if (!app.project) return;
    const platformField = form.elements.platform;
    const releaseField = form.elements.releaseId;
    if (!platformField.reportValidity() || !releaseField.reportValidity()) return;
    const formData = new FormData(form);
    const payload = {
      platform: textValue(formData, "platform"),
      releaseId: textValue(formData, "releaseId")
    };
    const selected = app.project.releases.find(
      (release) => release.releaseId === payload.releaseId
    );
    const accepted = await ask(
      "生成并下载首次部署包",
      `整合包基线：${selected?.displayVersion || payload.releaseId}\n`
        + "管理端会在临时目录生成 ZIP，下载完成后自动清理临时文件。",
      "生成并下载"
    );
    if (!accepted) return;
    await runBusy("正在生成并准备下载", async () => {
      const headers = new Headers({
        Accept: "application/zip",
        "Content-Type": "application/json"
      });
      if (app.token) headers.set("X-DFS-Token", app.token);
      const response = await fetch(
        `/api/projects/${encodeURIComponent(app.project.id)}/deployment/download`,
        {
          method: "POST",
          headers,
          credentials: "same-origin",
          body: JSON.stringify(payload)
        }
      );
      if (!response.ok) {
        const contentType = response.headers.get("Content-Type") || "";
        let message = `请求失败：HTTP ${response.status}`;
        if (contentType.includes("application/json")) {
          const data = await response.json().catch(() => null);
          message = data?.message || message;
        }
        throw new Error(message);
      }
      const blob = await response.blob();
      if (blob.size === 0) throw new Error("服务器返回了空的部署包");
      const disposition = response.headers.get("Content-Disposition") || "";
      const match = disposition.match(/filename="([^"]+)"/i);
      const fallback = `${app.project.id}-player-deployment-${selected?.displayVersion || "latest"}.zip`;
      const fileName = match?.[1] || fallback;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = fileName;
      link.hidden = true;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
      toast(`首次部署包已下载：${fileName}`);
    });
  });
}





function renderUploadTargetTree() {
  const container = byId("source-target-tree");
  container.replaceChildren();
  const files = app.sourceFiles?.files || [];
  const root = buildUploadTargetTree(
    files, app.sourceFiles?.directories || []
  );
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
  item.tabIndex = 0;
  item.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); app.uploadTargetDirectory = path; renderUploadTargetTree(); }
    if (["ArrowDown", "ArrowUp"].includes(event.key)) { event.preventDefault(); const siblings = [...item.parentNode.querySelectorAll('[role="treeitem"]')]; const index = siblings.indexOf(item); siblings[Math.max(0, Math.min(siblings.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)))].focus(); }
  });
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

async function createSourceFolder() {
  if (!app.project || app.uploadTargetDirectory === null) return;
  const input = byId("source-new-folder-name");
  const name = String(input.value || "").trim();
  if (!name) return;
  if (name.includes("/") || name.includes("\\")) {
    showErrorDialog("这里只填写一层文件夹名称，不要包含斜杠。需要多层目录时可以逐层创建。");
    return;
  }
  const path = app.uploadTargetDirectory
    ? `${app.uploadTargetDirectory}/${name}`
    : name;
  await runBusy("正在新建托管文件夹", async () => {
    const result = await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/files/directory`,
      { method: "POST", body: { path } }
    );
    app.sourceFiles = result.sourceFiles;
    app.uploadTargetDirectory = result.path;
    input.value = "";
    const parts = result.path.split("/");
    for (let index = 1; index < parts.length; index += 1) {
      app.uploadTargetExpandedFolders.add(parts.slice(0, index).join("/"));
    }
    renderUploadTargetTree();
    toast(`已新建并选中 ${result.path}/`);
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
        ? (entry.selectable
            ? "文件"
            : browser.kind === "image"
              ? "非图片文件"
              : browser.kind === "music"
                ? "非 MP3 文件"
                : browser.kind === "json" ? "非 JSON 文件" : "文件")
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
    setFormValue(form, "brandName", "梦鱼更新器");
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
      showView("content");
      toast(`项目 ${created.displayName} 已创建：先添加文件，再到“检查并发布”完成首次发布`);
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
      publicBaseUrl: textValue(data, "publicBaseUrl")
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
  bindColorPair(form, "titleColor");
  bindColorPair(form, "topBarColor");
  bindOpacityPair(form, "topBarOpacity");
  bindColorPair(form, "cardColor");
  byId("reset-theme-colors").addEventListener("click", () => {
    setColor(form, "accentColor", DEFAULT_PLAYER_APPEARANCE.accentColor);
    setColor(
      form,
      "secondaryAccentColor",
      DEFAULT_PLAYER_APPEARANCE.secondaryAccentColor
    );
    setColor(form, "titleColor", DEFAULT_PLAYER_APPEARANCE.titleColor);
    setColor(form, "topBarColor", DEFAULT_PLAYER_APPEARANCE.topBarColor);
    setOpacity(form, "topBarOpacity", DEFAULT_PLAYER_APPEARANCE.topBarOpacity);
    setColor(form, "cardColor", DEFAULT_PLAYER_APPEARANCE.cardColor);
    updatePlayerPreview();
    toast("已恢复默认配色；点击“保存个性化设置”后才会正式生效");
  });
  byId("choose-cover-upload").addEventListener("click", () => coverInput.click());
  coverInput.addEventListener("change", () => {
    selectPendingCover(coverInput.files?.[0] || null);
  });
  byId("import-cover-server").addEventListener("click", async () => {
    if (!app.project) return;
    const sourcePath = String(form.elements.coverServerPath.value || "").trim();
    if (!sourcePath) {
      showErrorDialog("请先选择管理端所在电脑或服务器上的背景图片。");
      return;
    }
    await runBusy("正在从管理端导入背景图片", async () => {
      const updated = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/cover/import`,
        { method: "POST", body: { sourcePath } }
      );
      app.project.branding = updated.branding;
      clearPendingCover();
      form.elements.removeCover.checked = false;
      form.elements.coverServerPath.value = "";
      updatePlayerPreview();
      toast("背景图片已从管理端导入；创建整合包发布后玩家才能下载");
    });
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
      app.project.branding = data.branding;
      renderMusicTracks(data.branding?.musicTracks || []);
      updatePlayerPreview();
      titleInput.value = "";
      toast(`已添加音乐：${title}`);
    });
  });
  byId("import-music-server").addEventListener("click", async () => {
    if (!app.project) return;
    const sourcePath = String(form.elements.musicServerPath.value || "").trim();
    if (!sourcePath) {
      showErrorDialog("请先选择管理端所在电脑或服务器上的 MP3 文件。");
      return;
    }
    const titleInput = byId("music-upload-title");
    const title = titleInput.value.trim();
    await runBusy("正在从管理端导入音乐", async () => {
      const updated = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/music/import`,
        {
          method: "POST",
          body: { sourcePath, title, overwrite: false }
        }
      );
      app.project.branding = updated.branding;
      renderMusicTracks(updated.branding?.musicTracks || []);
      form.elements.musicServerPath.value = "";
      titleInput.value = "";
      updatePlayerPreview();
      toast(`已从管理端导入音乐：${title || sourcePath.split(/[\\/]/).pop()}`);
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
    app.expandedPlayerPages.add(id);
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
  byId("import-player-pages-server").addEventListener("click", async () => {
    const sourcePath = String(
      form.elements.pageConfigServerPath.value || ""
    ).trim();
    if (!sourcePath) {
      showErrorDialog("请先选择管理端所在电脑或服务器上的页面配置 JSON。");
      return;
    }
    let parsed = null;
    await runBusy("正在读取管理端页面配置", async () => {
      parsed = await api("/api/system/import-player-pages", {
        method: "POST", body: { sourcePath }
      });
    });
    // Wait until the busy layer is closed before opening the confirmation
    // dialog, otherwise the confirmation would appear behind that layer.
    if (parsed != null) {
      await applyImportedPlayerPages(parsed);
      form.elements.pageConfigServerPath.value = "";
    }
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
      welcomeText: textValue(data, "welcomeText"),
      brandName: textValue(data, "brandName"),
      brandEnglishName: textValue(data, "brandEnglishName"),
      subtitle: textValue(data, "subtitle"),
      serverAddress: textValue(data, "serverAddress"),
      accentColor: textValue(data, "accentColorText"),
      secondaryAccentColor: textValue(data, "secondaryAccentColorText"),
      titleColor: textValue(data, "titleColorText"),
      topBarColor: textValue(data, "topBarColorText"),
      topBarOpacity: opacityValue(form, "topBarOpacity"),
      cardColor: textValue(data, "cardColorText"),
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
      app.personalDirty = false;
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
      changelog: textValue(data, "changelog"),
      acknowledgePlayerUpgrade: form.elements.acknowledgePlayerUpgrade.checked
    };
    dialog.close();
    await runBusy("正在创建回滚发布", async () => {
      const release = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/rollback`,
        { method: "POST", body: payload }
      );
      app.history = null;
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
  byId("rollback-ack-field").hidden = Boolean(app.project?.policyPlayerPublished);
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
  renderWorkflow();
  if (changed) document.querySelector(".content").scrollTop = 0;
  if (view === "personalization") {
    requestAnimationFrame(resizePlayerPreview);
  }
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
    : DEFAULT_PLAYER_APPEARANCE[name] || "#030708";
  setFormValue(form, name, normalized);
  setFormValue(form, name + "Text", normalized);
}

function opacityPercent(value, fallback = 22) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(100, Math.max(0, parsed));
}

function setOpacity(form, name, value) {
  const parsed = Number(value);
  const percent = Number.isFinite(parsed) && parsed >= 0 && parsed <= 1
    ? Math.round(parsed * 100)
    : 22;
  setFormValue(form, name, percent);
  setFormValue(form, name + "Number", percent);
}

function opacityValue(form, name) {
  const control = form.elements.namedItem(name);
  return opacityPercent(control?.value) / 100;
}

function bindOpacityPair(form, name) {
  const slider = form.elements.namedItem(name);
  const number = form.elements.namedItem(name + "Number");
  slider.addEventListener("input", () => {
    number.value = slider.value;
  });
  number.addEventListener("input", () => {
    if (number.value !== "" && number.validity.valid) {
      slider.value = String(opacityPercent(number.value));
    }
  });
  number.addEventListener("change", () => {
    const percent = opacityPercent(number.value);
    number.value = String(percent);
    slider.value = String(percent);
  });
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

function visibleSourceFiles() {
  const query = byId("source-file-search").value
    .trim().toLocaleLowerCase("zh-CN");
  const filter = byId("content-filter").value;
  return (app.sourceFiles?.files || []).filter((file) => {
    if (query) {
      const text = `${file.path} ${file.displayName || ""} ${file.componentId || ""}`
        .toLocaleLowerCase("zh-CN");
      if (!text.includes(query)) return false;
    }
    switch (filter) {
      case "REQUIRED":
      case "INITIAL":
      case "DEFAULT_CONFIG":
        return file.preset === filter;
      case "optional":
        return Boolean(file.optionalGroup);
      case "custom":
        return file.presetSource !== "DEFAULT";
      case "unpublished":
        return !file.published;
      default:
        return true;
    }
  });
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

function buildUploadTargetTree(files, directories) {
  const root = buildFileTree(files);
  [...directories]
    .map((path) => String(path || "").replaceAll("\\", "/"))
    .filter(Boolean)
    .sort((left, right) => left.localeCompare(
      right, "zh-CN", { sensitivity: "base" }
    ))
    .forEach((path) => {
      let node = root;
      let currentPath = "";
      path.split("/").filter(Boolean).forEach((name) => {
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
      });
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
