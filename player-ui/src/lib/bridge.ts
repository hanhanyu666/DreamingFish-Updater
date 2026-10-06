import type {
  ArchiveDto,
  LocalFileEntry,
  LocalModEntry,
  OptionalGroupView,
  SidecarCommand,
  SidecarMessage,
} from "./types";

export interface PlayerBridge {
  readonly isTauri: boolean;
  sendCommand(command: SidecarCommand): void;
  openExternal(uri: string): void;
  openPath(path: string): void;
  assetUrl(path: string | null): Promise<string | null>;
  startupMusic(): Promise<string | null>;
  musicTrackUrl(fileName: string): Promise<string | null>;
  window: {
    show(): Promise<void>;
    minimize(): void;
    toggleMaximize(): void;
    close(): void;
    isMaximized(): Promise<boolean>;
    onMaximizedChange(callback: (maximized: boolean) => void): void;
  };
  onMessage(handler: (message: SidecarMessage) => void): void;
  startPreview(): void;
}

let singleton: PlayerBridge | null = null;

export function getBridge(): PlayerBridge {
  if (singleton == null) {
    singleton = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window
      ? new TauriBridge()
      : new MockBridge();
  }
  return singleton;
}

class TauriBridge implements PlayerBridge {
  readonly isTauri = true;
  private handler: ((message: SidecarMessage) => void) | null = null;
  private unlisten: (() => void) | null = null;

  async start(): Promise<void> {
    try {
      const { listen } = await import("@tauri-apps/api/event");
      const { getCurrentWindow } = await import("@tauri-apps/api/window");
      try {
        this.unlisten = await listen<string>("sidecar-line", (event) => {
          if (this.handler == null) return;
          try {
            this.handler(JSON.parse(event.payload) as SidecarMessage);
          } catch {
            // ignore malformed lines
          }
        });
      } catch {
        // Event listening is best-effort; sidecar startup continues.
      }
      try {
        await listen<string>("sidecar-crashed", (event) => {
          this.handler?.(sidecarCrashMessage(event.payload));
        });
      } catch {
        // ignore
      }
      await getCurrentWindow().setBackgroundColor([0, 0, 0, 0]);
    } catch {
      // Some WebView2 versions ignore the transparent background; CSS still handles it.
    }
    try {
      const { getCurrentWindow } = await import("@tauri-apps/api/window");
      getCurrentWindow().onResized(async () => {
        const maximized = await getCurrentWindow().isMaximized();
        window.dispatchEvent(new CustomEvent("dfs-maximized", { detail: maximized }));
      });
    } catch {
      // Maximized-state sync is cosmetic; do not block sidecar startup.
    }
    void this.spawnSidecar()
      .catch((error) => {
        this.handler?.({ type: "error", title: "无法启动更新引擎", detail: String(error), allowContinue: false });
      });
  }

  private async spawnSidecar(): Promise<unknown> {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke("spawn_sidecar");
  }

  sendCommand(command: SidecarCommand): void {
    void import("@tauri-apps/api/core")
      .then(({ invoke }) => invoke("send_command", { line: JSON.stringify(command) }))
      .catch((error) => {
        if (shouldRespawnAfterCommandFailure(command)) {
          void this.spawnSidecar().catch((spawnError) => {
            this.handler?.({
              type: "error",
              title: "无法重新启动更新引擎",
              detail: String(spawnError),
              allowContinue: false,
            });
          });
          return;
        }
        if (command.command === "close" || command.command === "quit") {
          this.handler?.({
            type: "error",
            title: "无法关闭更新器",
            detail: `关闭指令未能发送：${String(error)}`,
            allowContinue: false,
          });
          // The sidecar cannot authorize or deny launch when its IPC channel is
          // gone. Never trap the user in the shell; Rust teardown kills/waits
          // any surviving child and the Bootstrap Agent applies its fallback.
          this.window.close();
        }
      });
  }

  openExternal(uri: string): void {
    void import("@tauri-apps/api/core").then(({ invoke }) =>
      invoke("open_external", { uri }).catch(() => undefined),
    );
  }

  openPath(path: string): void {
    void import("@tauri-apps/api/core").then(({ invoke }) =>
      invoke("open_path", { path }).catch(() => undefined),
    );
  }

  async assetUrl(path: string | null): Promise<string | null> {
    if (!path) return null;
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke<string>("read_local_image", { path });
  }

  async startupMusic(): Promise<string | null> {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke<string | null>("read_startup_music");
  }

  async musicTrackUrl(fileName: string): Promise<string | null> {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke<string | null>("read_music_track", { fileName });
  }

  window = {
    async show(): Promise<void> {
      const { invoke } = await import("@tauri-apps/api/core");
      await invoke("window_show");
    },
    minimize(): void {
      void import("@tauri-apps/api/core").then(({ invoke }) => invoke("window_minimize"));
    },
    toggleMaximize(): void {
      void import("@tauri-apps/api/core").then(({ invoke }) => invoke("window_toggle_maximize"));
    },
    close(): void {
      void import("@tauri-apps/api/core").then(({ invoke }) => invoke("quit_application"));
    },
    async isMaximized(): Promise<boolean> {
      const { getCurrentWindow } = await import("@tauri-apps/api/window");
      return getCurrentWindow().isMaximized();
    },
    onMaximizedChange(callback: (maximized: boolean) => void): void {
      window.addEventListener("dfs-maximized", (event) => {
        callback(Boolean((event as CustomEvent).detail));
      });
    },
  };

  onMessage(handler: (message: SidecarMessage) => void): void {
    this.handler = handler;
    if (this.unlisten == null) void this.start();
  }

  startPreview(): void {
    // Real sidecar owns preview when the Tauri exe is started with --preview.
    this.sendCommand({ command: "quit" });
  }
}

declare global {
  interface Window {
    __DFS_ARGV__?: string[];
    __TAURI_INTERNALS__?: unknown;
  }
}

class MockBridge implements PlayerBridge {
  readonly isTauri = false;
  private handler: ((message: SidecarMessage) => void) | null = null;
  private timers: number[] = [];
  private mods = mockMods();
  private files = mockFiles();
  private groups = mockGroups();
  private archives = mockArchives();
  private working = false;
  private permitted = false;

  sendCommand(command: SidecarCommand): void {
    this.handleCommand(command);
  }

  openExternal(uri: string): void {
    window.open(uri, "_blank", "noopener,noreferrer");
  }

  openPath(): void {
    // Browser mock cannot open local directories.
  }

  async assetUrl(path: string | null): Promise<string | null> {
    return path;
  }

  async startupMusic(): Promise<string | null> {
    return null;
  }

  async musicTrackUrl(): Promise<string | null> {
    return null;
  }

  window = {
    async show(): Promise<void> {
      // no-op in the browser
    },
    minimize(): void {
      // no-op in the browser
    },
    toggleMaximize(): void {
      window.dispatchEvent(new CustomEvent("dfs-maximized", { detail: false }));
    },
    close(): void {
      window.close();
    },
    async isMaximized(): Promise<boolean> {
      return false;
    },
    onMaximizedChange(callback: (maximized: boolean) => void): void {
      window.addEventListener("dfs-maximized", (event) => {
        callback(Boolean((event as CustomEvent).detail));
      });
    },
  };

  onMessage(handler: (message: SidecarMessage) => void): void {
    this.handler = handler;
  }

  startPreview(): void {
    this.playPreview();
  }

  private emit(message: SidecarMessage): void {
    this.handler?.(message);
  }

  private later(milliseconds: number, callback: () => void): void {
    this.timers.push(window.setTimeout(callback, milliseconds));
  }

  private playPreview(): void {
    const adminPreview = new URLSearchParams(window.location.search)
      .get("adminPreview") === "1";
    this.emit({ type: "identity", name: "Player" });
    this.emit({ type: "branding", branding: {
      productName: "Minecraft 整合包",
      subtitle: "准备好后，一起进入游戏。",
      serverAddress: "",
      coverObject: null,
      accentColor: "#2ee8df",
      secondaryAccentColor: "#b06cff",
      brandName: "梦鱼更新器",
      brandEnglishName: "DreamingFish",
      newsArticles: null,
      customPage: null,
      contentPages: [],
    } });
    this.emit({ type: "background", path: null });
    this.emit({ type: "logs", lines: [
      "2026-08-13 12:08:40.210 | START | 启动 | 玩家端 0.2.0 · 项目 preview",
      "2026-08-13 12:08:41.035 | INFO  | 检查更新 | 已连接到整合包更新服务",
      "2026-08-13 12:08:42.184 | INFO  | 下载文件 | 正在下载 mods/dreamingfish-core.jar",
      "2026-08-13 12:08:43.420 | WARN  | 网络 | 当前下载速度较慢，正在继续尝试",
    ] });
    if (adminPreview) {
      this.working = false;
      this.permitted = true;
      this.emit({ type: "ready" });
      this.emit({ type: "progress", event: {
        stage: "COMPLETE", message: "本地文件已验证", currentPath: null,
        completedBytes: 1, totalBytes: 1, fraction: 1,
      } });
      return;
    }
    this.later(300, () => this.emit({ type: "ready" }));
    this.later(400, () => this.emit({ type: "progress", event: {
      stage: "CHECKING", message: "正在连接更新服务", currentPath: null, completedBytes: 0, totalBytes: 0, fraction: -1,
    } }));
    this.later(900, () => this.emit({ type: "progress", event: {
      stage: "DOWNLOADING", message: "正在下载更新", currentPath: "mods/dreamingfish-core.jar",
      completedBytes: 184 * 1024 * 1024, totalBytes: 271 * 1024 * 1024,
      fraction: (184 * 1024 * 1024) / (271 * 1024 * 1024),
    } }));
    this.later(1500, () => this.emit({ type: "progress", event: {
      stage: "DOWNLOADING", message: "正在下载更新", currentPath: "config/dreamingfish/client.toml",
      completedBytes: 271 * 1024 * 1024, totalBytes: 271 * 1024 * 1024, fraction: 1,
    } }));
    this.later(1700, () => {
      this.working = false;
      this.permitted = true;
      this.emit({ type: "mods", entries: this.mods });
      this.emit({ type: "files", entries: this.files });
      this.emit({ type: "result", result: {
        releaseId: "r000012",
        sequence: 12,
        projectId: "preview",
        createdAt: new Date(Date.now() - 86_400_000).toISOString(),
        outcome: "UPDATED",
        displayVersion: "1.20.1-r12",
        changelog: "更新模组与资源包",
        downloadedBytes: 271 * 1024 * 1024,
        installedPaths: [
          "mods/dreamingfish-core.jar",
          "mods/dreamingfish-world.jar",
          "config/dreamingfish/client.toml",
        ],
        deletedPaths: ["mods/legacy-renderer.jar"],
        archivedFiles: ["config/dreamingfish/client.toml"],
        releasedPaths: [],
        archiveDirectory: "DreamingFishUpdater/backups/archive/preview",
        unmanagedMods: ["mods/embeddium-options-api.jar", "mods/xaeros-minimap.jar"],
        forcedSyncDirectories: [],
        archived: [{
          path: "config/dreamingfish/client.toml", reason: "REPLACED_MODIFIED",
          reasonText: "这个文件由服主同步管理，你的修改已备份并恢复为服主版本",
          detail: "", size: 2048, componentId: null, version: null, restoredAt: null,
        }],
        keptModifiedPaths: ["config/voice.toml"],
        skippedSelfManagedPaths: [],
        resetPaths: [],
      } });
      this.emit({ type: "groups", groups: this.groups });
      this.emit({ type: "archives", archives: this.archives });
      this.emit({ type: "history", history: {
        schemaVersion: 1,
        projectId: "preview",
        releases: [
          { releaseId: "r000012", sequence: 12, displayVersion: "1.20.1-r12",
            createdAt: new Date(Date.now() - 86_400_000).toISOString(), changelog: "更新模组与资源包" },
          { releaseId: "r000011", sequence: 11, displayVersion: "1.20.1-r11",
            createdAt: new Date(Date.now() - 172_800_000).toISOString(), changelog: "修复部分任务无法完成的问题" },
        ],
      } });
      this.emit({ type: "countdown", seconds: 15 });
    });
  }

  private handleCommand(command: SidecarCommand): void {
    switch (command.command) {
      case "toggle-mod": {
        this.mods = this.mods.map((entry) =>
          entry.key === command.entry.key
            ? { ...entry, disabled: command.disabled, active: !command.disabled }
            : entry,
        );
        this.emit({ type: "mods", entries: this.mods });
        break;
      }
      case "restore-mods": {
        this.mods = this.mods.map((entry) => ({ ...entry, disabled: false, active: true }));
        this.emit({ type: "mods", entries: this.mods });
        break;
      }
      case "toggle-file": {
        this.files = toggleMockFile(this.files, command.entry, command.managed);
        this.emit({ type: "files", entries: this.files });
        break;
      }
      case "toggle-group": {
        this.groups = this.groups.map((group) => group.id !== command.groupId ? group : {
          ...group,
          enabled: command.enabled ?? group.defaultInstall,
          explicit: command.enabled != null,
        });
        const enabled = new Map(this.groups.map((group) => [group.id, group.enabled]));
        this.mods = this.mods.map((entry) => entry.group == null ? entry : {
          ...entry,
          disabled: !enabled.get(entry.group),
          active: Boolean(enabled.get(entry.group)),
        });
        this.emit({ type: "groups", groups: this.groups });
        this.emit({ type: "mods", entries: this.mods });
        break;
      }
      case "reset-default": {
        this.files = this.files.map((entry) =>
          entry.path === command.entry.path ? { ...entry, resetPending: true } : entry);
        this.emit({ type: "files", entries: this.files });
        break;
      }
      case "archives": {
        this.emit({ type: "archives", archives: this.archives });
        break;
      }
      case "restore-archive": {
        this.archives = this.archives.map((archive) => archive.id !== command.archiveId ? archive : {
          ...archive,
          files: archive.files.map((file) => file.path !== command.path ? file
            : { ...file, restoredAt: new Date().toISOString() }),
        });
        this.emit({ type: "archives", archives: this.archives });
        break;
      }
      case "delete-archive": {
        this.archives = this.archives.filter((archive) => archive.id !== command.archiveId);
        this.emit({ type: "archives", archives: this.archives });
        break;
      }
      case "restore-files": {
        this.files = this.files.map((entry) =>
          entry.directlyExcluded || entry.inheritedExclusion != null
            ? { ...entry, directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false }
            : entry,
        );
        this.emit({ type: "files", entries: this.files });
        break;
      }
      case "retry": {
        if (this.working) break;
        this.working = true;
        this.emit({ type: "progress", event: {
          stage: "CHECKING", message: "正在连接更新服务", currentPath: null, completedBytes: 0, totalBytes: 0, fraction: -1,
        } });
        break;
      }
      case "continue-launch": {
        this.permitted = true;
        this.emit({ type: "local-content-override" });
        this.emit({ type: "countdown", seconds: 15 });
        break;
      }
      case "keep-open": {
        this.emit({ type: "launch-kept-open" });
        break;
      }
      case "confirm": {
        // The mock resolves confirmations through the store directly.
        break;
      }
      case "close": {
        if (!this.permitted) {
          this.emit({ type: "confirm-request", request: {
            id: 9001,
            tone: "DANGER",
            title: "取消更新",
            heading: "确定要关闭更新器吗？",
            message: "关闭更新器会取消本次更新，并停止 Minecraft 启动。",
            actionText: "取消更新",
            cancelText: "继续更新",
          } });
        }
        break;
      }
      case "quit": {
        window.close();
        break;
      }
    }
  }
}

function mockMods(): LocalModEntry[] {
  return [
    { key: "component:renderer", displayName: "旧版渲染优化", path: "mods/legacy-renderer.jar",
      componentId: "renderer", managed: true, disabled: true, active: false, forced: false,
      version: "0.9.2", preset: "SYNC" },
    { key: "component:dreamingfish", displayName: "DreamingFish Core", path: "mods/dreamingfish-core.jar",
      componentId: "dreamingfish", managed: true, disabled: false, active: true, forced: true,
      version: "2.4.0", preset: "REQUIRED", lockReason: "服主设为强制同步，不能在本机停用" },
    { key: "component:iris", displayName: "Iris Shaders", path: "mods/iris.jar",
      componentId: "iris", managed: true, disabled: false, active: true, forced: true,
      version: "1.7.2", preset: "SYNC", group: "visuals", groupTitle: "光影与美化",
      lockReason: "由可选内容“光影与美化”统一开关" },
    { key: "component:embeddium-options-api", displayName: "Embeddium Options API",
      path: "mods/embeddium-options-api.jar", componentId: "embeddium-options-api",
      managed: false, disabled: false, active: true, forced: false, version: "1.0.3" },
    { key: "component:xaerominimap", displayName: "Xaero's Minimap", path: "mods/xaeros-minimap.jar",
      componentId: "xaerominimap", managed: false, disabled: false, active: true, forced: false,
      version: "24.2.0", withdrawnReason: "这个版本会导致进入主城时崩溃，请换用 24.3.0" },
  ];
}

function mockFiles(): LocalFileEntry[] {
  return [
    { path: "config", displayName: "config", directory: true, directlyExcluded: false,
      inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
      policy: null, managedFileCount: 2 },
    { path: "config/dreamingfish", displayName: "dreamingfish", directory: true, directlyExcluded: true,
      inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
      policy: null, managedFileCount: 1 },
    { path: "config/dreamingfish/client.toml", displayName: "client.toml", directory: false,
      directlyExcluded: false, inheritedExclusion: "config/dreamingfish", partiallyExcluded: false,
      present: true, forced: false, policy: "ENFORCED", managedFileCount: 0, preset: "SYNC" },
    { path: "config/voice.toml", displayName: "voice.toml", directory: false, directlyExcluded: false,
      inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
      policy: "ENFORCED", managedFileCount: 0, preset: "DEFAULT_CONFIG", modified: true,
      resetPending: false },
    { path: "mods", displayName: "mods", directory: true, directlyExcluded: false,
      inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
      policy: null, managedFileCount: 2 },
    { path: "mods/dreamingfish-core.jar", displayName: "DreamingFish Core", directory: false,
      directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false, present: true,
      forced: true, policy: "ENFORCED", managedFileCount: 0, componentId: "dreamingfish",
      preset: "REQUIRED", lockReason: "服主设为强制同步，不能取消管理" },
    { path: "mods/iris.jar", displayName: "Iris Shaders", directory: false,
      directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false, present: true,
      forced: true, policy: "ENFORCED", managedFileCount: 0, componentId: "iris",
      preset: "SYNC", group: "visuals", lockReason: "由可选内容“光影与美化”统一开关" },
    { path: "options.txt", displayName: "options.txt", directory: false,
      directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false, present: true,
      forced: false, policy: "ENFORCED", managedFileCount: 0, preset: "INITIAL" },
  ];
}

function mockGroups(): OptionalGroupView[] {
  return [
    { id: "visuals", title: "光影与美化", description: "光影、粒子和动画效果，显卡较弱或内存小于 8G 建议关闭",
      defaultInstall: true, enabled: true, explicit: false,
      members: ["Iris Shaders", "Particle Rain", "Not Enough Animations"] },
    { id: "minimap", title: "小地图", description: "右上角小地图与路径点", defaultInstall: false,
      enabled: false, explicit: false, members: ["Xaero's Minimap"] },
  ];
}

function mockArchives(): ArchiveDto[] {
  return [{
    id: "20260812-120840_r000012_preview",
    legacy: false,
    createdAt: new Date(Date.now() - 3_600_000).toISOString(),
    releaseId: "r000012",
    displayVersion: "1.20.1-r12",
    totalBytes: 2048 + 1_843_200,
    files: [{
      path: "config/dreamingfish/client.toml", reason: "REPLACED_MODIFIED",
      reasonText: "这个文件由服主同步管理，你的修改已备份并恢复为服主版本",
      detail: "", size: 2048, componentId: null, version: null, restoredAt: null,
    }, {
      path: "mods/xaeros-minimap-24.1.0.jar", reason: "WITHDRAWN",
      reasonText: "服主撤回了这个版本", detail: "这个版本会导致进入主城时崩溃",
      size: 1_843_200, componentId: "xaerominimap", version: "24.1.0", restoredAt: null,
    }],
  }];
}

function toggleMockFile(
  files: readonly LocalFileEntry[],
  target: LocalFileEntry,
  managed: boolean,
): LocalFileEntry[] {
  const prefix = target.directory ? target.path + "/" : null;
  return files.map((entry) => {
    if (entry.path !== target.path && prefix != null && entry.path.startsWith(prefix)) {
      return {
        ...entry,
        inheritedExclusion: managed ? null : entry.inheritedExclusion ?? target.path,
      };
    }
    if (entry.path !== target.path) return entry;
    if (entry.directory) {
      return { ...entry, directlyExcluded: !managed };
    }
    return { ...entry, directlyExcluded: !managed, inheritedExclusion: null };
  });
}

export function isTauriRuntime(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

export function shouldRespawnAfterCommandFailure(command: SidecarCommand): boolean {
  return command.command === "retry";
}

export function sidecarCrashMessage(detail: string): SidecarMessage {
  return {
    type: "error",
    title: "更新引擎意外退出",
    detail: detail.trim().length > 0 ? detail : "没有收到错误详情。可以点击“重试”重新启动更新引擎。",
    allowContinue: false,
  };
}
