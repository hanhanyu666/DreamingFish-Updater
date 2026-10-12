#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod frame;
mod job;
mod profiles;
mod service;
mod workbench;

use profiles::{Config, Profile};
use service::{ConnectionResult, OwnedService};
use std::{
    fs,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Mutex,
    },
};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{
    Emitter, Manager, State, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_dialog::DialogExt;

struct Desktop {
    root: PathBuf,
    config: Mutex<Config>,
    active: Mutex<Option<Active>>,
    connecting: Mutex<bool>,
    generation: AtomicU64,
    exiting: AtomicBool,
    notice: Mutex<Option<String>>,
    background_check: bool,
    frame_checks: Mutex<(bool, bool)>,
    workbench_ready: AtomicBool,
    background_started: AtomicBool,
    disconnecting: AtomicBool,
}

struct Active {
    profile: Profile,
    service: OwnedService,
    origin: url::Origin,
    _workbench: workbench::Workbench,
}

struct ConnectingGuard<'a>(&'a Mutex<bool>);
impl Drop for ConnectingGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut busy) = self.0.lock() {
            *busy = false;
        }
    }
}

struct DisconnectGuard<'a>(&'a AtomicBool);
impl Drop for DisconnectGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    profiles: Vec<Profile>,
    default_home: String,
    logs: String,
    active: Option<String>,
    version: &'static str,
    notice: Option<String>,
}

fn launcher(window: &WebviewWindow) -> Result<(), String> {
    if window.label() != "main" {
        return Err("此窗口不能调用桌面管理命令".into());
    }
    let u = window.url().map_err(|e| e.to_string())?;
    if frame::bundled_launcher(window.label(), &u) {
        Ok(())
    } else {
        Err("连接选择页来源无效".into())
    }
}

#[tauri::command]
fn desktop_window_action(
    window: WebviewWindow,
    app: tauri::AppHandle,
    action: frame::Action,
) -> Result<frame::FrameState, String> {
    let state = app.state::<Desktop>();
    let url = window.url().map_err(|e| e.to_string())?;
    let origin = state
        .active
        .lock()
        .map_err(|e| e.to_string())?
        .as_ref()
        .map(|a| a.origin.clone());
    if !frame::allowed(window.label(), &url, origin.as_ref()) {
        return Err("此页面不能操作桌面窗口".into());
    }
    if matches!(action, frame::Action::Status) {
        let mut checked = state.frame_checks.lock().map_err(|e| e.to_string())?;
        if window.label() == "main" {
            checked.0 = true;
        } else {
            checked.1 = true;
        }
    }
    if matches!(action, frame::Action::Connections) {
        show_main(&app);
    }
    frame::operate(&window, action)
}

#[tauri::command]
fn load_desktop(window: WebviewWindow, state: State<Desktop>) -> Result<Snapshot, String> {
    launcher(&window)?;
    Ok(Snapshot {
        profiles: state
            .config
            .lock()
            .map_err(|e| e.to_string())?
            .profiles
            .clone(),
        default_home: state.root.join("management").to_string_lossy().into(),
        logs: state.root.join("logs").to_string_lossy().into(),
        active: state
            .active
            .lock()
            .map_err(|e| e.to_string())?
            .as_ref()
            .map(|a| a.profile.id.clone()),
        version: "0.2.0",
        notice: state.notice.lock().map_err(|e| e.to_string())?.clone(),
    })
}

#[tauri::command]
fn save_profile(
    window: WebviewWindow,
    state: State<Desktop>,
    mut profile: Profile,
) -> Result<Profile, String> {
    launcher(&window)?;
    profile.normalize()?;
    let mut config = state.config.lock().map_err(|e| e.to_string())?;
    if profile.id.is_empty() {
        profile.id = config
            .profiles
            .iter()
            .find(|p| p.connection == profile.connection)
            .map(|p| p.id.clone())
            .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    }
    profiles::valid_id(&profile.id)?;
    if let Some(active) = state.active.lock().map_err(|e| e.to_string())?.as_ref() {
        if active.profile.id == profile.id && active.profile.connection != profile.connection {
            return Err("请先断开工作区，再修改这个连接的目录或地址".into());
        }
    }
    let mut next = config.clone();
    if let Some(old) = next.profiles.iter_mut().find(|p| p.id == profile.id) {
        *old = profile.clone();
    } else {
        if next.profiles.len() >= 30 {
            return Err("连接记录最多保存 30 项".into());
        }
        next.profiles.push(profile.clone());
    }
    next.save(&state.root)?;
    *config = next;
    Ok(profile)
}

#[tauri::command]
fn delete_profile(window: WebviewWindow, state: State<Desktop>, id: String) -> Result<(), String> {
    launcher(&window)?;
    if state
        .active
        .lock()
        .map_err(|e| e.to_string())?
        .as_ref()
        .is_some_and(|a| a.profile.id == id)
    {
        return Err("请先断开工作区，再移除这个连接记录".into());
    }
    let mut config = state.config.lock().map_err(|e| e.to_string())?;
    let mut next = config.clone();
    next.profiles.retain(|p| p.id != id);
    next.save(&state.root)?;
    *config = next;
    Ok(())
}

#[tauri::command]
async fn choose_home(
    window: WebviewWindow,
    app: tauri::AppHandle,
) -> Result<Option<String>, String> {
    launcher(&window)?;
    let selected = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title("选择管理端数据所在目录")
            .blocking_pick_folder()
    })
    .await
    .map_err(|e| e.to_string())?;
    selected
        .map(|p| {
            p.into_path()
                .map(|v| v.to_string_lossy().into())
                .map_err(|e| e.to_string())
        })
        .transpose()
}

fn show_main(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn show_notice(app: &tauri::AppHandle, message: String) {
    if let Ok(mut notice) = app.state::<Desktop>().notice.lock() {
        *notice = Some(message);
    }
    show_main(app);
    let _ = app.emit_to("main", "workspace-ended", ());
}

fn show_current(app: &tauri::AppHandle) -> Result<(), String> {
    let w = app
        .get_webview_window("workspace")
        .ok_or("还没有打开工作区")?;
    w.show().map_err(|e| e.to_string())?;
    w.unminimize().map_err(|e| e.to_string())?;
    w.set_focus().map_err(|e| e.to_string())
}

#[tauri::command]
fn show_workspace(window: WebviewWindow, app: tauri::AppHandle) -> Result<(), String> {
    launcher(&window)?;
    show_current(&app)
}

#[tauri::command]
fn cancel_connect(window: WebviewWindow, state: State<Desktop>) -> Result<(), String> {
    launcher(&window)?;
    state.generation.fetch_add(1, Ordering::SeqCst);
    Ok(())
}

#[tauri::command]
async fn connect_profile(
    window: WebviewWindow,
    app: tauri::AppHandle,
    id: String,
) -> Result<(), String> {
    launcher(&window)?;
    let state = app.state::<Desktop>();
    if state.disconnecting.load(Ordering::SeqCst) {
        return Err("正在断开工作区，请稍候".into());
    }
    {
        let active = state.active.lock().map_err(|e| e.to_string())?;
        if let Some(a) = active.as_ref() {
            if a.profile.id == id {
                return show_current(&app);
            }
            return Err("已有工作区在后台运行，请先断开再切换连接".into());
        }
    }
    let profile = state
        .config
        .lock()
        .map_err(|e| e.to_string())?
        .profiles
        .iter()
        .find(|p| p.id == id)
        .cloned()
        .ok_or("连接记录不存在")?;
    {
        let mut busy = state.connecting.lock().map_err(|e| e.to_string())?;
        if *busy {
            return Err("正在打开工作区，请稍候".into());
        }
        *busy = true;
    }
    let _connecting = ConnectingGuard(&state.connecting);
    let generation = state.generation.fetch_add(1, Ordering::SeqCst) + 1;
    let app_worker = app.clone();
    let profile_worker = profile.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let s = app_worker.state::<Desktop>();
        service::connect(&app_worker, &s.root, &profile_worker, || {
            s.generation.load(Ordering::SeqCst) != generation
        })
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|v| v);
    let ConnectionResult { url, service } = result?;
    if state.generation.load(Ordering::SeqCst) != generation {
        drop(service);
        return Err("已取消连接".into());
    }
    let proxy_app = app.clone();
    let workbench = tauri::async_runtime::spawn_blocking(move || {
        let assets = std::sync::Arc::new(move |key: &str| proxy_app.asset_resolver().get(key.to_string()).map(|asset| (asset.bytes, asset.mime_type)));
        workbench::Workbench::start(url, assets)
    }).await.map_err(|e| e.to_string())??;
    if state.generation.load(Ordering::SeqCst) != generation {
        drop(workbench);drop(service);return Err("已取消连接".into());
    }
    state.workbench_ready.store(false, Ordering::SeqCst);
    let url = workbench.url.join("workspace.html").map_err(|e| e.to_string())?;
    let origin = url.origin();
    let active_origin = origin.clone();
    let cookies = state.root.join("webviews").join(&profile.id);
    fs::create_dir_all(&cookies).map_err(|e| e.to_string())?;
    let external_app = app.clone();
    let workspace = WebviewWindowBuilder::new(&app, "workspace", WebviewUrl::External(url.clone()))
        .title(format!("{} · 梦鱼更新管理", profile.name))
        .inner_size(1280.0, 800.0)
        .min_inner_size(960.0, 600.0)
        .center()
        .visible(!state.background_check)
        .decorations(false)
        .data_directory(cookies)
        .initialization_script(format!("window.__DFS_DESKTOP_TOKEN__ = {};\n{}",serde_json::to_string(&workbench.token).map_err(|e| e.to_string())?, desktop_chrome_script(&profile, state.background_check)))
        .on_document_title_changed(|window, title| {
            let state = window.app_handle().state::<Desktop>();
            if state.background_check && title == "DFS_WORKBENCH_READY" {
                state.workbench_ready.store(true,Ordering::SeqCst);
            }
            if state.background_check && title.starts_with("DFS_FRAME_ERROR:") {
                if let Ok(mut notice) = state.notice.lock() {
                    *notice = Some(title);
                }
            }
        })
        .disable_drag_drop_handler()
        .on_navigation(move |next| next.origin() == origin)
        .on_new_window(move |next, _| {
            if matches!(next.scheme(), "http" | "https") {
                let _ = open::that_detached(next.as_str());
            }
            tauri::webview::NewWindowResponse::Deny
        })
        .build()
        .map_err(|e| format!("无法打开管理工作区：{e}"))?;
    let workspace_handle = workspace.clone();
    workspace.on_window_event(move |event| {
        if let WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();
            let _ = workspace_handle.hide();
        }
    });
    *state.active.lock().map_err(|e| e.to_string())? = Some(Active {
        profile,
        service,
        origin: active_origin,
        _workbench: workbench,
    });
    *state.notice.lock().map_err(|e| e.to_string())? = None;
    // Keep the connection chooser available from the tray while the workspace is open.
    if let Some(main) = external_app.get_webview_window("main") {
        let _ = main.hide();
    }
    Ok(())
}

fn desktop_chrome_script(profile: &Profile, background_check: bool) -> String {
    let meta = serde_json::json!({
        "workspace": true,
        "name": profile.name,
        "check": background_check,
        "css": include_str!("../../src/desktop-chrome.css"),
    });
    format!(
        "window.__DFS_DESKTOP_META__ = {};\n{}",
        meta,
        include_str!("../../src/desktop-chrome.js")
    )
}

fn disconnect(app: &tauri::AppHandle) -> Result<(), String> {
    let state = app.state::<Desktop>();
    if state.disconnecting.swap(true, Ordering::SeqCst) {
        return Err("正在断开工作区，请稍候".into());
    }
    let _disconnecting = DisconnectGuard(&state.disconnecting);
    state.generation.fetch_add(1, Ordering::SeqCst);
    if *state.connecting.lock().map_err(|e| e.to_string())? {
        return Err("正在取消连接，请稍候再退出".into());
    }
    let active = state.active.lock().map_err(|e| e.to_string())?.take();
    if let Some(mut active) = active {
        if let Err(error) = active.service.stop() {
            *state.active.lock().map_err(|e| e.to_string())? = Some(active);
            return Err(error);
        }
    }
    if let Some(w) = app.get_webview_window("workspace") {
        w.destroy().map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
async fn disconnect_workspace(window: WebviewWindow, app: tauri::AppHandle) -> Result<(), String> {
    launcher(&window)?;
    let clone = app.clone();
    tauri::async_runtime::spawn_blocking(move || disconnect(&clone))
        .await
        .map_err(|e| e.to_string())??;
    show_main(&app);
    Ok(())
}

#[tauri::command]
fn open_logs(window: WebviewWindow, state: State<Desktop>) -> Result<(), String> {
    launcher(&window)?;
    open::that(state.root.join("logs")).map_err(|e| e.to_string())
}

#[tauri::command]
async fn quit_application(window: WebviewWindow, app: tauri::AppHandle) -> Result<(), String> {
    launcher(&window)?;
    let clone = app.clone();
    tauri::async_runtime::spawn_blocking(move || disconnect(&clone))
        .await
        .map_err(|e| e.to_string())??;
    app.exit(0);
    Ok(())
}

fn profile_root(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--background-check") && !args.iter().any(|a| a == "--desktop-home")
    {
        return Err("后台验收必须使用独立的 --desktop-home 目录".into());
    }
    if let Some(index) = args.iter().position(|a| a == "--desktop-home") {
        let path = PathBuf::from(args.get(index + 1).ok_or("--desktop-home 缺少目录")?);
        if !path.is_absolute() {
            return Err("桌面数据目录必须是绝对路径".into());
        }
        return Ok(path);
    }
    app.path().app_local_data_dir().map_err(|e| e.to_string())
}

// Exercise the packaged webviews and owned process without showing or focusing a window.
fn run_background_check(app: tauri::AppHandle) {
    let state = app.state::<Desktop>();
    if state.background_started.swap(true, Ordering::SeqCst) {
        return;
    }
    tauri::async_runtime::spawn(async move {
        let check = async {
            let state = app.state::<Desktop>();
            let main = app.get_webview_window("main").ok_or("连接窗口缺失")?;
            let snapshot = load_desktop(main.clone(), app.state::<Desktop>())?;
            let args: Vec<String> = std::env::args().collect();
            let requested = args.iter().position(|a| a == "--background-profile").and_then(|i| args.get(i + 1));
            let profile = if let Some(id) = requested {
                snapshot.profiles.into_iter().find(|p| p.id == *id).ok_or("验收连接记录缺失")?
            } else { save_profile(main.clone(), app.state::<Desktop>(), Profile {
                id: String::new(), name: "本机管理".into(),
                connection: profiles::Connection::Local { home: snapshot.default_home },
            })? };
            let owned = !matches!(profile.connection, profiles::Connection::Remote { .. });
            connect_profile(main, app.clone(), profile.id).await?;
            let checker = app.clone();
            let frames = tauri::async_runtime::spawn_blocking(move || {
                let state = checker.state::<Desktop>();
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
                loop {
                    let frames = *state.frame_checks.lock().map_err(|e| e.to_string())?;
                    if frames.0 && frames.1 && state.workbench_ready.load(Ordering::SeqCst) { return Ok(frames); }
                    if std::time::Instant::now() >= deadline { return Err(format!("窗口控件 IPC 未就绪：{frames:?} {:?}", state.notice.lock().map_err(|e| e.to_string())?.clone())); }
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
            }).await.map_err(|e| e.to_string())??;
            let workspace = app.get_webview_window("workspace").ok_or("管理工作区缺失")?;
            let workspace_url = workspace.url().map_err(|e| e.to_string())?;
            if load_desktop(workspace.clone(), app.state::<Desktop>()).is_ok() { return Err("工作区错误地获得了管理原生命令".into()); }
            if main_window_visible(&app)? || workspace.is_visible().map_err(|e| e.to_string())? { return Err("后台检查不应显示窗口".into()); }
            let worker = app.clone();
            tauri::async_runtime::spawn_blocking(move || disconnect(&worker)).await.map_err(|e| e.to_string())??;
            if owned {
                let port = workspace_url.port().ok_or("本机端口缺失")?;
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
                while std::net::TcpStream::connect_timeout(&format!("127.0.0.1:{port}").parse().unwrap(), std::time::Duration::from_millis(200)).is_ok() {
                    if std::time::Instant::now() >= deadline { return Err("退出后工作区端口仍在运行".into()); }
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
            }
            Ok::<_, String>(serde_json::json!({"version":"0.2.0", "success":true,
                "bundledLauncherIpc":frames.0, "workspaceFrameIpc":frames.1,
                "desktopFrontendReady":true,
                "workspaceManagementCommandsDenied":true, "windowsKeptHidden":true,
                "ownedLoopbackClosed":owned, "workspaceUrl":workspace_url.as_str(), "profiles":state.config.lock().map_err(|e| e.to_string())?.profiles.len()}))
        }.await;
        let (report, exit_code) = match check {
            Ok(report) => (report, 0),
            Err(error) => {
                let clone = app.clone();
                let _ = tauri::async_runtime::spawn_blocking(move || disconnect(&clone)).await;
                (serde_json::json!({"success":false,"error":error}), 1)
            }
        };
        let state = app.state::<Desktop>();
        let write = serde_json::to_vec_pretty(&report)
            .map_err(|e| e.to_string())
            .and_then(|bytes| {
                fs::write(state.root.join("background-check.json"), bytes)
                    .map_err(|e| e.to_string())
            });
        app.exit(if write.is_ok() { exit_code } else { 1 });
    });
}

fn main_window_visible(app: &tauri::AppHandle) -> Result<bool, String> {
    app.get_webview_window("main")
        .ok_or("连接窗口缺失")?
        .is_visible()
        .map_err(|e| e.to_string())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _| {
            if !args.iter().any(|a| a == "--background-check") {
                show_main(app)
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            load_desktop,
            save_profile,
            delete_profile,
            connect_profile,
            cancel_connect,
            disconnect_workspace,
            show_workspace,
            choose_home,
            open_logs,
            quit_application,
            desktop_window_action
        ])
        .setup(|app| {
            let root = profile_root(app.handle()).map_err(std::io::Error::other)?;
            fs::create_dir_all(root.join("logs"))?;
            let config = Config::load(&root).map_err(std::io::Error::other)?;
            app.manage(Desktop {
                root,
                config: Mutex::new(config),
                active: Mutex::new(None),
                connecting: Mutex::new(false),
                generation: AtomicU64::new(0),
                exiting: AtomicBool::new(false),
                notice: Mutex::new(None),
                background_check: std::env::args().any(|a| a == "--background-check"),
                frame_checks: Mutex::new((false, false)),
                workbench_ready: AtomicBool::new(false),
                background_started: AtomicBool::new(false),
                disconnecting: AtomicBool::new(false),
            });
            let monitor = app.handle().clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(std::time::Duration::from_millis(750));
                let state = monitor.state::<Desktop>();
                if state.exiting.load(Ordering::SeqCst) {
                    break;
                }
                let ended = if let Ok(mut active) = state.active.lock() {
                    let ended = active
                        .as_mut()
                        .is_some_and(|a| a.service.ended().unwrap_or(true));
                    if ended {
                        active.take()
                    } else {
                        None
                    }
                } else {
                    None
                };
                if let Some(active) = ended {
                    let message = format!(
                        "{}的连接已中断，可以重新打开。详细信息见运行日志。",
                        active.profile.name
                    );
                    if let Ok(mut notice) = state.notice.lock() {
                        *notice = Some(message);
                    }
                    drop(active);
                    if let Some(workspace) = monitor.get_webview_window("workspace") {
                        let _ = workspace.destroy();
                    }
                    if !state.background_check {
                        show_main(&monitor);
                    }
                    let _ = monitor.emit_to("main", "workspace-ended", ());
                }
            });
            let chooser = MenuItem::with_id(app, "chooser", "选择管理端", true, None::<&str>)?;
            let current = MenuItem::with_id(app, "current", "返回工作区", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出桌面端", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&current, &chooser, &quit])?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().ok_or("缺少程序图标")?.clone())
                .tooltip("梦鱼更新管理")
                .menu(&menu)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "chooser" => show_main(app),
                    "current" => {
                        if show_current(app).is_err() {
                            show_main(app);
                        }
                    }
                    "quit" => {
                        let app = app.clone();
                        tauri::async_runtime::spawn(async move {
                            let clone = app.clone();
                            match tauri::async_runtime::spawn_blocking(move || disconnect(&clone))
                                .await
                            {
                                Ok(Ok(())) => app.exit(0),
                                Ok(Err(error)) => show_notice(&app, error),
                                Err(error) => show_notice(&app, format!("退出失败：{error}")),
                            }
                        });
                    }
                    _ => {}
                })
                .build(app)?;
            let main = app.get_webview_window("main").ok_or("缺少连接选择窗口")?;
            let clone = main.clone();
            main.on_window_event(move |event| {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = clone.hide();
                }
            });
            if !app.state::<Desktop>().background_check {
                main.show()?;
            }
            Ok(())
        })
        .on_page_load(|webview, payload| {
            if webview.label() == "main"
                && matches!(payload.event(), tauri::webview::PageLoadEvent::Finished)
                && webview.app_handle().state::<Desktop>().background_check
            {
                run_background_check(webview.app_handle().clone());
            }
        })
        .build(tauri::generate_context!())
        .expect("启动梦鱼管理桌面端失败")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                let state = app.state::<Desktop>();
                state.exiting.store(true, Ordering::SeqCst);
                if let Ok(mut active) = state.active.lock() {
                    *active = None;
                };
            }
        });
}
