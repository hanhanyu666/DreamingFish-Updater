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

/** CARD: in the server card; HERO: beside the spotlight button; HIDDEN: only used for the status check. */
export type AddressPlacement = "CARD" | "HERO" | "HIDDEN";

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
  /** Home page carousel. None: the home page shows the welcome title and the latest announcements. */
  spotlights?: PlayerSpotlight[] | null;
  /** The server card on the home page. None: a card with the product name and server address. */
  serverCard?: PlayerServerCard | null;
  /** Upcoming events; the home page counts down to the next one that has not ended. */
  events?: PlayerEvent[] | null;
  theme?: PlayerTheme | null;
  /** Where the home page shows the server address. None: in the server card. */
  addressPlacement?: AddressPlacement | null;
  /** An uploaded showcase image for the top-left mark. None: the DreamingFish icon. */
  brandLogoFile?: string | null;
  /** Release-managed file identities; never supplied by showcase JSON imports. */
  presentationImages?: { fileName: string; sha256: string; size: number }[] | null;
}

/**
 * An owner image: an uploaded file shipped with the release ({@link imageFile}, read locally by the
 * player app like music tracks) or a public http(s) address ({@link imageUrl}). The file wins when both are set.
 */
export interface PlayerImageSource {
  imageFile?: string | null;
  imageUrl?: string | null;
}

export type PlayerLinkKind = "PAGE" | "ARTICLE" | "URL";

/** Where a button goes: one of the owner's pages, one announcement, or an external address. */
export interface PlayerLinkTarget {
  label: string;
  kind: PlayerLinkKind;
  pageId?: string | null;
  articleId?: string | null;
  url?: string | null;
}

export interface PlayerSpotlight extends PlayerImageSource {
  id: string;
  /** Short pill above the title, for example 新赛季. */
  tag: string;
  /** Small uppercase line, for example SEASON 03 · NOW OPEN. */
  eyebrow: string;
  title: string;
  lead: string;
  /** Short name in the carousel switcher, for example 建筑大赛; the title when empty. */
  label?: string | null;
  action?: PlayerLinkTarget | null;
}

export type PlayerCommunityKind = "QQ" | "DISCORD" | "KOOK" | "BILIBILI" | "WEBSITE" | "OTHER";

/** A community button; without {@link url} it copies {@link copyText}, such as a QQ group number. */
export interface PlayerCommunityLink {
  id: string;
  kind: PlayerCommunityKind;
  label: string;
  url?: string | null;
  copyText?: string | null;
}

export interface PlayerServerCard {
  enabled: boolean;
  /** Ask the player app to ping the Minecraft server for online players, latency and MOTD. */
  showStatus: boolean;
  /** Overrides the version the server reports, for example 1.20.1 · Fabric. */
  versionLabel?: string | null;
  /** Overrides the MOTD the server reports; § colour codes are allowed. */
  motd?: string | null;
  /** Overrides the server icon the server reports. */
  logoFile?: string | null;
  links?: PlayerCommunityLink[] | null;
}

export interface PlayerEvent {
  id: string;
  title: string;
  /** ISO 8601 with an offset, for example 2026-10-24T20:00:00+08:00. */
  startsAt: string;
  /** Optional end; without it the event counts as ongoing for two hours. */
  endsAt?: string | null;
  action?: PlayerLinkTarget | null;
}

/** What the player app learned from a Minecraft server list ping. */
export interface ServerStatus {
  state: "CHECKING" | "ONLINE" | "OFFLINE";
  onlinePlayers?: number | null;
  maxPlayers?: number | null;
  latencyMs?: number | null;
  /** Legacy § formatted text converted from the ping's description. */
  motd?: string | null;
  version?: string | null;
  /** The server icon as a data:image/png;base64 address. */
  favicon?: string | null;
  checkedAt?: string | null;
}

export type PlayerPageLayout = "MARKDOWN" | "ANNOUNCEMENTS" | "GALLERY" | "BLOCKS";

export interface PlayerGalleryItem extends PlayerImageSource {
  id: string;
  title: string;
  author: string;
  category: string;
  caption?: string | null;
  /** In-game coordinates or a place name, shown in the lightbox. */
  location?: string | null;
  /** Marked 本周精选 on its tile. */
  featured?: boolean | null;
}

/**
 * Building blocks of a BLOCKS page. Every kind reads the same fields; docs/showcase/README.md
 * says which ones each kind shows.
 */
export type PlayerBlockKind =
  | "TEXT"
  | "CARDS"
  | "STEPS"
  | "LIST"
  | "TEAM"
  | "FAQ"
  | "STATS"
  | "CALLOUT"
  | "IMAGE"
  | "GALLERY";

/** Colour family of a block, taken from the project colours so every page stays in the theme. */
export type PlayerBlockTone = "DEFAULT" | "ACCENT" | "SECONDARY" | "WARM" | "MUTED";

export type PlayerIconName =
  | "star" | "shield" | "users" | "chat" | "globe" | "calendar" | "pin" | "image"
  | "sparkle" | "sword" | "pickaxe" | "heart" | "home" | "gift" | "trophy" | "book"
  | "clock" | "check" | "info" | "signal";

export interface PlayerBlockItem extends PlayerImageSource {
  id: string;
  title: string;
  text?: string | null;
  meta?: string | null;
  icon?: PlayerIconName | null;
  action?: PlayerLinkTarget | null;
}

export interface PlayerBlock extends PlayerImageSource {
  id: string;
  kind: PlayerBlockKind;
  title?: string | null;
  lead?: string | null;
  /** Label in the page's section navigation; the title when empty. */
  navLabel?: string | null;
  /** Small English line above the title, for example FEATURES; a default per kind when empty. */
  eyebrow?: string | null;
  tone?: PlayerBlockTone | null;
  /** 1–4 columns for CARDS, TEAM, STATS and GALLERY. */
  columns?: number | null;
  /** TEXT and CALLOUT. */
  markdown?: string | null;
  items?: PlayerBlockItem[] | null;
  actions?: PlayerLinkTarget[] | null;
}

/** Window-wide styling choices on top of the project colours. */
export interface PlayerTheme {
  corner?: "ROUND" | "SOFT" | "SQUARE" | null;
  surface?: "GLASS" | "SOLID" | "CLEAR" | null;
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
  /** Filter and badge on the news page, for example 活动 or 维护. */
  category?: string | null;
  /** Shown beside the date. */
  author?: string | null;
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
  /** Absent on older presentations: announcementPage picks ANNOUNCEMENTS, otherwise MARKDOWN. */
  layout?: PlayerPageLayout | null;
  gallery?: PlayerGalleryItem[] | null;
  blocks?: PlayerBlock[] | null;
  /** Header image of a BLOCKS page; the project background when empty. */
  imageFile?: string | null;
  imageUrl?: string | null;
  /** Short chips under a BLOCKS page title, for example Java 1.20.1 · Fabric. */
  tags?: string[] | null;
}

export interface AdminPreviewPayload {
  type: "dfs-admin-preview";
  branding: Branding;
  backgroundUrl: string | null;
  /** Uploaded image files the admin app can already show, by file name. */
  images?: Record<string, string> | null;
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
  | { type: "server-status"; status: ServerStatus }
  | { type: "presentation-images-updated" }
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
  | { command: "refresh-server-status" }
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
  titleColor: "#fff6e0",
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
  spotlights: null,
  serverCard: null,
  events: null,
  theme: null,
  addressPlacement: null,
  brandLogoFile: null,
};
