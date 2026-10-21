#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod frame;
mod downloads;
mod job;
mod migrate;
mod profiles;
mod recovery;
mod service;
mod workbench;

use profiles::{Config, Profile};
use service::{ConnectionResult, OwnedService};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering},
        Mutex,
    },
    time::Duration,
};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{
    Emitter, Manager, State, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_updater::UpdaterExt;

const RELEASES: &str = "https://github.com/hanhanyu666/DreamingFish-Updater/releases";

struct Desktop {
    root: PathBuf,
    installed: bool,
    beside_program: bool,
    update: Mutex<Option<tauri_plugin_updater::Update>>,
    updating: AtomicBool,
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
    rebuilds: Mutex<recovery::Budget>,
    downloads: downloads::Downloads,
}

struct Active {
    profile: Profile,
    service: OwnedService,
    origin: url::Origin,
    workspace_url: url::Url,
    workbench: workbench::Workbench,
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
    connection_resources: Option<workbench::ConnectionMetrics>,
    default_home: String,
    logs: String,
    data_root: String,
    installed: bool,
    data_beside_program: bool,
    portable_notice: bool,
    overlay_notice: bool,
    program_name: String,
    active: Option<String>,
    version: &'static str,
    notice: Option<String>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateInfo {
    version: String,
    notes: String,
    installable: bool,
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

fn download_workspace(window: &WebviewWindow, state: &Desktop) -> Result<(url::Url, String, u64), String> {
    let url = window.url().map_err(|e| e.to_string())?;
    let active = state.active.lock().map_err(|e| e.to_string())?;
    let active = active.as_ref().ok_or("还没有打开管理工作区")?;
    if window.label() != "workspace" || url.origin() != active.origin {
        return Err("此页面不能保存部署包".into());
    }
    Ok((active.workbench.url.clone(), active.workbench.token.clone(), state.generation.load(Ordering::SeqCst)))
}

#[tauri::command]
async fn save_deployment_download(
    window: WebviewWindow,
    app: tauri::AppHandle,
    request: downloads::Request,
    session_token: String,
) -> Result<Option<downloads::Saved>, String> {
    let state = app.state::<Desktop>();
    let (base, desktop_token, generation) = download_workspace(&window, &state)?;
    request.validate()?;
    let _busy = state.downloads.begin()?;
    let picker_app = app.clone();
    let suggested_name = request.suggested_name();
    let selected = tauri::async_runtime::spawn_blocking(move || {
        picker_app.dialog().file().set_parent(&window)
            .set_title("保存玩家部署包").add_filter("ZIP 部署包", &["zip"])
            .set_file_name(suggested_name).blocking_save_file()
    }).await.map_err(|e| e.to_string())?;
    let Some(selected) = selected else { return Ok(None); };
    let destination = selected.into_path().map_err(|e| e.to_string())?;
    let worker_app = app.clone();
    let saved = tauri::async_runtime::spawn_blocking(move || {
        let state = worker_app.state::<Desktop>();
        let current = || state.generation.load(Ordering::SeqCst) == generation
            && !state.exiting.load(Ordering::SeqCst) && !state.disconnecting.load(Ordering::SeqCst);
        let size = downloads::save(&base, &desktop_token, &session_token, &request, &destination, current)?;
        state.downloads.remember(destination, size)
    }).await.map_err(|e| e.to_string())??;
    Ok(Some(saved))
}

#[tauri::command]
fn reveal_deployment_download(window: WebviewWindow, app: tauri::AppHandle, receipt_id: String) -> Result<(), String> {
    let state = app.state::<Desktop>();
    download_workspace(&window, &state)?;
    let folder = state.downloads.parent_for(&receipt_id)?;
    open::that_detached(folder).map_err(|e| format!("无法打开部署包所在文件夹：{e}"))
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
    // Closing the workspace hides it to the tray; show the connection page so the app is still in view.
    let closing_workspace = matches!(action, frame::Action::Close) && window.label() == "workspace";
    let result = frame::operate(&window, action)?;
    if closing_workspace {
        show_main(&app);
    }
    Ok(result)
}

#[tauri::command]
fn load_desktop(window: WebviewWindow, state: State<Desktop>) -> Result<Snapshot, String> {
    launcher(&window)?;
    desktop_snapshot(&state)
}

fn desktop_snapshot(state:&Desktop)->Result<Snapshot,String> {
    let (connection_resources,active)={
        let current=state.active.lock().map_err(|e|e.to_string())?;
        (current.as_ref().map(|active|active.workbench.statistics()),current.as_ref().map(|active|active.profile.id.clone()))
    };
    Ok(Snapshot {
        connection_resources,
        profiles: state
            .config
            .lock()
            .map_err(|e| e.to_string())?
            .profiles
            .clone(),
        default_home: state.root.join("management").to_string_lossy().into(),
        logs: state.root.join("logs").to_string_lossy().into(),
        data_root: state.root.to_string_lossy().into(),
        installed: state.installed,
        data_beside_program: state.beside_program,
        portable_notice: !state.installed
            && state.beside_program
            && !state.root.join(PORTABLE_NOTICE_READ).exists(),
        overlay_notice: overlay_injected() && !state.root.join(OVERLAY_NOTICE_READ).exists(),
        program_name: std::env::current_exe()
            .ok()
            .and_then(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()))
            .unwrap_or_default(),
        active,
        version: "0.2.8",
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
    if !profile.confirm_relocation && state.config.lock().map_err(|e| e.to_string())?.profiles.iter().any(|old| old.id == profile.id && old.relocation_required) {
        return Err("旧连接目录身份不明确，请通过选择目录按钮重新定位后再连接。".into());
    }
    if let profiles::Connection::Local { home } = &profile.connection {
        let resolved = profiles::resolve_home(&state.root, home)?;
        service::require_management_home(&resolved)?;
        fs::create_dir_all(&resolved).map_err(|e| format!("无法准备选定的管理目录：{e}"))?;
    }
    profile.relocation_required = false;
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

fn show_main_existing(app:&tauri::AppHandle) {
    if let Some(window)=app.get_webview_window("main") {let _=window.show();let _=window.unminimize();let _=window.set_focus();}
}
fn show_main(app:&tauri::AppHandle) {
    let state=app.state::<Desktop>();
    if state.exiting.load(Ordering::SeqCst)||state.updating.load(Ordering::SeqCst){return;}
    if app.get_webview_window("main").is_none(){
        if let Ok(mut budget)=state.rebuilds.lock(){budget.manual_reset("main");}
        if let Err(error)=build_main(app,&state.root){append_desktop_log(&state.root,&format!("手动重建连接页失败：{error}"));return;}
    }
    show_main_existing(app);
}

fn show_notice(app: &tauri::AppHandle, message: String) {
    if let Ok(mut notice) = app.state::<Desktop>().notice.lock() {
        *notice = Some(message);
    }
    show_main_existing(app);
    let _ = app.emit_to("main", "workspace-ended", ());
}

fn recover_current(app:&tauri::AppHandle,visible:bool)->Result<(),String> {
    let state=app.state::<Desktop>();
    if state.exiting.load(Ordering::SeqCst)||state.disconnecting.load(Ordering::SeqCst)||state.updating.load(Ordering::SeqCst){return Err("当前连接正在关闭或更新".into());}
    if app.get_webview_window("workspace").is_none(){
        let (profile,url,token)=state.active.lock().map_err(|e|e.to_string())?.as_ref().map(|active|(active.profile.clone(),active.workspace_url.clone(),active.workbench.token.clone())).ok_or("还没有打开工作区")?;
        state.rebuilds.lock().map_err(|e|e.to_string())?.manual_reset("workspace");
        state.workbench_ready.store(false,Ordering::SeqCst);
        build_workspace(app,&profile,&url,&token,false)?;
        append_desktop_log(&state.root,"用户已手动重建工作区窗口，所属连接仍然有效");
    }
    if visible {let window=app.get_webview_window("workspace").ok_or("工作区重建后仍缺失")?;window.show().map_err(|e|e.to_string())?;window.unminimize().map_err(|e|e.to_string())?;window.set_focus().map_err(|e|e.to_string())?;}
    Ok(())
}
fn show_current(app:&tauri::AppHandle)->Result<(),String>{recover_current(app,true)}

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
    let active_origin = url.origin();
    let external_app = app.clone();
    build_workspace(&app, &profile, &url, &workbench.token, !state.background_check)?;
    *state.active.lock().map_err(|e| e.to_string())? = Some(Active {
        profile,
        service,
        origin: active_origin,
        workspace_url: url,
        workbench,
    });
    *state.notice.lock().map_err(|e| e.to_string())? = None;
    // Keep the connection chooser available from the tray while the workspace is open.
    if let Some(main) = external_app.get_webview_window("main") {
        let _ = main.hide();
    }
    Ok(())
}

fn build_main(app: &tauri::AppHandle, root: &Path) -> tauri::Result<WebviewWindow> {
    let main = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
        .title("梦鱼更新管理 · 工作区")
        .inner_size(1000.0, 760.0)
        .min_inner_size(820.0, 620.0)
        .center()
        .decorations(false)
        .visible(false)
        .data_directory(root.join("webview"))
        .build()?;
    frame::round_corners(&main);
    let clone = main.clone();
    main.on_window_event(move |event| {
        if let WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();
            let _ = clone.hide();
        }
    });
    watch_webview(&main);
    Ok(main)
}

fn build_workspace(
    app: &tauri::AppHandle,
    profile: &Profile,
    url: &url::Url,
    token: &str,
    visible: bool,
) -> Result<WebviewWindow, String> {
    let state = app.state::<Desktop>();
    let origin = url.origin();
    let cookies = state.root.join("webviews").join(&profile.id);
    fs::create_dir_all(&cookies).map_err(|e| e.to_string())?;
    let workspace = WebviewWindowBuilder::new(app, "workspace", WebviewUrl::External(url.clone()))
        .title(format!("{} · 梦鱼更新管理", profile.name))
        .inner_size(1280.0, 800.0)
        .min_inner_size(960.0, 600.0)
        .center()
        .visible(visible)
        .decorations(false)
        .data_directory(cookies)
        .initialization_script(format!("window.__DFS_DESKTOP_TOKEN__ = {};\n{}",serde_json::to_string(token).map_err(|e| e.to_string())?, desktop_chrome_script(profile, state.background_check)))
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
    frame::round_corners(&workspace);
    let handle = workspace.clone();
    workspace.on_window_event(move |event| {
        if let WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();
            let _ = handle.hide();
            show_main(handle.app_handle());
        }
    });
    watch_webview(&workspace);
    Ok(workspace)
}

/// Overlays such as RivaTuner (RTSS) inject into WebView2 and can crash it, which leaves a black
/// window. A crashed page is reloaded; a window whose browser process is gone is rebuilt.
#[cfg(windows)]
fn watch_webview(window: &WebviewWindow) {
    let app = window.app_handle().clone();
    let label = window.label().to_string();
    let epoch=match app.state::<Desktop>().rebuilds.lock(){Ok(mut budget)=>budget.attach(&label),Err(_)=>return};
    let setup_root=app.state::<Desktop>().root.clone();let setup_label=label.clone();
    let attached = window.with_webview(move |webview| unsafe {
        use webview2_com::{Microsoft::Web::WebView2::Win32::*, ProcessFailedEventHandler};
        let core=match webview.controller().CoreWebView2(){Ok(core)=>core,Err(error)=>{append_desktop_log(&app.state::<Desktop>().root,&format!("{label} 故障监听初始化失败：{error}"));return;}};
        let registration_app=app.clone();let registration_label=label.clone();
        let handler = ProcessFailedEventHandler::create(Box::new(move |_sender, args| {
            if !app.state::<Desktop>().rebuilds.lock().map(|budget|budget.current(&label,epoch)).unwrap_or(false){return Ok(());}
            let mut kind = COREWEBVIEW2_PROCESS_FAILED_KIND::default();
            if let Some(args) = args {
                args.ProcessFailedKind(&mut kind)?;
            }
            let reason=if kind==COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED {Some("browser-process-exited")}
                else if kind==COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED {Some("render-process-exited")}
                else if kind==COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE {Some("render-process-unresponsive")}else{None};
            if let Some(reason)=reason {let app=app.clone();let label=label.clone();std::thread::spawn(move||rebuild_window(&app,&label,reason));}
            Ok(())
        }));
        let mut token = 0i64;
        match core.add_ProcessFailed(&handler,&mut token){Ok(())=>append_desktop_log(&registration_app.state::<Desktop>().root,&format!("{registration_label} 已注册统一 WebView 故障监听")),Err(error)=>append_desktop_log(&registration_app.state::<Desktop>().root,&format!("{registration_label} 无法注册故障监听：{error}"))}
    });
    if let Err(error)=attached{append_desktop_log(&setup_root,&format!("{setup_label} 绑定 WebView 故障监听失败：{error}"));}
}

#[cfg(not(windows))]
fn watch_webview(_window: &WebviewWindow) {}

/// Replaces a window whose WebView2 browser process ended, keeping it hidden if it was hidden.
fn rebuild_window(app:&tauri::AppHandle,label:&str,reason:&str) {
    let state=app.state::<Desktop>();let generation=state.generation.load(Ordering::SeqCst);
    let closing=||state.exiting.load(Ordering::SeqCst)||state.updating.load(Ordering::SeqCst)||state.disconnecting.load(Ordering::SeqCst)
        ||state.connecting.lock().map(|busy|*busy).unwrap_or(true)||state.generation.load(Ordering::SeqCst)!=generation;
    if closing(){append_desktop_log(&state.root,&format!("忽略 {label} 的迟到故障 {reason}：会话正在切换"));return;}
    let decision=match state.rebuilds.lock(){Ok(mut budget)=>budget.schedule(label,std::time::Instant::now()),Err(_)=>return};
    let (attempt,delay)=match decision {recovery::Decision::AlreadyPending=>{append_desktop_log(&state.root,&format!("合并 {label} 故障 {reason}：已有恢复任务"));return;},recovery::Decision::Exhausted=>(0,Duration::ZERO),recovery::Decision::Retry{attempt,delay}=>(attempt,delay)};
    append_desktop_log(&state.root,&format!("窗口故障 label={label} reason={reason} attempt={attempt} decision={decision:?}"));
    let finish=||{if let Ok(mut budget)=state.rebuilds.lock(){budget.finish(label);}};
    let Some(old)=app.get_webview_window(label)else{finish();return;};let visible=old.is_visible().unwrap_or(false);
    std::thread::sleep(delay);if closing(){finish();return;}
    if let Ok(mut checks)=state.frame_checks.lock(){if label=="main"{checks.0=false;}else{checks.1=false;state.workbench_ready.store(false,Ordering::SeqCst);}}
    if let Err(error)=old.destroy(){append_desktop_log(&state.root,&format!("销毁 {label} 失败：{error}"));finish();return;}
    for _ in 0..50 {if app.get_webview_window(label).is_none(){break;}std::thread::sleep(Duration::from_millis(100));}
    if attempt==0 {
        *state.notice.lock().unwrap()=Some(format!("{label} 窗口一分钟内故障达到上限，自动恢复已暂停。可使用托盘的连接页 / 返回工作区手动重建。"));
        append_desktop_log(&state.root,&format!("{label} 自动恢复暂停，保留手动入口与连接状态"));finish();return;
    }
    if closing(){finish();return;}
    let rebuilt=if label=="main"{build_main(app,&state.root).map_err(|e|e.to_string())}else{
        let active=state.active.lock().ok().and_then(|active|active.as_ref().map(|a|(a.profile.clone(),a.workspace_url.clone(),a.workbench.token.clone())));
        match active {Some((profile,url,token))=>build_workspace(app,&profile,&url,&token,false),None=>{finish();return;}}
    };
    match rebuilt {
        Ok(window)=>{if closing(){let _=window.destroy();}else if visible&&!state.background_check{let _=window.show();let _=window.set_focus();}append_desktop_log(&state.root,&format!("{label} 第 {attempt} 次恢复完成，visible={visible}"));}
        Err(error)=>{append_desktop_log(&state.root,&format!("{label} 第 {attempt} 次恢复失败：{error}"));*state.notice.lock().unwrap()=Some(error);}
    }
    finish();
}

fn append_desktop_log(root: &Path, line: &str) {
    use std::io::Write;
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    if let Ok(mut file) = fs::OpenOptions::new().create(true).append(true).open(root.join("logs").join("desktop.log")) {
        let _ = writeln!(file, "[{seconds}] {line}");
    }
}

/// RivaTuner Statistics Server injects its frame counter into every process that draws.
#[cfg(windows)]
fn overlay_injected() -> bool {
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    ["RTSSHooks64.dll", "RTSSHooks.dll"].iter().any(|name| {
        let wide: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
        unsafe { !GetModuleHandleW(wide.as_ptr()).is_null() }
    })
}

#[cfg(not(windows))]
fn overlay_injected() -> bool {
    false
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
fn open_data_dir(window: WebviewWindow, state: State<Desktop>) -> Result<(), String> {
    launcher(&window)?;
    open::that(&state.root).map_err(|e| e.to_string())
}

#[tauri::command]
fn dismiss_notice(window: WebviewWindow, state: State<Desktop>, name: String) -> Result<(), String> {
    launcher(&window)?;
    let marker = match name.as_str() {
        "portable" => PORTABLE_NOTICE_READ,
        "overlay" => OVERLAY_NOTICE_READ,
        _ => return Err("未知的提示".into()),
    };
    fs::write(state.root.join(marker), b"").map_err(|e| e.to_string())
}

#[tauri::command]
async fn check_update(
    window: WebviewWindow,
    app: tauri::AppHandle,
) -> Result<Option<UpdateInfo>, String> {
    launcher(&window)?;
    let state = app.state::<Desktop>();
    if state.background_check {
        return Ok(None);
    }
    let exiting = app.clone();
    let update = app
        .updater_builder()
        .timeout(Duration::from_secs(30))
        // The installer replaces the bundled core; the owned service is already stopped by then.
        .on_before_exit(move || {
            exiting.state::<Desktop>().exiting.store(true, Ordering::SeqCst);
            let _ = exiting.remove_tray_by_id("main");
        })
        .build()
        .map_err(|e| e.to_string())?
        .check()
        .await
        .map_err(|e| format!("检查更新失败：{e}"))?;
    let info = update.as_ref().map(|u| UpdateInfo {
        version: u.version.clone(),
        notes: u.body.clone().unwrap_or_default(),
        installable: state.installed,
    });
    *state.update.lock().map_err(|e| e.to_string())? = update;
    Ok(info)
}

#[tauri::command]
async fn install_update(window: WebviewWindow, app: tauri::AppHandle) -> Result<(), String> {
    launcher(&window)?;
    let state = app.state::<Desktop>();
    if !state.installed {
        return Err("便携版请下载新的压缩包，替换程序文件即可".into());
    }
    let update = state
        .update
        .lock()
        .map_err(|e| e.to_string())?
        .clone()
        .ok_or("请先检查更新")?;
    if state.updating.swap(true, Ordering::SeqCst) {
        return Err("正在更新，请稍候".into());
    }
    let _updating = DisconnectGuard(&state.updating);
    let clone = app.clone();
    tauri::async_runtime::spawn_blocking(move || disconnect(&clone))
        .await
        .map_err(|e| e.to_string())??;
    let progress = app.clone();
    let mut received = 0u64;
    // On Windows the installer starts here and this process exits; it reopens after installing.
    update
        .download_and_install(
            move |chunk, total| {
                received += chunk as u64;
                let _ = progress.emit_to(
                    "main",
                    "update-progress",
                    serde_json::json!({"received": received, "total": total}),
                );
            },
            || {},
        )
        .await
        .map_err(|e| format!("更新失败：{e}"))
}

#[tauri::command]
fn open_release_page(window: WebviewWindow, state: State<Desktop>) -> Result<(), String> {
    launcher(&window)?;
    let page = match state.update.lock().map_err(|e| e.to_string())?.as_ref() {
        Some(update) => format!("{RELEASES}/tag/v{}", update.version),
        None => format!("{RELEASES}/latest"),
    };
    open::that(page).map_err(|e| e.to_string())
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

struct DataPlace {
    root: PathBuf,
    installed: bool,
    beside_program: bool,
    notice: Option<String>,
}

const PORTABLE_NOTICE_READ: &str = ".portable-notice-read";
const OVERLAY_NOTICE_READ: &str = ".overlay-notice-read";
const DATA_NOTE: &str = "请勿删除 - 梦鱼更新管理的数据.txt";

/// A portable copy started from a temporary folder (opened inside the zip, or only the exe taken
/// out) would scatter data that is gone on the next cleanup, so it stops before writing anything.
fn portable_problem() -> Option<String> {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--desktop-home") {
        return None;
    }
    let program = std::env::current_exe().ok()?.parent()?.to_path_buf();
    if program.join("uninstall.exe").is_file() {
        return None;
    }
    let canonical = |p: &Path| {
        dunce::canonicalize(p)
            .unwrap_or_else(|_| p.to_path_buf())
            .to_string_lossy()
            .to_lowercase()
    };
    if canonical(&program).starts_with(&canonical(&std::env::temp_dir())) {
        return Some("梦鱼更新管理正在临时文件夹里运行，可能是直接在压缩包里打开的。\n\n请先把整个压缩包解压到一个固定的文件夹（例如 D:\\梦鱼更新管理），再运行里面的 DreamingFishAdminDesktop.exe。便携版的数据会保存在那个文件夹里。".into());
    }
    if !cfg!(debug_assertions) && !program.join("core").join("dfs-admin.jar").is_file() {
        return Some("程序旁边缺少 core 文件夹。\n\n请把压缩包里的整个“梦鱼更新管理”文件夹解压出来，不要只拿出 DreamingFishAdminDesktop.exe。".into());
    }
    None
}

#[cfg(windows)]
fn show_startup_message(message: &str) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONWARNING, MB_OK};
    let wide = |s: &str| s.encode_utf16().chain(Some(0)).collect::<Vec<u16>>();
    let (text, title) = (wide(message), wide("梦鱼更新管理"));
    unsafe { MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), MB_OK | MB_ICONWARNING) };
}

#[cfg(not(windows))]
fn show_startup_message(message: &str) {
    eprintln!("{message}");
}

/// Explains, inside the data folder itself, what deleting it would lose.
fn write_data_note(root: &Path) {
    let note = root.join(DATA_NOTE);
    if !note.exists() {
        let _ = fs::write(note, "这个 data 文件夹保存梦鱼更新管理的连接记录、运行日志，以及默认管理数据目录（management）里的项目、账户、签名密钥和发布记录。\r\n\r\n请不要删除或单独移走它。签名密钥丢失后，就无法再给已经分发出去的玩家端发布更新。\r\n\r\n要换电脑或换位置，请把程序文件夹连同 data 一起复制；也可以只复制 data，放到新程序旁边。\r\n".as_bytes());
    }
}

/// Connection records, logs and the default management data live in `data` beside the program:
/// the install folder for the installed app, the unpacked folder for the portable one. A folder
/// the user cannot write, such as Program Files, falls back to the per-user directory that
/// versions before 0.2.3 used.
fn data_place(app: &tauri::AppHandle) -> Result<DataPlace, String> {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--background-check") && !args.iter().any(|a| a == "--desktop-home")
    {
        return Err("后台验收必须使用独立的 --desktop-home 目录".into());
    }
    let program = std::env::current_exe()
        .map_err(|e| e.to_string())?
        .parent()
        .ok_or("无法确定程序所在目录")?
        .to_path_buf();
    let installed = program.join("uninstall.exe").is_file();
    if let Some(index) = args.iter().position(|a| a == "--desktop-home") {
        let path = PathBuf::from(args.get(index + 1).ok_or("--desktop-home 缺少目录")?);
        if !path.is_absolute() {
            return Err("桌面数据目录必须是绝对路径".into());
        }
        return Ok(DataPlace { root: path, installed, beside_program: false, notice: None });
    }
    // Tests point the migration at a fake old folder instead of the real per-user one.
    let legacy = match args.iter().position(|a| a == "--legacy-home") {
        Some(index) => PathBuf::from(args.get(index + 1).ok_or("--legacy-home 缺少目录")?),
        None => app.path().app_local_data_dir().map_err(|e| e.to_string())?,
    };
    let beside = program.join("data");
    Ok(choose_default_place(&legacy,&beside,installed,writable(&beside)))
}
fn choose_default_place(legacy:&Path,beside:&Path,installed:bool,can_write:bool)->DataPlace {
    if can_write {
        let outcome=migrate::legacy_into(legacy,beside);write_data_note(&outcome.root);
        let beside_program=outcome.root==beside;
        DataPlace {root:outcome.root,installed,beside_program,notice:outcome.notice}
    }else{DataPlace {root:legacy.into(),installed,beside_program:false,notice:None}}
}

fn writable(dir: &Path) -> bool {
    let probe = dir.join(".write-check");
    fs::create_dir_all(dir).is_ok() && fs::write(&probe, b"").is_ok() && fs::remove_file(&probe).is_ok()
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
                relocation_required: false, confirm_relocation: false,
                connection: profiles::Connection::Local { home: snapshot.default_home },
            })? };
            let owned = !matches!(profile.connection, profiles::Connection::Remote { .. });
            connect_profile(main, app.clone(), profile.id).await?;
            if let Some(mode)=args.iter().position(|a|a=="--background-fault").and_then(|i|args.get(i+1)){
                let inject=app.clone();let mode=mode.clone();
                tauri::async_runtime::spawn_blocking(move||{
                    if mode=="main"||mode=="both"{rebuild_window(&inject,"main","simulated-render-process-exited");}
                    if mode=="workspace"||mode=="both"{rebuild_window(&inject,"workspace","simulated-render-process-unresponsive");}
                    if mode=="limit"{for _ in 0..4{rebuild_window(&inject,"workspace","simulated-browser-process-exited");}recover_current(&inject,false)?;}
                    Ok::<_,String>(())
                }).await.map_err(|e|e.to_string())??;
            }
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
            Ok::<_, String>(serde_json::json!({"version":"0.2.8", "buildId":option_env!("DFS_BUILD_ID").unwrap_or("development"),"success":true,
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
        if BACKGROUND_FINISHED.swap(true,Ordering::SeqCst){return;}
        let write = serde_json::to_vec_pretty(&report)
            .map_err(|e| e.to_string())
            .and_then(|bytes| {
                fs::write(state.root.join("background-check.json"), bytes)
                    .map_err(|e| e.to_string())
            });
        let exit_code=if write.is_ok(){exit_code}else{1};
        BACKGROUND_EXIT_CODE.store(exit_code,Ordering::SeqCst);
        app.exit(exit_code);
    });
}

fn main_window_visible(app: &tauri::AppHandle) -> Result<bool, String> {
    app.get_webview_window("main")
        .ok_or("连接窗口缺失")?
        .is_visible()
        .map_err(|e| e.to_string())
}

static BACKGROUND_FINISHED:AtomicBool=AtomicBool::new(false);
static BACKGROUND_EXIT_CODE:AtomicI32=AtomicI32::new(-1);
fn start_background_deadline(){
    let args:Vec<String>=std::env::args().collect();if !args.iter().any(|a|a=="--background-check"){return;}
    let Some(root)=args.iter().position(|a|a=="--desktop-home").and_then(|i|args.get(i+1)).map(PathBuf::from)else{return;};
    if !root.is_absolute(){return;}
    let timeout=args.iter().position(|a|a=="--background-timeout-ms").and_then(|i|args.get(i+1)).and_then(|v|v.parse::<u64>().ok()).unwrap_or(45000).clamp(1000,120000);
    std::thread::spawn(move||{
        std::thread::sleep(Duration::from_millis(timeout));
        if !BACKGROUND_FINISHED.swap(true,Ordering::SeqCst){
            let _=fs::create_dir_all(&root);let value=serde_json::json!({"success":false,"error":"后台验收从进程启动起达到总期限","timeoutMillis":timeout});
            let _=fs::write(root.join("background-check.json"),serde_json::to_vec_pretty(&value).unwrap());
            // Owned Windows Job Objects close with the process even if initialization is stuck.
            std::process::exit(1);
        }
    });
    if args.iter().any(|a|a=="--simulate-init-hang"){loop{std::thread::sleep(Duration::from_secs(1));}}
}

fn main() {
    start_background_deadline();
    if let Some(problem) = portable_problem() {
        show_startup_message(&problem);
        return;
    }
    let exit_code=tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _| {
            if !args.iter().any(|a| a == "--background-check") {
                show_main(app)
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
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
            desktop_window_action,
            save_deployment_download,
            reveal_deployment_download,
            open_data_dir,
            dismiss_notice,
            check_update,
            install_update,
            open_release_page
        ])
        .setup(|app| {
            let place = data_place(app.handle()).map_err(std::io::Error::other)?;
            let root = place.root;
            fs::create_dir_all(root.join("logs"))?;
            let (config, recovery_notice) = Config::load_with_notice(&root).map_err(std::io::Error::other)?;
            let main_root = root.clone();
            app.manage(Desktop {
                root,
                installed: place.installed,
                beside_program: place.beside_program,
                update: Mutex::new(None),
                updating: AtomicBool::new(false),
                config: Mutex::new(config),
                active: Mutex::new(None),
                connecting: Mutex::new(false),
                generation: AtomicU64::new(0),
                exiting: AtomicBool::new(false),
                notice: Mutex::new([place.notice, recovery_notice].into_iter().flatten().collect::<Vec<_>>().join("\n").into()),
                background_check: std::env::args().any(|a| a == "--background-check"),
                frame_checks: Mutex::new((false, false)),
                workbench_ready: AtomicBool::new(false),
                background_started: AtomicBool::new(false),
                disconnecting: AtomicBool::new(false),
                rebuilds: Mutex::new(recovery::Budget::default()),
                downloads: downloads::Downloads::default(),
            });
            let main = build_main(app.handle(), &main_root)?;
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
            TrayIconBuilder::with_id("main")
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
        .run_return(|app, event| match event {
            // Windows only hide; this fires when a crashed window is being rebuilt. Quitting uses exit(0).
            tauri::RunEvent::ExitRequested { code: None, api, .. } => api.prevent_exit(),
            tauri::RunEvent::Exit => {
                let state = app.state::<Desktop>();
                state.exiting.store(true, Ordering::SeqCst);
                if let Ok(mut active) = state.active.lock() {
                    *active = None;
                };
            }
            _ => {}
        });
    let background_exit=BACKGROUND_EXIT_CODE.load(Ordering::SeqCst);
    std::process::exit(if background_exit>0{background_exit}else{exit_code});
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn launcher_snapshot_does_not_relock_an_active_guard_inside_one_struct_literal(){
        let root=std::env::temp_dir().join(format!("dfs-snapshot-{}",uuid::Uuid::new_v4()));fs::create_dir_all(&root).unwrap();
        let state=Desktop {root:root.clone(),installed:false,beside_program:true,update:Mutex::new(None),updating:AtomicBool::new(false),
            config:Mutex::new(Config::load(&root).unwrap()),active:Mutex::new(None),connecting:Mutex::new(false),generation:AtomicU64::new(0),
            exiting:AtomicBool::new(false),notice:Mutex::new(None),background_check:true,frame_checks:Mutex::new((false,false)),
            workbench_ready:AtomicBool::new(false),background_started:AtomicBool::new(false),disconnecting:AtomicBool::new(false),rebuilds:Mutex::new(recovery::Budget::default()),downloads:downloads::Downloads::default()};
        let (sender,receiver)=std::sync::mpsc::channel();
        let worker=std::thread::spawn(move||{let snapshot=desktop_snapshot(&state);let released=state.active.try_lock().is_ok();sender.send((snapshot,released)).unwrap();});
        let (snapshot,released)=receiver.recv_timeout(Duration::from_secs(2)).expect("launcher snapshot must finish without waiting for its own active mutex");
        let snapshot=snapshot.unwrap();assert!(snapshot.active.is_none());assert!(snapshot.connection_resources.is_none());assert!(released);worker.join().unwrap();fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn full_default_location_entry_uses_fallback_and_supports_appdata_portability(){
        let base=std::env::temp_dir().join(format!("dfs-location-{}-{}",std::process::id(),uuid::Uuid::new_v4()));
        let old=base.join("legacy");let beside=base.join("program/data");
        fs::create_dir_all(old.join("management/data")).unwrap();fs::write(old.join("management/data/management.db"),b"synthetic").unwrap();
        let cfg=serde_json::json!({"profiles":[{"id":"test","name":"Test","kind":"local","home":old.join("management")}]});
        fs::write(old.join("connections.json"),serde_json::to_vec(&cfg).unwrap()).unwrap();
        fs::create_dir_all(beside.join("connections.json.tmp")).unwrap();
        let place=choose_default_place(&old,&beside,false,true);assert_eq!(place.root,old);assert!(!place.beside_program);assert_eq!(Config::load(&place.root).unwrap().profiles.len(),1);
        let fallback=choose_default_place(&old,&beside,false,false);assert_eq!(fallback.root,old);
        // New portable configs resolve against the actual fallback root too.
        let config=Config::load(&old).unwrap();config.save(&old).unwrap();assert!(fs::read_to_string(old.join("connections.json")).unwrap().contains("\"management\""));
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn data_folder_is_probed_without_leaving_files_and_gets_a_note() {
        let root = std::env::temp_dir().join(format!("dfs-data-place-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let beside = root.join("program").join("data");
        assert!(writable(&beside));
        assert!(!beside.join(".write-check").exists());
        write_data_note(&beside);
        assert!(fs::read_to_string(beside.join(DATA_NOTE)).unwrap().contains("请不要删除"));
        let _ = fs::remove_dir_all(&root);
    }
}
