import { invoke } from '@tauri-apps/api/core';

export type HostingMethod = 'export' | 'webdav' | 's3';
export type HostingValues = {
    outputDirectory: string; baseUrl: string; username: string; password: string;
    endpoint: string; region: string; bucket: string; prefix: string; accessKeyId: string;
    secretAccessKey: string; sessionToken: string; addressingStyle: string; exportFirst: boolean;
};
type SafeField = 'outputDirectory' | 'baseUrl' | 'username' | 'endpoint' | 'region' | 'bucket' | 'prefix' | 'accessKeyId' | 'addressingStyle';
export type HostingEntry = { id: string; method: HostingMethod; savedAt: number; values: Partial<Record<SafeField, string>> };
export const historyLimit = 10;
const storagePrefix = 'dfs-hosting-history-v1:';
const fields: Record<HostingMethod, readonly SafeField[]> = {
    export: ['outputDirectory'],
    webdav: ['outputDirectory', 'baseUrl', 'username'],
    s3: ['outputDirectory', 'endpoint', 'region', 'bucket', 'prefix', 'accessKeyId', 'addressingStyle'],
};
export function emptyHosting(): HostingValues {
    return { outputDirectory: '', baseUrl: '', username: '', password: '', endpoint: '', region: 'auto',
        bucket: '', prefix: '', accessKeyId: '', secretAccessKey: '', sessionToken: '', addressingStyle: 'PATH', exportFirst: true };
}
export function hostingRequest(method: HostingMethod, form: HostingValues) {
    const common = { outputDirectory: form.outputDirectory, exportFirst: form.exportFirst };
    if (method === 'export') return common;
    if (method === 'webdav') return { ...common, baseUrl: form.baseUrl, username: form.username, password: form.password };
    return { ...common, endpoint: form.endpoint, region: form.region, bucket: form.bucket, prefix: form.prefix,
        accessKeyId: form.accessKeyId, secretAccessKey: form.secretAccessKey, sessionToken: form.sessionToken, addressingStyle: form.addressingStyle };
}
function safeUrl(value: string) {
    if (!value) return '';
    try {
        const url = new URL(value);
        if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) return '';
        if (!url.username && !url.password && !url.search && !url.hash) return value;
        url.username = ''; url.password = ''; url.search = ''; url.hash = '';
        return url.toString();
    } catch { return ''; }
}
export function safeHostingValues(method: HostingMethod, input: unknown): HostingEntry['values'] {
    const source = input && typeof input === 'object' ? input as Record<string, unknown> : {};
    const values: HostingEntry['values'] = {};
    for (const field of fields[method]) {
        if (typeof source[field] !== 'string') continue;
        let value = (source[field] as string).trim();
        if (value.length > 2048 || /[\x00-\x1f\x7f]/.test(value)) throw new Error('填写历史中的字段过长或包含控制字符');
        if (field === 'baseUrl' || field === 'endpoint') value = safeUrl(value);
        if (field === 'addressingStyle' && !['PATH', 'VIRTUAL_HOST'].includes(value)) value = 'PATH';
        if (value) values[field] = value;
    }
    return values;
}
export function parseHostingEntries(value: unknown): HostingEntry[] {
    if (!Array.isArray(value)) throw new Error('填写历史格式异常，原记录已保留');
    const seen = new Set<string>(), result: HostingEntry[] = [];
    for (const entry of value.slice(0, historyLimit)) {
        if (!entry || typeof entry !== 'object' || !['export', 'webdav', 's3'].includes(entry.method)
            || typeof entry.id !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(entry.id)
            || !Number.isSafeInteger(entry.savedAt) || entry.savedAt <= 0 || entry.savedAt > 8_640_000_000_000_000) {
            throw new Error('填写历史格式异常，原记录已保留');
        }
        if (seen.has(entry.id)) continue;
        seen.add(entry.id);
        result.push({ id: entry.id, method: entry.method, savedAt: entry.savedAt, values: safeHostingValues(entry.method, entry.values) });
    }
    return result;
}
export function rememberHosting(entries: HostingEntry[], method: HostingMethod, input: unknown, savedAt = Date.now()): HostingEntry[] {
    const values = safeHostingValues(method, input);
    if (!Object.entries(values).some(([key, value]) => value && key !== 'region' && key !== 'addressingStyle')) {
        throw new Error('请先填写目录、地址或存储桶，再保存填写历史');
    }
    const same = (entry: HostingEntry) => entry.method === method && JSON.stringify(entry.values) === JSON.stringify(values);
    const existing = entries.find(same);
    const id = existing?.id || (typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : savedAt.toString(36) + '-' + Math.random().toString(36).slice(2));
    return [{ id, method, savedAt, values }, ...entries.filter(entry => !same(entry))].slice(0, historyLimit);
}
export function restoreHosting(entry: HostingEntry): HostingValues {
    return { ...emptyHosting(), ...safeHostingValues(entry.method, entry.values) };
}
export function hostingEntryLabel(entry: HostingEntry) {
    const v = entry.values;
    if (entry.method === 's3') return (v.bucket || '对象存储') + (v.endpoint ? ' · ' + v.endpoint : '');
    if (entry.method === 'webdav') return 'WebDAV · ' + (v.baseUrl || v.outputDirectory || '未填写地址');
    return '仅导出 · ' + (v.outputDirectory || '未填写目录');
}
function checkScope(scope: string) {
    if (!scope || scope.length > 2048) throw new Error('项目尚未加载，无法读取填写历史');
}
export async function loadHostingHistory(scope: string): Promise<HostingEntry[]> {
    checkScope(scope);
    if (window.__DFS_DESKTOP_META__?.workspace) {
        return parseHostingEntries(await invoke('load_hosting_history', { projectKey: scope, desktopToken: window.__DFS_DESKTOP_TOKEN__ || '' }));
    }
    const raw = localStorage.getItem(storagePrefix + scope);
    if (!raw) return [];
    if (raw.length > 128 * 1024) throw new Error('填写历史过大，原记录已保留');
    let data;
    try { data = JSON.parse(raw); } catch { throw new Error('填写历史格式异常，原记录已保留'); }
    if (data?.version !== 1) throw new Error('填写历史版本不受支持，原记录已保留');
    return parseHostingEntries(data.entries);
}
export async function saveHostingHistory(scope: string, entries: HostingEntry[]): Promise<void> {
    checkScope(scope);
    const safe = parseHostingEntries(entries);
    if (window.__DFS_DESKTOP_META__?.workspace) {
        await invoke('save_hosting_history', { projectKey: scope, entries: safe, desktopToken: window.__DFS_DESKTOP_TOKEN__ || '' });
        return;
    }
    const raw = JSON.stringify({ version: 1, entries: safe });
    if (raw.length > 128 * 1024) throw new Error('填写历史过大，请减少保存的记录');
    localStorage.setItem(storagePrefix + scope, raw);
}
