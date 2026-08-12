export type PlayerLogLevel = "START" | "INFO" | "WARN" | "ERROR";

export interface PlayerLogEntry {
  id: number;
  date: string | null;
  time: string;
  level: PlayerLogLevel;
  category: string;
  message: string;
  details: string[];
  legacy: boolean;
}

export interface PlayerLogGroup {
  key: string;
  label: string;
  entries: PlayerLogEntry[];
}

const CURRENT_LINE = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}(?:\.\d{3})?) \| (START|INFO|WARN|ERROR)\s* \| ([^|]+?) \| (.*)$/;
const LEGACY_LINE = /^(\d{2}:\d{2}:\d{2})\s{2,}(INFO|WARN|ERROR)\s{2,}(.*)$/;

export function parsePlayerLogs(lines: readonly string[]): PlayerLogEntry[] {
  const entries: PlayerLogEntry[] = [];
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index] ?? "";
    const current = CURRENT_LINE.exec(raw);
    if (current != null) {
      entries.push({
        id: index,
        date: current[1],
        time: current[2],
        level: current[3] as PlayerLogLevel,
        category: current[4].trim() || "运行",
        message: current[5].trim(),
        details: [],
        legacy: false,
      });
      continue;
    }

    const legacy = LEGACY_LINE.exec(raw);
    if (legacy != null) {
      entries.push({
        id: index,
        date: null,
        time: legacy[1],
        level: legacy[2] as PlayerLogLevel,
        category: "旧版日志",
        message: legacy[3].trim(),
        details: [],
        legacy: true,
      });
      continue;
    }

    if (raw.trim().length === 0) continue;
    const previous = entries.at(-1);
    if (previous != null && /^\s+/.test(raw)) {
      previous.details.push(raw.trim());
      continue;
    }
    entries.push({
      id: index,
      date: null,
      time: "--:--:--",
      level: "INFO",
      category: "旧版日志",
      message: raw.trim(),
      details: [],
      legacy: true,
    });
  }
  return entries;
}

export function groupPlayerLogs(
  lines: readonly string[],
  now: Date = new Date(),
): PlayerLogGroup[] {
  const groups = new Map<string, PlayerLogEntry[]>();
  for (const entry of parsePlayerLogs(lines)) {
    const key = entry.date ?? "legacy";
    const group = groups.get(key);
    if (group == null) groups.set(key, [entry]);
    else group.push(entry);
  }
  return [...groups.entries()].map(([key, entries]) => ({
    key,
    label: key === "legacy" ? "旧版记录 · 当时未保存日期" : dateLabel(key, now),
    entries,
  }));
}

export function logLevelLabel(level: PlayerLogLevel): string {
  switch (level) {
    case "START": return "启动";
    case "INFO": return "信息";
    case "WARN": return "提醒";
    case "ERROR": return "错误";
  }
}

function dateLabel(value: string, now: Date): string {
  const today = localDateKey(now);
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const prefix = value === today
    ? "今天 · "
    : value === localDateKey(yesterday)
      ? "昨天 · "
      : "";
  const [year, month, day] = value.split("-");
  return `${prefix}${year}年${Number(month)}月${Number(day)}日`;
}

function localDateKey(value: Date): string {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}
