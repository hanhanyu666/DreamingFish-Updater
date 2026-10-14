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
    // The DreamingFish mark: a small white fish on the brand tile.
    mark.className = 'dfs-frame-mark'; mark.innerHTML = '<svg viewBox="0 0 32 32" aria-hidden="true"><path fill="#fff" d="M10.4 16 4.8 11.6c-.7-.6-1.8-.1-1.8.8v7.2c0 .9 1.1 1.4 1.8.8l5.6-4.4Z"/><path fill="#fff" fill-rule="evenodd" d="M9 16c2.8-4.6 6.6-7 10.6-7 4 0 7 2.6 8.4 7-1.4 4.4-4.4 7-8.4 7-4 0-7.8-2.4-10.6-7Zm14.7-1.6a1.5 1.5 0 1 0-3 0 1.5 1.5 0 0 0 3 0Z"/></svg>';
    const title = document.createElement('span');
    title.className = 'dfs-frame-title'; title.textContent = meta.name || '梦鱼更新管理';
    title.title = title.textContent;
    const badge = document.createElement('span');
    badge.className = 'dfs-frame-badge'; badge.textContent = meta.workspace ? '工作区' : '0.2.0';
    frame.append(mark, title, badge);
    const controls = document.createElement('div');
    controls.className = 'dfs-frame-controls';
    let maximize;
    let statusAttempts = 0;
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
        badge.textContent = meta.workspace ? '工作区' : '0.2.0'; badge.removeAttribute('title');
      } catch (error) {
        if (action === 'status' && statusAttempts++ < 6) { setTimeout(() => void native('status'), 200); return; }
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
    void native('status');
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install, { once: true });
  else install();
})();
