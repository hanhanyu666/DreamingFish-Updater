"use strict";

const app = {
  token: "",
  state: null,
  project: null,
  selectedProjectId: "",
  view: "dashboard",
  busy: false,
  forcedFileSelection: new Set()
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
  renderPreview();
  renderForcedFiles();
  renderReleases();
  renderPrograms();
  renderInstanceReleases();
}

function renderProjectForm() {
  if (!app.project) return;
  const form = byId("project-form");
  byId("project-identity").textContent =
    `${app.project.displayName} · ${app.project.id}`;
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
  const previewFiles = app.project?.preview?.files || [];
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
    checkbox.addEventListener("change", () => {
      if (checkbox.checked) app.forcedFileSelection.add(file.path);
      else app.forcedFileSelection.delete(file.path);
      updateForcedFileCount();
    });
    control.append(checkbox);
    const policy = directoryForced
      ? "目录强制"
      : missing ? "源文件缺失" : file.policy === "DEFAULT" ? "默认" : "普通托管";
    return row([
      control,
      pathCell(file.path),
      policy,
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
  const select = byId("instance-form").elements.releaseId;
  const releases = app.project?.releases || [];
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
    button.addEventListener("click", () => showView(button.dataset.view));
  });
  byId("project-select").addEventListener("change", async (event) => {
    await runBusy("正在切换项目", async () => {
      await loadProject(event.target.value);
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
  bindPublish();
  bindPrograms();
  bindInstance();
  bindSettings();
  bindRollback();
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
      form.elements.minimumPlayerVersion.value = "0.1.13";
      await refreshState(app.project.id);
      toast(`版本 ${release.displayVersion} 已发布`);
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
      version: textValue(data, "version"),
      sourceDirectory: textValue(data, "sourceDirectory"),
      launchPath: textValue(data, "launchPath"),
      minimumBootstrapVersion:
        textValue(data, "minimumBootstrapVersion")
    };
    const accepted = await ask(
      "发布玩家端程序",
      `${payload.platform} · ${payload.version}`,
      "确认发布"
    );
    if (!accepted) return;
    await runBusy("正在校验并签名玩家端程序", async () => {
      const program = await api(
        `/api/projects/${encodeURIComponent(app.project.id)}/programs`,
        { method: "POST", body: payload }
      );
      form.elements.version.value = "";
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
      toast(`回滚版本 ${release.displayVersion} 已发布`);
    });
  });
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
