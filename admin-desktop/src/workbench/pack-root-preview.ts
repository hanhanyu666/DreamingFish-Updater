export function relativeRootForLevels(levels: number) {
    if (!Number.isInteger(levels) || levels < 1 || levels > 8) throw new Error('请选择向上 1 至 8 级');
    return Array(levels).fill('..').join('/');
}

/** A schematic of a version-isolated instance, never a scan of a player's files. */
export function packRootPreview(value: string) {
    const normalized = value.trim().replaceAll('\\', '/').replace(/\/+$/, '');
    const levels = /^\.\.(?:\/\.\.){0,7}$/.test(normalized) ? normalized.split('/').length : null;
    const highest = Math.max(3, levels || 3);
    const labels: Record<number, string> = { 0: '游戏版本（实例）', 1: 'versions', 2: '.minecraft', 3: '整合包' };
    const rows: { id: string; name: string; depth: number; levels: number | null; selected: boolean; origin: boolean; branch: string }[] = [];
    for (let up = highest; up >= 0; up--) {
        rows.push({ id: `up-${up}`, name: labels[up] || `整合包上方第 ${up - 3} 级目录`, depth: highest - up,
            levels: up, selected: levels === up, origin: up === 0, branch: up === highest ? '' : '└─' });
        if (up === 3) rows.push({ id: 'launcher', name: '启动器.exe', depth: highest - up + 1,
            levels: null, selected: false, origin: false, branch: '├─' });
    }
    for (const name of ['mods', 'config']) rows.push({ id: name, name, depth: highest + 1,
        levels: null, selected: false, origin: false, branch: name === 'mods' ? '├─' : '└─' });
    return { levels, relativeRoot: levels === null ? null : relativeRootForLevels(levels), rows,
        rootName: levels === null ? '' : rows.find(row => row.selected)!.name };
}

/** Same default as the management service: `<pack>/.minecraft/versions/<instance>`, with `*` for any one folder. */
export function defaultLayout(levels: number) {
    const known = ['.minecraft', 'versions', '*'];
    return [...Array(Math.max(0, levels - known.length)).fill('*'), ...known.slice(Math.max(0, known.length - levels))];
}

/** Folder names the player's path must have from the update root down to the instance; null when unusable. */
export function parseLayout(value: string, levels: number | null) {
    if (levels === null) return null;
    const segments = value.trim().replaceAll('\\', '/').replace(/^\/+|\/+$/g, '').split('/').map(part => part.trim());
    if (segments.length !== levels) return null;
    const valid = segments.every(part => part && part.length <= 64 && part !== '.' && part !== '..'
        && !/[:]/.test(part) && (part === '*' || !part.includes('*')));
    return valid ? segments : null;
}
