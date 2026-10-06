export type FilePolicy = "DEFAULT" | "ENFORCED" | null;

/** How the owner maintains a published file; older releases map to LEGACY_MISSING_ONLY or SYNC. */
export type MaintenancePreset =
  | "REQUIRED"
  | "SYNC"
  | "INITIAL"
  | "DEFAULT_CONFIG"
  | "LEGACY_MISSING_ONLY";

export interface LocalFileEntry {
  path: string;
  displayName: string;
  directory: boolean;
  directlyExcluded: boolean;
  inheritedExclusion: string | null;
  partiallyExcluded: boolean;
  present: boolean;
  /** Whether the player cannot change it; {@link lockReason} says why. */
  forced: boolean;
  policy: FilePolicy | null;
  managedFileCount: number;
  componentId?: string | null;
  preset?: MaintenancePreset | null;
  group?: string | null;
  lockReason?: string | null;
  /** For default configurations: whether the local copy differs from the shipped one. */
  modified?: boolean | null;
  /** The player asked the next update to restore the default. */
  resetPending?: boolean;
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
  version?: string | null;
  preset?: MaintenancePreset | null;
  group?: string | null;
  groupTitle?: string | null;
  lockReason?: string | null;
  /** The owner's reason when this exact version was withdrawn. */
  withdrawnReason?: string | null;
}

/** An optional content group with the player's effective switch. */
export interface OptionalGroupView {
  id: string;
  title: string;
  description: string;
  defaultInstall: boolean;
  enabled: boolean;
  /** Whether the player chose explicitly instead of following the owner's default. */
  explicit: boolean;
  members: string[];
}

export type ArchiveReason =
  | "CLEANUP"
  | "REMOVED_MODIFIED"
  | "REMOVED_SELF_MANAGED"
  | "TAKEOVER"
  | "REPLACED_MODIFIED"
  | "DUPLICATE"
  | "WITHDRAWN"
  | "CORRECTED"
  | "RESET_DEFAULT"
  | "LEGACY";

export interface ArchivedFileDto {
  path: string;
  reason: ArchiveReason | string;
  reasonText: string;
  detail: string;
  size: number;
  componentId: string | null;
  version: string | null;
  restoredAt: string | null;
}

export interface ArchiveDto {
  id: string;
  legacy: boolean;
  createdAt: string | null;
  releaseId: string | null;
  displayVersion: string | null;
  totalBytes: number;
  files: ArchivedFileDto[];
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
  titleColor?: string | null;
  welcomeText?: string | null;
  topBarColor?: string | null;
  topBarOpacity?: number | null;
  cardColor?: string | null;
  brandName: string;
  brandEnglishName: string;
  newsArticles?: PlayerNewsArticle[] | null;
  customPage?: PlayerCustomPage | null;
  contentPages?: PlayerContentPage[] | null;
  musicTracks?: PlayerMusicTrack[] | null;
}

export interface PlayerMusicTrack {
  id: string;
  title: string;
  fileName: string;
}

export interface PlayerNewsArticle {
  id: string;
  title: string;
  summary: string;
  publishedOn: string;
  coverUrl: string;
  markdown: string;
}

export interface PlayerCustomPage {
  enabled: boolean;
  navigationLabel: string;
  eyebrow: string;
  title: string;
  lead: string;
  markdown: string;
}

export interface PlayerContentPage {
  id: string;
  navigationLabel: string;
  announcementPage: boolean;
  eyebrow: string;
  title: string;
  lead: string;
  markdown: string;
  articles: PlayerNewsArticle[] | null;
}

export interface AdminPreviewPayload {
  type: "dfs-admin-preview";
  branding: Branding;
  backgroundUrl: string | null;
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
  /** Directories where files the player added are moved into the backup. */
  forcedSyncDirectories: string[];
  archived?: ArchivedFileDto[];
  keptModifiedPaths?: string[];
  skippedSelfManagedPaths?: string[];
  resetPaths?: string[];
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
  | { type: "groups"; groups: OptionalGroupView[] }
  | { type: "archives"; archives: ArchiveDto[] }
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
  | { command: "toggle-group"; groupId: string; enabled: boolean | null }
  | { command: "reset-default"; entry: LocalFileEntry }
  | { command: "archives" }
  | { command: "restore-archive"; archiveId: string; path: string }
  | { command: "delete-archive"; archiveId: string }
  | { command: "open-directory" }
  | { command: "open-archive"; archiveId?: string }
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
  productName: "Minecraft 整合包",
  subtitle: "准备好后，一起进入游戏。",
  serverAddress: "",
  coverObject: null,
  accentColor: "#2ee8df",
  secondaryAccentColor: "#b06cff",
  titleColor: "#fff8dc",
  welcomeText: "欢迎来到",
  topBarColor: "#030708",
  topBarOpacity: 0.22,
  cardColor: "#030708",
  brandName: "梦鱼更新器",
  brandEnglishName: "DreamingFish",
  newsArticles: null,
  customPage: null,
  contentPages: [],
  musicTracks: null,
};
