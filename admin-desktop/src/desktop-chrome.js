// Shared by the bundled chooser and the desktop-owned management webview.
(() => {
  if (window.parent !== window) return;
  const meta = window.__DFS_DESKTOP_META__ || {};
  const diagnostic = (message) => {
    if (!meta.check) return;
    const report = () => { document.title = `DFS_FRAME_ERROR:${message}`; };
    if (document.head) report(); else document.addEventListener('DOMContentLoaded', report, { once:true });
  };
  if (meta.check) window.addEventListener('error', e => diagnostic(e.message));
  const install = () => {
    const root = document.documentElement;
    root.classList.add('dfs-desktop');
    if (meta.workspace) root.classList.add('dfs-workspace');
    if (document.getElementById('dfs-desktop-frame')) return;
    if (meta.css) {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(meta.css);
      document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
    }
    const frame = document.createElement('header');
    frame.id = 'dfs-desktop-frame';
    frame.setAttribute('aria-label', '桌面窗口');
    const mark = document.createElement('span');
    // The app icon, as in src/brand-logo.ts (this file also runs as a plain initialization script, so it keeps a copy).
    mark.className = 'dfs-frame-mark'; mark.innerHTML = '<svg viewBox="0 0 512 512" aria-hidden="true"><defs><linearGradient id="dfs-frame-bg" x1="92" y1="0" x2="391" y2="512" gradientUnits="userSpaceOnUse"><stop stop-color="#6399EC"/><stop offset="1" stop-color="#3D70CB"/></linearGradient><linearGradient id="dfs-frame-body" x1="230" y1="164" x2="314" y2="371" gradientUnits="userSpaceOnUse"><stop stop-color="#FFFCF1"/><stop offset="1" stop-color="#FAEFD9"/></linearGradient><clipPath id="dfs-frame-tile"><rect width="512" height="512" rx="120"/></clipPath><clipPath id="dfs-frame-fish"><path d="M182 244c1-50 43-84 98-84 70 0 119 49 119 109 0 60-48 100-108 100-56 0-99-37-109-85l-46 27c-8 5-15-1-11-10l16-32-16-34c-4-9 2-15 11-10Z"/></clipPath></defs><rect width="512" height="512" rx="120" fill="url(#dfs-frame-bg)"/><g clip-path="url(#dfs-frame-tile)"><path d="M0 397c112-38 172 27 278 6 82-17 144-33 234-9v118H0Z" fill="#AADCF0" opacity=".17"/><path d="M0 440c120-25 202 20 308 1 80-15 143-14 204 3v68H0Z" fill="#2C62BA" opacity=".17"/></g><g transform="translate(-8 8) translate(256 264) scale(1.2) translate(-256 -264) rotate(-14 256 264)"><path d="M182 244c1-50 43-84 98-84 70 0 119 49 119 109 0 60-48 100-108 100-56 0-99-37-109-85l-46 27c-8 5-15-1-11-10l16-32-16-34c-4-9 2-15 11-10Z" fill="url(#dfs-frame-body)"/><path d="M174 295c72 50 159 49 229-18l25 108-228 26Z" fill="#EAE3F1" opacity=".78" clip-path="url(#dfs-frame-fish)"/><path d="M212 216c18-20 45-31 72-30" fill="none" stroke="#FFF" stroke-opacity=".7" stroke-width="6" stroke-linecap="round"/><path d="M257 254c-22-3-36 7-30 19 5 11 22 5 30-7" fill="#C8BDEA"/><path d="M326 247q10 12 20 0" fill="none" stroke="#615579" stroke-width="7" stroke-linecap="round"/><ellipse cx="335" cy="275" rx="13" ry="8" fill="#EFB3C4"/><path d="M369 274q5 6 10 0" fill="none" stroke="#615579" stroke-width="4.5" stroke-linecap="round"/></g><g transform="translate(8 -14)"><path d="M392.57 148.66A25 25 0 1 1 403.65 113.5M395.1 108.59l8.55 4.91.02-9.86" fill="none" stroke="#FFE6AD" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M382 116c2 6 4 8 10 10-6 2-8 4-10 10-2-6-4-8-10-10 6-2 8-4 10-10Z" fill="#FFE6AD"/></g></svg>';
    const title = document.createElement('span');
    title.className = 'dfs-frame-title'; title.textContent = meta.name || '梦鱼更新管理';
    title.title = title.textContent;
    const badge = document.createElement('span');
    badge.className = 'dfs-frame-badge'; badge.textContent = meta.workspace ? '工作区' : '0.2.8';
    frame.append(mark, title, badge);
    const controls = document.createElement('div');
    controls.className = 'dfs-frame-controls';
    let maximize;
    let statusAttempts = 0;
    const statusRetryLimit = 50;
    const native = async (action) => {
      try {
        const state = await window.__TAURI_INTERNALS__.invoke('desktop_window_action', { action });
        if (maximize) {
          maximize.title = state.maximized ? '还原窗口' : '最大化';
          maximize.setAttribute('aria-label', maximize.title);
          maximize.classList.toggle('is-maximized', state.maximized);
          maximize.setAttribute('aria-pressed', String(state.maximized));
        }
        frame.removeAttribute('data-error');
        badge.textContent = meta.workspace ? '工作区' : '0.2.8'; badge.removeAttribute('title');
      } catch (error) {
        if (action === 'status' && statusAttempts++ < statusRetryLimit) { setTimeout(() => void native('status'), 200); return; }
        diagnostic(String(error));
        frame.dataset.error = 'true';
        badge.title = String(error);
        badge.textContent = '窗口操作暂不可用';
      }
    };
    const button = (action, label, path) => {
      const node = document.createElement('button');
      node.type = 'button'; node.className = `dfs-frame-button dfs-frame-${action}`;
      node.title = label; node.setAttribute('aria-label', label);
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 16 16'); svg.setAttribute('aria-hidden', 'true');
      const shape = document.createElementNS(svg.namespaceURI, 'path');
      shape.setAttribute('d', path); svg.append(shape); node.append(svg);
      node.addEventListener('click', () => void native(action));
      return node;
    };
    if (meta.workspace) {
      const chooser = button('connections', '切换连接', 'M2 3.5h10M9 1l3 2.5L9 6M14 12.5H4M7 10l-3 2.5L7 15');
      const label = document.createElement('span'); label.textContent = '连接'; chooser.append(label);
      frame.append(chooser);
    }
    const space = document.createElement('div'); space.className = 'dfs-frame-space'; frame.append(space);
    maximize = button('maximize', '最大化', 'M3 3h10v10H3z');
    controls.append(button('minimize', '最小化', 'M3 8h10'), maximize, button('close', '收到托盘', 'M3 3l10 10M13 3 3 13'));
    frame.append(controls);
    frame.addEventListener('mousedown', (event) => {
      if (event.button !== 0 || event.target.closest('button')) return;
      event.preventDefault();
      void native(event.detail === 2 ? 'maximize' : 'drag');
    });
    document.body.prepend(frame);
    // Keep window controls accessible while an HTML modal makes its siblings inert.
    const updateModal = () => {
      const dialogs = [...document.querySelectorAll('dialog[open]')].filter(d => d.matches(':modal'));
      const parent = dialogs.at(-1) || document.body;
      if (frame.parentNode !== parent) parent.prepend(frame);
    };
    new MutationObserver(updateModal).observe(document.body, { subtree: true, attributes: true, attributeFilter: ['open'] });
    let resizeTimer;
    window.addEventListener('resize', () => {
      clearTimeout(resizeTimer); resizeTimer = setTimeout(() => void native('status'), 100);
    });
    // WebView2 can deliver DOMContentLoaded before the IPC request origin is
    // committed. Status is read-only: wait for load when possible and keep a
    // bounded retry window, without changing any native origin permission.
    if (document.readyState === 'complete') void native('status');
    else window.addEventListener('load', () => void native('status'), { once:true });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install, { once: true });
  else install();
})();
