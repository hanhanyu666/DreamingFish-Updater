"use strict";

const app = {
  token: "",
  state: null,
  project: null,
  selectedProjectId: "",
  view: "dashboard",
  busy: false,
  forcedFileSelection: new Set(),
  sourceFiles: null,
  pendingUploads: []
};

const titles = {
  dashboard: "运行概览",
  project: "项目设置",
  publish: "扫描与发布",
  player: "玩家端程序",
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
  bindEvents();
  try {
    const session = await api("/api/session");
    app.token = session.token;
    byId("admin-version").textContent =
      `DreamingFish Admin ${session.version}`;
    setConnection(true);
    await refreshState();
  } catch (error) {
    setConnection(false);
    toast(error.message, true);
  }
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
  const requestedPlatform = platform
    || app.project?.platform
    || "windows-x64";
  app.selectedProjectId = projectId;
  app.project = await api(
    `/api/projects/${encodeURIComponent(projectId)}`
      + `?platform=${encodeURIComponent(requestedPlatform)}`
  );
  app.forcedFileSelection = new Set(app.project.forcedSyncFiles || []);
  app.sourceFiles = null;
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
  renderSourceFiles();
  renderPreview();
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
  renderSourceFiles();
  renderForcedFiles();
}

function renderSourceFiles() {
  const result = app.sourceFiles;
  if (!result) {
    byId("source-file-count").textContent = "打开页面后读取";
    byId("source-managed-count").textContent = "--";
    byId("source-total-size").textContent = "--";
    byId("source-forced-count").textContent = "--";
    setRows("source-file-table", "source-file-empty", []);
    return;
  }
  const query = byId("source-file-search").value
    .trim().toLocaleLowerCase("zh-CN");
  const files = (result.files || []).filter((file) =>
    !query || file.path.toLocaleLowerCase("zh-CN").includes(query)
  );
  const forcedCount = (result.files || []).filter(
    (file) => file.forcedByDirectory || file.forcedByFile
  ).length;
  byId("source-file-count").textContent = query
    ? `显示 ${files.length} / ${result.count} 个文件`
    : `${result.count} 个托管文件`;
  byId("source-managed-count").textContent = String(result.count);
  byId("source-total-size").textContent = formatBytes(result.totalBytes);
  byId("source-forced-count").textContent = String(forcedCount);
  const rows = files.map((file) => {
    const status = document.createElement("span");
    status.className = "source-status";
    if (file.forcedByDirectory || file.forcedByFile) {
      status.classList.add("forced");
      status.textContent = file.forcedByDirectory ? "目录强制" : "单文件强制";
    } else {
      status.textContent = file.policy === "DEFAULT" ? "默认文件" : "普通托管";
    }
    const remove = actionButton("移除", () => removeSourceFile(file));
    return row([
      status,
      pathCell(file.path),
      formatBytes(file.size),
      formatDate(file.lastModifiedMillis),
      remove
    ]);
  });
  setRows("source-file-table", "source-file-empty", rows);
}

function renderProjectForm() {
  if (!app.project) return;
  const form = byId("project-form");
  byId("project-identity").textContent =
    `${app.project.displayName} · ${app.project.id}`;
  setFormValue(form, "displayName", app.project.displayName);
  setFormValue(form, "sourceDirectory", app.project.sourceDirectory);
  setFormValue(form, "publicBaseUrl", app.project.publicBaseUrl);
  setFormValue(form, "productName", app.project.branding.productName);
  setFormValue(form, "subtitle", app.project.branding.subtitle);
  setFormValue(form, "serverAddress", app.project.branding.serverAddress);
  setFormValue(
    form,
    "forcedSyncDirectories",
    (app.project.forcedSyncDirectories || []).join(", ")
  );
  setColor(form, "accentColor", app.project.branding.accentColor);
  setColor(
    form,
    "secondaryAccentColor",
    app.project.branding.secondaryAccentColor
  );
  setFormValue(form, "coverPath", "");
  form.elements.removeCover.checked = false;
}

function renderPreview() {
  const preview = app.project?.preview;
  const summary = byId("preview-summary").querySelectorAll("strong");
  if (!preview) {
    summary.forEach((element) => {
      element.textContent = "--";
    });
    byId("preview-time").textContent = "尚未扫描";
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
        : "");
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

function renderForcedFiles() {
  const previewFiles = app.project?.preview?.files
    || app.sourceFiles?.files
    || [];
  const known = new Map(previewFiles.map((file) => [foldPath(file.path), file]));
  (app.project?.forcedSyncFiles || []).forEach((path) => {
    if (!known.has(foldPath(path))) {
      known.set(foldPath(path), {
        path,
        size: null,
        policy: "MISSING"
      });
    }
  });
  const query = byId("forced-file-search").value.trim().toLocaleLowerCase("zh-CN");
  const files = [...known.values()]
    .filter((file) => !query
      || file.path.toLocaleLowerCase("zh-CN").includes(query))
    .sort((left, right) => left.path.localeCompare(
      right.path, "zh-CN", { sensitivity: "base" }
    ));
  const rows = files.map((file) => {
    const directoryForced = insideForcedDirectory(file.path);
    const missing = file.policy === "MISSING";
    const control = document.createElement("td");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.className = "forced-file-check";
    checkbox.dataset.path = file.path;
    checkbox.checked = directoryForced
      || app.forcedFileSelection.has(file.path);
    checkbox.disabled = directoryForced || missing;
    checkbox.title = directoryForced
      ? "该文件已由强制同步目录覆盖"
      : missing
        ? "源目录中已找不到该文件；取消旧选择后保存"
        : "玩家不能豁免选中的文件";
    control.append(checkbox);
    const fileForced = [...app.forcedFileSelection]
      .some((path) => foldPath(path) === foldPath(file.path));
    const policy = directoryForced
      ? "目录强制"
      : fileForced ? "单文件强制"
        : missing ? "源文件缺失"
          : file.policy === "DEFAULT" ? "默认" : "普通托管";
    const policyCell = textCell(policy);
    checkbox.addEventListener("change", () => {
      if (checkbox.checked) app.forcedFileSelection.add(file.path);
      else app.forcedFileSelection.delete(file.path);
      policyCell.textContent = checkbox.checked
        ? "单文件强制"
        : file.policy === "DEFAULT" ? "默认" : "普通托管";
      policyCell.title = policyCell.textContent;
      updateForcedFileCount();
    });
    return row([
      control,
      pathCell(file.path),
      policyCell,
      file.size === null ? "--" : formatBytes(file.size)
    ]);
  });
  setRows("forced-file-table", "forced-file-empty", rows);
  updateForcedFileCount();
}

function updateForcedFileCount() {
  const count = app.forcedFileSelection.size;
  byId("forced-file-count").textContent =
    count === 0
      ? "未单独强制任何文件"
      : `已选择 ${count} 个单独强制同步文件`;
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
}

function bindEvents() {
  document.querySelectorAll(".nav-button").forEach((button) => {
    button.addEventListener("click", async () => {
      const needsSourceFiles = button.dataset.view === "publish";
      if (needsSourceFiles && app.project && !app.sourceFiles) {
        await runBusy("正在读取标准源目录", async () => {
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
  bindProjectForm();
  bindPathPickers();
  bindSourceFiles();
  bindPublish();
  bindPrograms();
  bindDeployment();
  bindInstance();
  bindSettings();
  bindRollback();
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
  byId("open-source-add").addEventListener("click", () => {
    form.reset();
    app.pendingUploads = [];
    updateUploadSelection();
    resetUploadProgress();
    dialog.showModal();
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (event.submitter?.value === "cancel") dialog.close();
  });
  byId("choose-source-upload").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    app.pendingUploads = [...fileInput.files];
    updateUploadSelection();
  });
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
  const form = byId("source-add-form");
  const data = new FormData(form);
  let targetDirectory;
  try {
    targetDirectory = normalizeSourceDirectory(
      textValue(data, "targetDirectory")
    );
  } catch (error) {
    toast(error.message, true);
    return;
  }
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
    toast(`${files.length} 个文件已加入标准源目录`);
  } catch (error) {
    toast(error.message, true);
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
    uploadButton.disabled = false;
    importButton.disabled = false;
  }
}

function uploadSourceFile(file, targetPath, overwrite, refreshPreview, onProgress) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
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
      if (request.status >= 200 && request.status < 300) resolve(result);
      else reject(new Error(result?.message || `上传失败：HTTP ${request.status}`));
    });
    request.addEventListener("error", () => reject(new Error("上传连接中断")));
    request.addEventListener("abort", () => reject(new Error("上传已取消")));
    request.send(file);
  });
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
  let targetDirectory;
  try {
    targetDirectory = normalizeSourceDirectory(
      textValue(data, "targetDirectory")
    );
  } catch (error) {
    toast(error.message, true);
    return;
  }
  const payload = {
    sourcePath,
    targetDirectory,
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
    toast("服务器文件已加入标准源目录");
  });
}

async function removeSourceFile(file) {
  if (!app.project) return;
  const action = await chooseSourceRemoval(file);
  if (!action) return;
  await runBusy("正在归档并移除源文件", async () => {
    const result = await api(
      `/api/projects/${encodeURIComponent(app.project.id)}/files/remove`,
      { method: "POST", body: { path: file.path, action } }
    );
    await loadProject(app.project.id);
    await loadSourceFiles();
    showView("publish");
    const playerResult = action === "RELEASE"
      ? "玩家本地将保留该文件"
      : "玩家更新时将移除该文件";
    toast(`${playerResult}；源文件已归档到 ${result.archivedPreviousFile}`);
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
    `${file.path}\n\n源文件会先归档，随后从标准源目录移出。`
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

function normalizeSourceDirectory(value) {
  const normalized = String(value || "").trim()
    .replaceAll("\\", "/").replace(/^\/+|\/+$/g, "");
  if (normalized.split("/").some((part) => part === "." || part === "..")) {
    throw new Error("目标目录不能包含 . 或 ..");
  }
  return normalized;
}

function joinSourcePath(directory, fileName) {
  const name = String(fileName || "").replaceAll("\\", "/");
  if (!name || name.includes("/")) throw new Error("上传文件名无效");
  return directory ? `${directory}/${name}` : name;
}

function bindPathPickers() {
  document.querySelectorAll(".path-picker-button").forEach((button) => {
    button.addEventListener("click", async () => {
      const form = button.closest("form");
      const input = form?.elements.namedItem(button.dataset.pathName);
      if (!(input instanceof HTMLInputElement)) return;
      await runBusy("请在管理端所在电脑完成路径选择", async () => {
        const result = await api("/api/system/select-path", {
          method: "POST",
          body: {
            kind: button.dataset.pathKind,
            title: button.dataset.pathTitle,
            initialPath: input.value
          }
        });
        if (result.selected) {
          input.value = result.path;
          input.dispatchEvent(new Event("change", { bubbles: true }));
        }
      });
    });
  });
}

function bindProjectCreate() {
  const dialog = byId("create-project-dialog");
  const form = byId("create-project-form");
  byId("open-create-project").addEventListener("click", () => {
    form.reset();
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
      serverAddress: textValue(data, "serverAddress")
    };
    await runBusy("正在创建项目", async () => {
      const created = await api("/api/projects", {
        method: "POST",
        body: payload
      });
      dialog.close();
      await refreshState(created.id);
      toast(`项目 ${created.displayName} 已创建`);
    });
  });
}

function bindProjectForm() {
  const form = byId("project-form");
  bindColorPair(form, "accentColor");
  bindColorPair(form, "secondaryAccentColor");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!app.project || !form.reportValidity()) return;
    const data = new FormData(form);
    const payload = {
      displayName: textValue(data, "displayName"),
      sourceDirectory: textValue(data, "sourceDirectory"),
      publicBaseUrl: textValue(data, "publicBaseUrl"),
      productName: textValue(data, "productName"),
      subtitle: textValue(data, "subtitle"),
      serverAddress: textValue(data, "serverAddress"),
      forcedSyncDirectories: directoryList(
        textValue(data, "forcedSyncDirectories")
      ),
      forcedSyncFiles: [...app.forcedFileSelection],
      accentColor: textValue(data, "accentColorText"),
      secondaryAccentColor: textValue(data, "secondaryAccentColorText"),
      coverPath: textValue(data, "coverPath"),
      removeCover: form.elements.removeCover.checked
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

function bindPublish() {
  byId("release-all-button").addEventListener("click", () => {
    setAllRemovalActions("RELEASE");
  });
  byId("delete-all-button").addEventListener("click", () => {
    setAllRemovalActions("DELETE");
  });
  byId("forced-file-search").addEventListener("input", renderForcedFiles);
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
      toast("扫描完成");
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
      webPort: Number(textValue(data, "webPort"))
    };
    await runBusy("正在保存服务设置", async () => {
      await api("/api/settings", { method: "PUT", body: payload });
      await refreshState();
      toast("服务设置已保存");
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
  app.view = view;
  document.querySelectorAll(".view").forEach((section) => {
    section.classList.toggle("active", section.id === `view-${view}`);
  });
  document.querySelectorAll(".nav-button").forEach((button) => {
    button.classList.toggle("active", button.dataset.view === view);
  });
  byId("page-title").textContent = titles[view];
  byId("view-" + view).scrollTop = 0;
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
  const element = document.createElement("div");
  element.className = error ? "toast error" : "toast";
  element.textContent = message;
  byId("toast-stack").append(element);
  window.setTimeout(() => element.remove(), 4200);
}

window.addEventListener("error", (event) => {
  toast(event.message || "页面发生错误", true);
});

initialize();
