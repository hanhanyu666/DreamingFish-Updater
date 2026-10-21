import { invoke } from '@tauri-apps/api/core';

export type DeploymentDownload = {
    state: 'saved' | 'browser' | 'canceled';
    destination: string;
    size: number;
    receiptId?: string;
};

type DownloadOptions = {
    projectId: string;
    releaseId: string;
    platform: string;
    fileName: string;
    headers: Record<string, string>;
};

export function deploymentFileName(projectId: string, releaseVersion = '', programVersion = '', platform = '') {
    const name = [projectId, 'player-deployment', releaseVersion, programVersion, platform]
        .filter(Boolean).join('-').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
    return `${name.slice(0, 180) || 'dreamingfish-player-deployment'}.zip`;
}

function responseName(response: Response, fallback: string) {
    const raw = response.headers.get('Content-Disposition')?.match(/filename\*?=(?:UTF-8'')?"?([^";]+)/i)?.[1];
    if (!raw) return fallback;
    try {
        return decodeURIComponent(raw).split(/[\\/]/).pop()?.replace(/[\x00-\x1f<>:"|?*]/g, '_') || fallback;
    } catch {
        return fallback;
    }
}

async function deploymentResponse(options: DownloadOptions) {
    const response = await fetch(`/api/projects/${encodeURIComponent(options.projectId)}/deployment/download`, {
        method: 'POST',
        headers: { ...options.headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ releaseId: options.releaseId, platform: options.platform }),
        cache: 'no-store',
    });
    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || `部署包生成失败 (${response.status})`);
    }
    if (response.headers.get('Content-Type')?.split(';')[0].trim() !== 'application/zip') {
        await response.body?.cancel();
        throw new Error('管理端没有返回 ZIP 部署包，请检查连接后重试');
    }
    return response;
}

function checkSize(response: Response, size: number) {
    const expected = response.headers.get('Content-Length');
    if (size === 0 || (expected !== null && Number(expected) !== size)) {
        throw new Error('部署包没有完整下载，未保存此次内容，请重试');
    }
}

export async function saveDeployment(options: DownloadOptions): Promise<DeploymentDownload> {
    if (window.__DFS_DESKTOP_META__?.workspace) {
        const saved = await invoke<{ id: string; path: string; size: number } | null>('save_deployment_download', {
            request: {
                projectId: options.projectId,
                releaseId: options.releaseId,
                platform: options.platform,
                fileName: options.fileName,
            },
            sessionToken: options.headers['X-DFS-Token'] || '',
        });
        return saved
            ? { state: 'saved', destination: saved.path, size: saved.size, receiptId: saved.id }
            : { state: 'canceled', destination: '', size: 0 };
    }

    const browser = window as Window & {
        showSaveFilePicker?: (options: {
            suggestedName: string;
            types: { description: string; accept: Record<string, string[]> }[];
        }) => Promise<FileSystemFileHandle>;
    };
    if (browser.showSaveFilePicker) {
        // Ask while the original button click still has browser user activation.
        let handle: FileSystemFileHandle;
        try {
            handle = await browser.showSaveFilePicker({
                suggestedName: options.fileName,
                types: [{ description: 'ZIP 部署包', accept: { 'application/zip': ['.zip'] } }],
            });
        } catch (error) {
            if ((error as { name?: string })?.name === 'AbortError') {
                return { state: 'canceled', destination: '', size: 0 };
            }
            throw error;
        }
        const response = await deploymentResponse(options);
        let writer: WritableStreamDefaultWriter<FileSystemWriteChunkType> | undefined;
        const reader = response.body?.getReader();
        if (!reader) throw new Error('浏览器没有提供部署包下载内容');
        try {
            writer = (await handle.createWritable()).getWriter();
            let size = 0;
            while (true) {
                const chunk = await reader.read();
                if (chunk.done) break;
                await writer.write(chunk.value);
                size += chunk.value.byteLength;
            }
            checkSize(response, size);
            // File System Access commits its temporary file when close succeeds.
            await writer.close();
            return { state: 'saved', destination: handle.name, size };
        } catch (error) {
            await writer?.abort().catch(() => {});
            await reader.cancel().catch(() => {});
            throw error;
        } finally {
            writer?.releaseLock();
            reader.releaseLock();
        }
    }

    const response = await deploymentResponse(options);
    const content = await response.blob();
    checkSize(response, content.size);
    const name = responseName(response, options.fileName), url = URL.createObjectURL(content);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = name;
    document.body.append(anchor);
    try {
        anchor.click();
    } finally {
        anchor.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
    }
    // Ordinary browser downloads do not expose their save result to the page.
    return { state: 'browser', destination: name, size: content.size };
}

export async function revealDeployment(receiptId: string) {
    await invoke('reveal_deployment_download', { receiptId });
}
