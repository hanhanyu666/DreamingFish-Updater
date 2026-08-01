export type FilePolicy = "DEFAULT" | "ENFORCED" | null;

export interface LocalFileEntry {
  path: string;
  displayName: string;
  directory: boolean;
  directlyExcluded: boolean;
  inheritedExclusion: string | null;
  partiallyExcluded: boolean;
  present: boolean;
  forced: boolean;
  policy: FilePolicy | null;
  managedFileCount: number;
}

export interface LocalModEntry {
  key: string;
  displayName: string;
  path: string;
  componentId: string | null;
  managed: boolean;
  disabled: boolean;
  active: boolean;
  forced: boolean;
}

export type UpdateStage =
  | "RECOVERING"
  | "CHECKING"
  | "SCANNING"
  | "DOWNLOADING"
  | "PREPARING"
  | "INSTALLING"
  | "VERIFYING"
  | "COMPLETE"
  | "OFFLINE";

export type UpdateOutcome = "UP_TO_DATE" | "UPDATED" | "OFFLINE_ALLOWED" | "GAME_RUNNING";

export interface ProgressEvent {
  stage: UpdateStage;
  message: string;
  currentPath: string | null;
  completedBytes: number;
  totalBytes: number;
  fraction: number;
}

export interface Branding {
  productName: string;
  subtitle: string;
  serverAddress: string;
  coverObject: string | null;
  accentColor: string | null;
  secondaryAccentColor: string | null;
}

export interface ReleaseHistoryEntry {
  releaseId: string;
  sequence: number;
  displayVersion: string;
  createdAt: string;
  changelog: string;
}

export interface ReleaseHistory {
  schemaVersion: number;
  projectId: string;
  releases: ReleaseHistoryEntry[];
}

export interface UpdateResultDto {
  releaseId: string;
  sequence: number;
  projectId: string;
  createdAt: string;
  outcome: UpdateOutcome;
  displayVersion: string;
  changelog: string;
  downloadedBytes: number;
  installedPaths: string[];
  deletedPaths: string[];
  archivedFiles: string[];
  releasedPaths: string[];
  archiveDirectory: string | null;
  unmanagedMods: string[];
  forcedSyncDirectories: string[];
}

export type DialogTone = "INFO" | "WARNING" | "DANGER";

export interface ConfirmRequest {
  id: number;
  tone: DialogTone;
  title: string;
  heading: string;
  message: string;
  actionText: string;
  cancelText: string;
}

export type SidecarMessage =
  | { type: "branding"; branding: Branding }
  | { type: "background"; path: string | null }
  | { type: "identity"; name: string }
  | { type: "logs"; lines: string[] }
  | { type: "log"; line: string }
  | { type: "history"; history: ReleaseHistory | null }
  | { type: "progress"; event: ProgressEvent }
  | { type: "result"; result: UpdateResultDto }
  | { type: "unverified-offline" }
  | { type: "local-content-override" }
  | { type: "error"; title: string; detail: string; allowContinue: boolean }
  | { type: "mods"; entries: LocalModEntry[] }
  | { type: "files"; entries: LocalFileEntry[] }
  | { type: "countdown"; seconds: number }
  | { type: "launch-kept-open" }
  | { type: "restart-required"; item: string }
  | { type: "confirm-request"; request: ConfirmRequest }
  | { type: "open-request"; kind: "directory" | "archive" | "external"; value: string | null }
  | { type: "ready" }
  | { type: "exit" };

export type SidecarCommand =
  | { command: "retry" }
  | { command: "continue-launch" }
  | { command: "toggle-mod"; entry: LocalModEntry; disabled: boolean }
  | { command: "restore-mods" }
  | { command: "toggle-file"; entry: LocalFileEntry; managed: boolean }
  | { command: "restore-files" }
  | { command: "open-directory" }
  | { command: "open-archive" }
  | { command: "keep-open" }
  | { command: "confirm"; id: number; accepted: boolean }
  | { command: "close" }
  | { command: "quit" };

export const STAGE_NAMES: Record<UpdateStage, string> = {
  RECOVERING: "正在恢复更新",
  CHECKING: "正在检查更新",
  SCANNING: "正在校验文件",
  DOWNLOADING: "正在下载更新",
  PREPARING: "正在准备安装",
  INSTALLING: "正在安装更新",
  VERIFYING: "正在完成校验",
  COMPLETE: "准备完成",
  OFFLINE: "离线启动",
};

export const DEFAULT_BRANDING: Branding = {
  productName: "梦屿",
  subtitle: "灾变之后，仍有人在这里守望。",
  serverAddress: "",
  coverObject: null,
  accentColor: "#2ee8df",
  secondaryAccentColor: "#b06cff",
};
