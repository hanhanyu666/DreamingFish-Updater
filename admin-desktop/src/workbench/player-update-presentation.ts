import type {Data} from './model';

/** Numeric x.y.z comparison; pre-release suffixes sort before the plain version, like the server. */
export function compareVersions(a: string, b: string) {
    const parse = (value: string) => { const [core, pre] = String(value).split('-', 2); return { parts: core.split('.').map(Number), pre }; };
    const left = parse(a), right = parse(b);
    for (let i = 0; i < 3; i++) { const diff = (left.parts[i] || 0) - (right.parts[i] || 0); if (diff) return Math.sign(diff); }
    if (left.pre === right.pre) return 0;
    return left.pre === undefined ? 1 : right.pre === undefined ? -1 : left.pre < right.pre ? -1 : 1;
}

/**
 * Programs already in management but not yet given to players. Older versions stay in history
 * and are never offered, because approval cannot lower the version players receive.
 */
export function playerUpgradeChoices(programs: Data[], target: Data | undefined) {
    return [...programs].sort((a, b) => compareVersions(b.version, a.version))
        .filter(program => !target || compareVersions(program.version, target.version) > 0);
}

/** Whether the official release would give players something newer than the approved version. */
export function officialOffer(release: Data | null | undefined, target: Data | undefined) {
    if (!release) return null;
    const newer = !target || compareVersions(release.version, target.version) > 0;
    return { newer, label: newer ? `官方新版 ${release.version}` : `已是官方最新 ${release.version}` };
}

export function playerUpdatePresentation(policy: Data | null | undefined, platform: string) {
    const target = policy?.targets?.[platform];
    const hasTargets = Object.keys(policy?.targets || {}).length > 0;
    const enabled = Boolean(policy?.enabled);
    return {
        target, hasTargets, enabled,
        badge: enabled ? '已开启' : hasTargets ? '已暂停' : '尚未开启',
        title: !hasTargets ? '由你决定玩家何时升级' : !enabled ? '已暂停后续升级'
            : target ? `玩家可升级到 ${target.version}` : '这个平台还没有发布给玩家的版本',
        detail: !hasTargets ? '先检查官方新版，再点“发布给玩家”。玩家下次打开更新器时自动升级。'
            : !enabled ? '玩家继续使用已安装的程序；已经升级的人不会退回旧版。'
            : target ? '玩家下次打开更新器时，会检查并升级到这个版本。'
            : '先为上方选中的平台发布一个版本，才能向这些玩家提供升级。',
    };
}

/** A running official publish shows how far its download has come; slow hosts may need most of an hour. */
export function downloadProgress(job: Data | null | undefined) {
    if (!job || !['QUEUED', 'RUNNING', 'COMMITTING'].includes(job.state) || !(job.total > 1)) return null;
    const done = Math.max(0, Math.min(job.completed, job.total));
    return { phase: job.phase || '正在处理', done, total: job.total, percent: Math.floor(done / job.total * 100) };
}

/** Automatic publishing runs on the management service; it never resumes a paused project. */
export function autoPublishPresentation(auto: Data | null | undefined) {
    if (!auto?.enabled) return { tone: 'info', title: '', detail: '' };
    if (auto.problem) return { tone: 'warning', title: '上次自动发布没有完成', detail: auto.problem };
    if (auto.publishedVersion) return { tone: 'success', title: `已自动发布 ${auto.publishedVersion}`, detail: '' };
    return { tone: 'info', title: auto.checkedAt ? '已检查，暂时没有更新的官方版本' : '等待第一次检查', detail: '' };
}

/** Whether publishes reach the saved OSS / WebDAV destination by themselves. */
export function autoUploads(saved: Data | null | undefined) {
    return Boolean(saved?.autoUpload && !saved?.problem);
}

/** The reminder appended after a publish, pause or resume. */
export function uploadReminder(saved: Data | null | undefined) {
    return autoUploads(saved) ? '管理端稍后自动上传到已保存的外部托管' : '使用 OSS 等外部托管时，请重新导出并上传';
}

/** State of the saved destination's automatic upload, shown on the hosting page. */
export function savedHostingPresentation(saved: Data | null | undefined) {
    if (!saved) return { tone: 'info', title: '', detail: '' };
    if (saved.problem) return { tone: 'warning', title: '已保存的设置无法使用', detail: saved.problem };
    const status = saved.autoStatus;
    if (status?.problem) return { tone: 'warning', title: '上次自动上传没有完成', detail: status.problem };
    if (!saved.autoUpload) return { tone: 'info', title: '自动上传已关闭', detail: '发布后请点击“立即上传”。' };
    if (status?.uploadedAt) return { tone: 'success', title: '外部托管已是最新内容', detail: '' };
    return { tone: 'info', title: '等待下一次发布', detail: '' };
}

/** An upload receipt describes one built-in destination, not every external download site. */
export function playerDeliveryPresentation(policy: Data | null | undefined, delivery: Data | null | undefined, saved?: Data | null) {
    if (delivery?.warning) return { tone: 'warning', title: '暂时无法确认上次上传的结果',
        detail: '请核对玩家下载位置的文件；当前记录不能证明这次设置已经上传。' };
    const revision = policy?.revision;
    if (revision != null && delivery?.uploadedRevision === revision && delivery?.uploadedAt) {
        const provider = delivery?.provider === 's3' ? '对象存储' : delivery?.provider === 'webdav' ? 'WebDAV' : '外部下载位置';
        return { tone: 'success', title: '本次设置已随最近一次上传发出',
            detail: `本管理端已上传到${provider}。其他下载网站和 CDN 缓存仍需分别核对。` };
    }
    if (saved?.autoStatus?.problem) return { tone: 'warning', title: '自动上传到外部托管没有完成',
        detail: saved.autoStatus.problem };
    if (autoUploads(saved)) return { tone: 'info', title: '稍后自动上传到外部托管',
        detail: `管理服务会在约半分钟内把这次设置上传到 ${saved!.destination}。` };
    if (revision != null && delivery?.exportedRevision === revision && delivery?.exportedAt) {
        return { tone: 'warning', title: '文件已导出，使用外部托管时还要上传',
            detail: '仅导出不会改变 OSS 等网站上的文件，请把这次导出的内容上传到玩家下载的位置。' };
    }
    return { tone: 'info', title: '玩家从 OSS 等外部位置下载？还需要上传一次',
        detail: '请到“外部托管”重新导出并上传，才能把这次发布或暂停的设置交给那里的玩家。' };
}
