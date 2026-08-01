import type {
  LocalFileEntry,
  LocalModEntry,
  SidecarCommand,
  SidecarMessage,
} from "./types";

export interface PlayerBridge {
  readonly isTauri: boolean;
  sendCommand(command: SidecarCommand): void;
  openExternal(uri: string): void;
  openPath(path: string): void;
  assetUrl(path: string | null): string | null;
  window: {
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
        await listen<string>("sidecar-error", (event) => {
          this.handler?.({ type: "error", title: "更新器进程异常", detail: event.payload, allowContinue: false });
        });
      } catch {
        // ignore
      }
      try {
        await listen("sidecar-exited", () => {
          getCurrentWindow().close();
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
    void import("@tauri-apps/api/core")
      .then(({ invoke }) => invoke("spawn_sidecar"))
      .catch((error) => {
        this.handler?.({ type: "error", title: "无法启动更新引擎", detail: String(error), allowContinue: false });
      });
  }

  sendCommand(command: SidecarCommand): void {
    void import("@tauri-apps/api/core").then(({ invoke }) =>
      invoke("send_command", { line: JSON.stringify(command) }).catch(() => undefined),
    );
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

  assetUrl(path: string | null): string | null {
    if (!path) return null;
    return convertFileSrc(path);
  }

  window = {
    minimize(): void {
      void import("@tauri-apps/api/core").then(({ invoke }) => invoke("window_minimize"));
    },
    toggleMaximize(): void {
      void import("@tauri-apps/api/core").then(({ invoke }) => invoke("window_toggle_maximize"));
    },
    close(): void {
      void import("@tauri-apps/api/core").then(({ invoke }) => invoke("window_close"));
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

// Tauri's convertFileSrc is available from @tauri-apps/api/core.
let convertFileSrc: (path: string) => string;
void import("@tauri-apps/api/core")
  .then((module) => {
    convertFileSrc = module.convertFileSrc;
  })
  .catch(() => undefined);

function lazyConvertFileSrc(path: string): string {
  if (convertFileSrc != null) return convertFileSrc(path);
  return "asset://localhost/" + encodeURIComponent(path);
}

class MockBridge implements PlayerBridge {
  readonly isTauri = false;
  private handler: ((message: SidecarMessage) => void) | null = null;
  private timers: number[] = [];
  private mods = mockMods();
  private files = mockFiles();
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

  assetUrl(path: string | null): string | null {
    return path;
  }

  window = {
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
    this.emit({ type: "identity", name: "Hanyu" });
    this.emit({ type: "branding", branding: {
      productName: "梦屿",
      subtitle: "灾变之后，仍有人在这里守望。",
      serverAddress: "",
      coverObject: null,
      accentColor: "#2ee8df",
      secondaryAccentColor: "#b06cff",
    } });
    this.emit({ type: "background", path: null });
    this.emit({ type: "logs", lines: [
      "12:08:41  INFO  已连接到守望梦屿更新服务",
      "12:08:42  INFO  正在下载 mods/dreamingfish-core.jar",
    ] });
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
        projectId: "dreamhaven",
        createdAt: new Date(Date.now() - 86_400_000).toISOString(),
        outcome: "UPDATED",
        displayVersion: "1.20.1-r12",
        changelog: "新增梦屿群系探索内容",
        downloadedBytes: 271 * 1024 * 1024,
        installedPaths: [
          "mods/dreamingfish-core.jar",
          "mods/dreamingfish-world.jar",
          "config/dreamingfish/client.toml",
        ],
        deletedPaths: ["mods/legacy-renderer.jar"],
        archivedFiles: [],
        releasedPaths: [],
        archiveDirectory: null,
        unmanagedMods: ["mods/embeddium-options-api.jar", "mods/xaeros-minimap.jar"],
        forcedSyncDirectories: [],
      } });
      this.emit({ type: "history", history: {
        schemaVersion: 1,
        projectId: "dreamhaven",
        releases: [
          { releaseId: "r000012", sequence: 12, displayVersion: "1.20.1-r12",
            createdAt: new Date(Date.now() - 86_400_000).toISOString(), changelog: "新增梦屿群系探索内容" },
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
      componentId: "renderer", managed: true, disabled: true, active: false, forced: false },
    { key: "component:dreamingfish", displayName: "DreamingFish Core", path: "mods/dreamingfish-core.jar",
      componentId: "dreamingfish", managed: true, disabled: false, active: true, forced: false },
    { key: "component:embeddium-options-api", displayName: "Embeddium Options API",
      path: "mods/embeddium-options-api.jar", componentId: "embeddium-options-api",
      managed: false, disabled: false, active: true, forced: false },
    { key: "component:xaerominimap", displayName: "Xaero's Minimap", path: "mods/xaeros-minimap.jar",
      componentId: "xaerominimap", managed: false, disabled: false, active: true, forced: false },
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
      present: true, forced: false, policy: "ENFORCED", managedFileCount: 0 },
    { path: "config/voice.toml", displayName: "voice.toml", directory: false, directlyExcluded: false,
      inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
      policy: "ENFORCED", managedFileCount: 0 },
    { path: "mods", displayName: "mods", directory: true, directlyExcluded: false,
      inheritedExclusion: null, partiallyExcluded: false, present: true, forced: false,
      policy: null, managedFileCount: 2 },
    { path: "mods/dreamingfish-core.jar", displayName: "DreamingFish Core", directory: false,
      directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false, present: true,
      forced: false, policy: "ENFORCED", managedFileCount: 0 },
    { path: "mods/dreamingfish-world.jar", displayName: "DreamingFish World", directory: false,
      directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false, present: true,
      forced: false, policy: "ENFORCED", managedFileCount: 0 },
    { path: "defaultconfigs", displayName: "defaultconfigs", directory: true, directlyExcluded: false,
      inheritedExclusion: null, partiallyExcluded: false, present: true, forced: true,
      policy: null, managedFileCount: 1 },
    { path: "defaultconfigs/server.toml", displayName: "server.toml", directory: false,
      directlyExcluded: false, inheritedExclusion: null, partiallyExcluded: false, present: true,
      forced: true, policy: "ENFORCED", managedFileCount: 0 },
  ];
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
