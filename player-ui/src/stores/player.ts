import { reactive, readonly } from "vue";
import { getBridge } from "../lib/bridge";
import {
  displayPath,
  formatAmount,
  formatBytes,
  formatUnmanagedModDetails,
  playerAddedMods,
  validColor,
} from "../lib/format";
import {
  DEFAULT_BRANDING,
  STAGE_NAMES,
  type Branding,
  type AdminPreviewPayload,
  type ConfirmRequest,
  type LocalFileEntry,
  type LocalModEntry,
  type ProgressEvent,
  type PlayerContentPage,
  type ReleaseHistory,
  type SidecarCommand,
  type SidecarMessage,
  type UpdateResultDto,
  type PlayerMusicTrack,
} from "../lib/types";
import type { NewsArticle } from "../lib/news";
import { loadBundledNews } from "../lib/news";

export type Page = "HOME" | "NEWS" | "CUSTOM" | "ABOUT" | `CONTENT:${string}`;
export type DrawerMode = "UPDATE" | "HISTORY" | "LOGS" | "FILES" | "PLAYER_MODS";
export type LocalManagementMode = "FILES" | "MODS";

export const DRAWER_LABELS: Record<DrawerMode, string> = {
  UPDATE: "本次更新",
  HISTORY: "更新记录",
  LOGS: "运行记录",
  FILES: "本地文件",
  PLAYER_MODS: "自选模组",
};

export interface UnmanagedNotice {
  text: string;
  mods: string[];
  contextLines: string[];
}

export interface ErrorState {
  title: string;
  detail: string;
  allowContinue: boolean;
}

const MUSIC_MUTED_STORAGE_KEY = "dfs-background-music-muted";
const MUSIC_LOOP_STORAGE_KEY = "dfs-background-music-loop";

function readMusicMutedPreference(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.localStorage.getItem(MUSIC_MUTED_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

function writeMusicMutedPreference(muted: boolean): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(MUSIC_MUTED_STORAGE_KEY, muted ? "1" : "0");
  } catch {
    // A restricted WebView storage area must not break the music control.
  }
}

function readMusicLoopPreference(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.localStorage.getItem(MUSIC_LOOP_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

function writeMusicLoopPreference(loop: boolean): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(MUSIC_LOOP_STORAGE_KEY, loop ? "1" : "0");
  } catch {
    // A restricted WebView storage area must not break the music control.
  }
}

interface PlayerState {
  preview: boolean;
  ready: boolean;
  playerProgramUpdating: boolean;
  maximized: boolean;
  branding: Branding;
  playerName: string;
  background: string | null;
  backgroundUrl: string | null;
  page: Page;
  logs: string[];
  releaseHistory: ReleaseHistory | null;
  progress: ProgressEvent | null;
  result: UpdateResultDto | null;
  unverifiedOffline: boolean;
  localContentOverride: boolean;
  error: ErrorState | null;
  working: boolean;
  launchPermitted: boolean;
  restartPending: boolean;
  mods: LocalModEntry[];
  files: LocalFileEntry[];
  unmanaged: UnmanagedNotice | null;
  drawerOpen: boolean;
  drawerMode: DrawerMode;
  drawerExpanded: boolean;
  localMode: LocalManagementMode;
  confirm: ConfirmRequest | null;
  launchNotice: string | null;
  countdownRemaining: number | null;
  latestNewsVisible: boolean;
  stageTitle: string;
  currentPathText: string;
  percent: string;
  byteSummary: string;
  newsRequest: { kind: "list" | "article"; pageId: string | null; articleId: string | null; seq: number };
  latestArticle: NewsArticle | null;
  latestArticlePageId: string | null;
  contentPages: PlayerContentPage[];
  fileTreeExpanded: Map<string, boolean>;
  newsArticles: NewsArticle[];
  newsLoadError: string | null;
  startupMusicUrl: string | null;
  musicMuted: boolean;
  musicPlaying: boolean;
  musicTracks: PlayerMusicTrack[];
  selectedMusicTrackId: string | null;
  musicLoop: boolean;
}

const state = reactive<PlayerState>({
  preview: false,
  ready: false,
  playerProgramUpdating: false,
  maximized: false,
  branding: { ...DEFAULT_BRANDING },
  playerName: "未识别玩家",
  background: null,
  backgroundUrl: null,
  page: "HOME",
  logs: [],
  releaseHistory: null,
  progress: null,
  result: null,
  unverifiedOffline: false,
  localContentOverride: false,
  error: null,
  working: false,
  launchPermitted: false,
  restartPending: false,
  mods: [],
  files: [],
  unmanaged: null,
  drawerOpen: false,
  drawerMode: "HISTORY",
  drawerExpanded: false,
  localMode: "FILES",
  confirm: null,
  launchNotice: null,
  countdownRemaining: null,
  latestNewsVisible: true,
  stageTitle: "正在启动更新器",
  currentPathText: "准备本地环境",
  percent: "--",
  byteSummary: "-- / --",
  newsRequest: { kind: "list", pageId: null, articleId: null, seq: 0 },
  latestArticle: null,
  latestArticlePageId: null,
  contentPages: [],
  fileTreeExpanded: new Map<string, boolean>(),
  newsArticles: [],
  newsLoadError: null,
  startupMusicUrl: null,
  musicMuted: readMusicMutedPreference(),
  musicPlaying: false,
  musicTracks: [],
  selectedMusicTrackId: null,
  musicLoop: readMusicLoopPreference(),
});

const bridge = getBridge();
let startupAudio: HTMLAudioElement | null = null;
let startupMusicGeneration = 0;
let startupMusicEnabled = false;

interface PendingConfirmation {
  request: ConfirmRequest;
  source: "local" | "sidecar";
  resolve?: (accepted: boolean) => void;
}

const confirmationQueue: PendingConfirmation[] = [];
let activeConfirmation: PendingConfirmation | null = null;

export function handleSidecarMessage(message: SidecarMessage): void {
  switch (message.type) {
    case "branding":
      setBranding(message.branding);
      break;
    case "background":
      state.background = message.path;
      state.backgroundUrl = null;
      void bridge.assetUrl(message.path)
        .then((url) => {
          if (state.background === message.path) state.backgroundUrl = url;
        })
        .catch(() => {
          if (state.background === message.path) state.backgroundUrl = null;
        });
      break;
    case "identity":
      state.playerName = message.name && message.name.trim().length > 0
        ? message.name
        : "未识别玩家";
      break;
    case "logs":
      state.logs = [...(message.lines ?? [])];
      break;
    case "log":
      state.logs.push(message.line);
      break;
    case "history":
      state.releaseHistory = message.history;
      break;
    case "progress":
      showProgress({
        ...message.event,
        fraction:
          message.event.totalBytes > 0
            ? Math.min(1, Math.max(0, message.event.completedBytes / message.event.totalBytes))
            : -1,
      });
      break;
    case "result":
      showResult(message.result);
      break;
    case "unverified-offline":
      showUnverifiedOfflineLaunch();
      break;
    case "local-content-override":
      showLocalContentOverrideLaunch();
      break;
    case "error":
      showError(message.title, message.detail, message.allowContinue);
      break;
    case "mods":
      state.mods = message.entries ?? [];
      break;
    case "files":
      state.files = message.entries ?? [];
      break;
    case "countdown":
      state.countdownRemaining = message.seconds;
      state.launchNotice = "Minecraft 已开始启动 · " + message.seconds + " 秒后自动关闭";
      break;
    case "launch-kept-open":
      state.launchNotice = "Minecraft 已开始启动 · 窗口将保持打开";
      state.countdownRemaining = null;
      break;
    case "restart-required":
      state.restartPending = true;
      void confirmLocal({
        id: 0,
        tone: "INFO",
        title: "需要重新启动游戏",
        heading: message.item,
        message:
          "请先关闭 DreamingFish Updater，再回到 MC 启动器重新启动游戏。当前已经启动的游戏不会自动重新加载刚恢复的文件。",
        actionText: "关闭更新器",
        cancelText: "稍后",
      }).then((accepted) => {
        if (accepted) sendCommand({ command: "quit" });
      });
      break;
    case "confirm-request":
      enqueueConfirmation({ request: message.request, source: "sidecar" });
      break;
    case "open-request":
      if (message.kind === "external" && message.value) bridge.openExternal(message.value);
      if (message.kind !== "external" && message.value) bridge.openPath(message.value);
      break;
    case "ready":
      state.ready = true;
      break;
    case "exit":
      bridge.window.close();
      break;
  }
}

export function setBranding(branding: Branding | null): void {
  const display = displayBranding(branding);
  state.branding = display;
  state.musicTracks = (display.musicTracks ?? []).filter((track) =>
    track.id.trim().length > 0 && track.fileName.trim().length > 0,
  );
  if (!state.musicTracks.some((track) => track.id === state.selectedMusicTrackId)) {
    state.selectedMusicTrackId = state.musicTracks[0]?.id ?? null;
  }
  if (state.musicTracks.length > 0 && !state.preview) {
    stopStartupMusic();
    void initializeStartupMusic();
  }
  state.contentPages = normalizeContentPages(display);
  refreshLatestArticle();
  if ((state.page.startsWith("CONTENT:") || state.page === "NEWS" || state.page === "CUSTOM")
      && !state.contentPages.some((page) => contentRoute(page.id) === state.page)) {
    state.page = "HOME";
  }
  document.documentElement.style.setProperty(
    "--dfs-accent",
    validColor(display.accentColor, "#2ee8df"),
  );
  document.documentElement.style.setProperty(
    "--dfs-secondary",
    validColor(display.secondaryAccentColor, "#b06cff"),
  );
}

export function displayBranding(branding: Branding | null | undefined): Branding {
  if (branding == null || unusableText(branding.productName)) return { ...DEFAULT_BRANDING };
  const subtitle = unusableText(branding.subtitle) ? DEFAULT_BRANDING.subtitle : branding.subtitle;
  return {
    productName: branding.productName,
    subtitle,
    serverAddress: branding.serverAddress,
    coverObject: branding.coverObject,
    accentColor: branding.accentColor,
    secondaryAccentColor: branding.secondaryAccentColor,
    brandName: unusableText(branding.brandName)
      ? DEFAULT_BRANDING.brandName : branding.brandName,
    brandEnglishName: unusableText(branding.brandEnglishName)
      ? DEFAULT_BRANDING.brandEnglishName : branding.brandEnglishName,
    newsArticles: branding.newsArticles ?? null,
    customPage: branding.customPage ?? null,
    contentPages: branding.contentPages ?? null,
    musicTracks: branding.musicTracks ?? null,
  };
}

function contentRoute(id: string): Page {
  if (id === "news") return "NEWS";
  if (id === "custom") return "CUSTOM";
  return `CONTENT:${id}`;
}

function normalizeContentPages(branding: Branding): PlayerContentPage[] {
  if (branding.contentPages != null) {
    return branding.contentPages.map((page) => ({
      ...page,
      articles: page.announcementPage ? [...(page.articles ?? [])] : [],
    }));
  }
  const pages: PlayerContentPage[] = [{
    id: "news",
    navigationLabel: "新闻",
    announcementPage: true,
    eyebrow: `${branding.brandEnglishName} NEWS`,
    title: `${branding.brandName}新闻`,
    lead: "这里记录服务器动态、版本消息和想与玩家分享的内容。",
    markdown: "",
    articles: branding.newsArticles ?? null,
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
      articles: [],
    });
  }
  return pages;
}

function uiArticles(page: PlayerContentPage): NewsArticle[] {
  return (page.articles ?? []).map((article) => ({
    id: article.id,
    title: article.title,
    summary: article.summary,
    publishedOn: article.publishedOn,
    cover: safeCoverUrl(article.coverUrl),
    markdown: article.markdown,
  })).sort((left, right) => {
    const date = right.publishedOn.localeCompare(left.publishedOn);
    return date !== 0 ? date : left.title.localeCompare(right.title);
  });
}

function refreshLatestArticle(): void {
  const newest = state.contentPages
    .filter((page) => page.announcementPage)
    .flatMap((page) => uiArticles(page).map((article) => ({ pageId: page.id, article })))
    .sort((left, right) => right.article.publishedOn.localeCompare(left.article.publishedOn))[0];
  state.latestArticle = newest?.article ?? null;
  state.latestArticlePageId = newest?.pageId ?? null;
  const firstAnnouncement = state.contentPages.find((page) => page.announcementPage);
  state.newsArticles = firstAnnouncement ? uiArticles(firstAnnouncement) : [];
  state.newsLoadError = null;
}

function safeCoverUrl(value: string | null | undefined): string {
  if (!value) return "";
  try {
    const uri = new URL(value);
    return uri.protocol === "http:" || uri.protocol === "https:" ? uri.toString() : "";
  } catch {
    return "";
  }
}

function unusableText(value: string | null | undefined): boolean {
  return value == null || value.trim().length === 0 || value.indexOf("\uFFFD") >= 0;
}

function showProgress(event: ProgressEvent): void {
  state.playerProgramUpdating = event.message === "正在更新玩家端程序";
  state.progress = event;
  state.error = null;
  state.unverifiedOffline = false;
  state.localContentOverride = false;
  state.working = true;
  state.launchNotice = null;
  state.countdownRemaining = null;
  if (event.totalBytes > 0) {
    state.byteSummary = formatAmount(event.completedBytes) + " / " + formatAmount(event.totalBytes);
    state.percent = Math.round(event.fraction * 100) + "%";
  } else {
    state.byteSummary = "正在计算变更";
    state.percent = "--";
  }
  state.currentPathText = displayPath(event.currentPath, event.message || "正在处理");
  state.stageTitle = state.playerProgramUpdating
    ? "玩家端正在自更新"
    : STAGE_NAMES[event.stage] ?? "正在处理更新";
}

function showResult(result: UpdateResultDto): void {
  state.playerProgramUpdating = false;
  state.result = result;
  state.progress = null;
  state.error = null;
  state.working = false;
  state.launchPermitted = true;
  ensureCurrentReleaseInHistory(result);
  if (result.outcome === "OFFLINE_ALLOWED") {
    state.stageTitle = "已使用离线许可";
    state.currentPathText = "正在使用最近一次完整验证的版本";
  } else if (result.outcome === "UP_TO_DATE") {
    state.stageTitle = "已是最新版本";
    state.currentPathText = "Minecraft 正在继续启动";
  } else {
    state.stageTitle = "更新已经完成";
    state.currentPathText = "Minecraft 正在继续启动";
  }
  state.percent = "100%";
  state.byteSummary = result.downloadedBytes > 0
    ? "已下载 " + formatBytes(result.downloadedBytes)
    : "本地文件已验证";
  showFileNotices(result);
}

function showUnverifiedOfflineLaunch(): void {
  state.playerProgramUpdating = false;
  state.unverifiedOffline = true;
  state.localContentOverride = false;
  state.progress = null;
  state.error = null;
  state.working = false;
  state.launchPermitted = true;
  state.stageTitle = "未验证离线启动";
  state.currentPathText = "无法连接更新服务器，本次未检查整合包";
  state.percent = "--";
  state.byteSummary = "未执行文件验证";
  state.unmanaged = null;
}

function showLocalContentOverrideLaunch(): void {
  state.playerProgramUpdating = false;
  state.localContentOverride = true;
  state.unverifiedOffline = false;
  state.progress = null;
  state.error = null;
  state.working = false;
  state.launchPermitted = true;
  state.stageTitle = "已忽略本地文件变更";
  state.currentPathText = "更新服务器不可用，Minecraft 将按当前本地文件继续启动";
  state.percent = "--";
  state.byteSummary = "本次未修复本地托管文件";
  state.unmanaged = null;
}

export function showError(title: string, detail: string, allowContinue: boolean): void {
  state.playerProgramUpdating = false;
  state.error = { title, detail, allowContinue };
  state.progress = null;
  state.working = false;
  state.stageTitle = title;
  state.currentPathText = detail;
  state.percent = "!";
  state.byteSummary = "Minecraft 启动已暂停";
}

export function appendLog(line: string): void {
  state.logs.push(line);
}

export function setLogs(lines: string[]): void {
  state.logs = [...lines];
}

function ensureCurrentReleaseInHistory(result: UpdateResultDto): void {
  const history = state.releaseHistory;
  if (history != null && history.releases.some((entry) => entry.releaseId === result.releaseId)) {
    return;
  }
  const current = {
    releaseId: result.releaseId,
    sequence: result.sequence,
    displayVersion: result.displayVersion,
    createdAt: result.createdAt,
    changelog: result.changelog,
  };
  const entries = history == null
    ? [current]
    : [current, ...history.releases];
  state.releaseHistory = {
    schemaVersion: 1,
    projectId: result.projectId ?? history?.projectId ?? "",
    releases: entries,
  };
}

function showFileNotices(result: UpdateResultDto): void {
  const archived = result.archivedFiles ?? [];
  const released = result.releasedPaths ?? [];
  const unmanagedMods = result.unmanagedMods ?? [];
  if (archived.length > 0 || released.length > 0) {
    const directories = (result.forcedSyncDirectories ?? []).length > 0
      ? (result.forcedSyncDirectories ?? []).map((value) => value + "/").join("、")
      : "所选目录";
    let text = "";
    if (archived.length > 0) {
      text =
        "远程管理端已对 " + directories + " 启用强制同步；已将 " +
        archived.length + " 个本地额外文件移入备份";
    }
    if (released.length > 0) {
      if (text.length > 0) text += "；";
      text += "服主已停止管理 " + released.length + " 个文件，本地副本已保留";
    }
    if (unmanagedMods.length > 0) {
      text += "；另有 " + unmanagedMods.length + " 个玩家自选模组已保留";
    }
    const noticeLines: string[] = [];
    if (archived.length > 0) {
      noticeLines.push("备份位置：" + (result.archiveDirectory ?? ""));
      archived.slice(0, 20).forEach((path) => noticeLines.push("备份：" + path));
    }
    released.slice(0, 20).forEach((path) => noticeLines.push("保留：" + path));
    if (unmanagedMods.length > 0) text += "  ›";
    updateUnmanagedNotice(text, unmanagedMods, noticeLines);
    return;
  }
  showUnmanaged(unmanagedMods);
}

export function showUnmanaged(mods: string[]): void {
  const present = mods.length > 0;
  const text = present
    ? "检测到 " + mods.length + " 个玩家自选模组，已保留并继续启动  ›"
    : "";
  updateUnmanagedNotice(text, mods, []);
}

function updateUnmanagedNotice(
  text: string,
  mods: string[],
  contextLines: string[],
): void {
  const present = text != null && text.trim().length > 0;
  if (!present) {
    state.unmanaged = null;
    return;
  }
  const details: string[] = [...contextLines];
  if (mods.length > 0) {
    if (details.length > 0) details.push("");
    details.push(formatUnmanagedModDetails(mods));
  }
  state.unmanaged = {
    text,
    mods: [...mods],
    contextLines: details,
  };
}

export function keepWindowOpen(): void {
  state.countdownRemaining = null;
  if (state.launchPermitted && state.launchNotice != null) {
    state.launchNotice = "Minecraft 已开始启动 · 窗口将保持打开";
  }
  sendCommand({ command: "keep-open" });
}

export function requestClose(): void {
  if (closeCommandForState(state.preview, state.launchPermitted, state.restartPending) === "quit") {
    quit();
    return;
  }
  // Java owns the update/launch state and is the single source of business
  // confirmations. Sending close directly avoids showing a second local dialog.
  sendCommand({ command: "close" });
}

export function closeCommandForState(
  preview: boolean,
  launchPermitted: boolean,
  restartPending: boolean,
): "close" | "quit" {
  return preview || launchPermitted || restartPending ? "quit" : "close";
}

function quit(): void {
  sendCommand({ command: "quit" });
  bridge.window.close();
}

export function sendCommand(command: SidecarCommand): void {
  bridge.sendCommand(command);
}

export function answerConfirm(accepted: boolean): void {
  const pending = activeConfirmation;
  if (pending == null) return;
  activeConfirmation = null;
  state.confirm = null;
  if (pending.source === "sidecar") {
    if (bridge.isTauri) {
      sendCommand({ command: "confirm", id: pending.request.id, accepted });
    }
  } else {
    pending.resolve?.(accepted);
  }
  showNextConfirmation();
}

function confirmLocal(request: ConfirmRequest): Promise<boolean> {
  return new Promise((resolve) => {
    enqueueConfirmation({ request, source: "local", resolve });
  });
}

function enqueueConfirmation(pending: PendingConfirmation): void {
  confirmationQueue.push(pending);
  showNextConfirmation();
}

function showNextConfirmation(): void {
  if (activeConfirmation != null) return;
  activeConfirmation = confirmationQueue.shift() ?? null;
  state.confirm = activeConfirmation?.request ?? null;
}

export function openDrawer(mode: DrawerMode): void {
  state.drawerOpen = true;
  state.drawerMode = mode;
  keepWindowOpen();
}

export function toggleDrawer(mode: DrawerMode): void {
  if (state.drawerOpen && state.drawerMode === mode) {
    hideDrawer();
    return;
  }
  openDrawer(mode);
}

export function hideDrawer(): void {
  state.drawerOpen = false;
}

export function setDrawerExpanded(expanded: boolean): void {
  state.drawerExpanded = expanded;
}

export function showLocalMode(mode: LocalManagementMode): void {
  state.localMode = mode;
}

export function showPage(page: Page): void {
  if (state.page === page) {
    if (page.startsWith("CONTENT:") || page === "NEWS" || page === "CUSTOM") {
      const pageId = page === "NEWS" ? "news"
        : page === "CUSTOM" ? "custom" : page.substring("CONTENT:".length);
      state.newsRequest = { kind: "list", pageId, articleId: null, seq: state.newsRequest.seq + 1 };
    }
    return;
  }
  hideDrawer();
  state.page = page;
}

export function openLatestNews(): void {
  if (!state.latestArticlePageId) return;
  showPage(contentRoute(state.latestArticlePageId));
  state.newsRequest = {
    kind: "article",
    pageId: state.latestArticlePageId,
    articleId: null,
    seq: state.newsRequest.seq + 1,
  };
}

export function setLatestArticle(article: NewsArticle | null): void {
  state.latestArticle = article;
}

export async function loadNews(): Promise<void> {
  if (state.branding.contentPages != null || state.branding.newsArticles != null) return;
  const legacyPage = state.contentPages.find((page) => page.id === "news" && page.announcementPage);
  if (!legacyPage || legacyPage.articles != null) return;
  try {
    const articles = await loadBundledNews();
    if (state.branding.contentPages != null || state.branding.newsArticles != null) return;
    legacyPage.articles = articles.map((article) => ({
      id: article.id, title: article.title, summary: article.summary,
      publishedOn: article.publishedOn, coverUrl: article.cover,
      markdown: article.markdown,
    }));
    refreshLatestArticle();
  } catch (error) {
    state.newsLoadError = String(error);
  }
}

export function navigationPages(): Array<{ page: Page; label: string }> {
  const pages: Array<{ page: Page; label: string }> = [{ page: "HOME", label: "主页" }];
  state.contentPages.forEach((content) => {
    pages.push({ page: contentRoute(content.id), label: content.navigationLabel });
  });
  pages.push({ page: "ABOUT", label: "关于更新器" });
  return pages;
}

export function applyAdminPreview(payload: AdminPreviewPayload): void {
  setBranding(payload.branding);
  state.preview = true;
  state.playerName = "玩家预览";
  state.background = null;
  state.backgroundUrl = payload.backgroundUrl;
  state.ready = true;
  state.working = false;
  state.launchPermitted = true;
  state.stageTitle = "已是最新版本";
  state.currentPathText = "本地文件已验证";
  state.percent = "100%";
  state.byteSummary = "";
  state.progress = {
    stage: "COMPLETE",
    message: "本地文件已验证",
    currentPath: null,
    completedBytes: 1,
    totalBytes: 1,
    fraction: 1,
  };
  state.error = null;
  state.result = null;
  state.countdownRemaining = null;
  state.launchNotice = null;
}

export function setFileTreeExpanded(expanded: Map<string, boolean>): void {
  state.fileTreeExpanded = expanded;
}

export function confirmRestoreFiles(): void {
  void confirmLocal({
    id: -1,
    tone: "INFO",
    title: "恢复文件管理",
    heading: "恢复更新器管理全部文件吗？",
    message:
      "所有文件和目录的本地豁免都会清除。下次校验时，普通 ENFORCED 文件将恢复为服务器当前版本。",
    actionText: "恢复全部管理",
    cancelText: "取消",
  }).then((accepted) => {
    if (accepted) sendCommand({ command: "restore-files" });
  });
}

export function confirmRestoreMods(): void {
  void confirmLocal({
    id: -1,
    tone: "INFO",
    title: "恢复整合包默认",
    heading: "恢复全部模组吗？",
    message:
      "所有本地停用选择都会清除。整合包模组将恢复为服务器当前版本，玩家自己添加的模组会放回原目录。",
    actionText: "恢复默认",
    cancelText: "取消",
  }).then((accepted) => {
    if (accepted) sendCommand({ command: "restore-mods" });
  });
}

export function confirmDisableMod(entry: LocalModEntry): Promise<boolean> {
  return confirmLocal({
    id: -1,
    tone: "WARNING",
    title: "停用本地模组",
    heading: "确认停用这个模组吗？",
    message:
      "停用 “" +
      entry.displayName +
      "” 可能导致依赖它的模组无法加载，也可能使你无法进入服务器。确认后更新器将不再自动恢复它。",
    actionText: "确认停用",
    cancelText: "取消",
  });
}

export function openPlayerModPage(): void {
  if (playerAddedMods(state.mods, state.unmanaged?.mods).length === 0) return;
  openDrawer("PLAYER_MODS");
}

export function countdownTick(): void {
  if (state.countdownRemaining == null) return;
  state.countdownRemaining -= 1;
  if (state.countdownRemaining > 0) {
    state.launchNotice = "Minecraft 已开始启动 · " + state.countdownRemaining + " 秒后自动关闭";
  }
}

export function startPreview(): void {
  state.preview = true;
  disableStartupMusic();
  bridge.startPreview();
}

export function setMaximized(maximized: boolean): void {
  state.maximized = maximized;
}

export async function initializeStartupMusic(): Promise<void> {
  if (startupAudio != null || state.preview || !startupMusicEnabled) return;
  const generation = ++startupMusicGeneration;
  try {
    const url = state.musicTracks.length > 0
      ? await bridge.musicTrackUrl(state.musicTracks[0].fileName)
      : await bridge.startupMusic();
    if (!url || generation !== startupMusicGeneration || state.preview
        || !startupMusicEnabled) return;
    const audio = attachMusicAudio(url);
    if (!state.musicMuted) {
      await audio.play().catch(() => undefined);
    }
  } catch {
    // Music is optional; a missing or unreadable file must never block startup.
  }
}

export async function enableStartupMusic(): Promise<void> {
  if (state.preview) return;
  startupMusicEnabled = true;
  await initializeStartupMusic();
}

export function disableStartupMusic(): void {
  startupMusicEnabled = false;
  stopStartupMusic();
}

function attachMusicAudio(url: string): HTMLAudioElement {
  const audio = new Audio(url);
  audio.preload = "auto";
  audio.loop = state.musicLoop;
  audio.volume = 0.42;
  audio.addEventListener("play", () => { if (startupAudio === audio) state.musicPlaying = true; });
  audio.addEventListener("pause", () => { if (startupAudio === audio) state.musicPlaying = false; });
  audio.addEventListener("ended", () => { if (startupAudio === audio) state.musicPlaying = false; });
  audio.addEventListener("error", () => { if (startupAudio === audio) stopStartupMusic(); });
  startupAudio?.pause();
  startupAudio = audio;
  state.startupMusicUrl = url;
  return audio;
}

export async function selectMusicTrack(id: string): Promise<void> {
  const track = state.musicTracks.find((entry) => entry.id === id);
  if (!track) return;
  state.selectedMusicTrackId = track.id;
  try {
    const url = await bridge.musicTrackUrl(track.fileName);
    if (url) {
      const wasPlaying = state.musicPlaying;
      attachMusicAudio(url);
      if (wasPlaying && !state.musicMuted) await startupAudio?.play().catch(() => undefined);
    }
  } catch {
    // A missing track is ignored; the rest of the updater remains usable.
  }
}

export function toggleMusicLoop(): void {
  state.musicLoop = !state.musicLoop;
  writeMusicLoopPreference(state.musicLoop);
  if (startupAudio) startupAudio.loop = state.musicLoop;
}

export function toggleStartupMusic(): void {
  const audio = startupAudio;
  if (audio == null) return;
  if (audio.paused) {
    void audio.play().then(() => {
      state.musicMuted = false;
      writeMusicMutedPreference(false);
    }).catch(() => undefined);
  } else {
    audio.pause();
    state.musicMuted = true;
    writeMusicMutedPreference(true);
  }
}

export function stopStartupMusic(): void {
  startupMusicGeneration++;
  const audio = startupAudio;
  startupAudio = null;
  state.startupMusicUrl = null;
  state.musicPlaying = false;
  if (audio != null) {
    audio.pause();
    audio.removeAttribute("src");
    try {
      audio.load();
    } catch {
      // Some embedded WebViews do not implement load() for an empty source.
    }
  }
}

export function usePlayerStore() {
  return {
    state: readonly(state),
    handleSidecarMessage,
    setBranding,
    setLogs,
    appendLog,
    keepWindowOpen,
    requestClose,
    answerConfirm,
    openDrawer,
    toggleDrawer,
    hideDrawer,
    setDrawerExpanded,
    showLocalMode,
    showPage,
    openLatestNews,
    setLatestArticle,
    loadNews,
    navigationPages,
    applyAdminPreview,
    setFileTreeExpanded,
    confirmRestoreFiles,
    confirmRestoreMods,
    confirmDisableMod,
    openPlayerModPage,
    countdownTick,
    startPreview,
    setMaximized,
    selectMusicTrack,
    toggleMusicLoop,
    initializeStartupMusic,
    enableStartupMusic,
    disableStartupMusic,
    toggleStartupMusic,
    stopStartupMusic,
    playerAddedMods: () => playerAddedMods(state.mods, state.unmanaged?.mods),
    drawerLabels: DRAWER_LABELS,
    sendCommand,
  };
}
