use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use tauri::{AppHandle, Emitter, Manager, State};

const ALLOW_DEVELOPMENT_OVERRIDES: bool = cfg!(debug_assertions);
const MAX_STARTUP_MUSIC_BYTES: u64 = 20 * 1024 * 1024;
const MAX_SHELL_LOG_BYTES: u64 = 512 * 1024;
static DEBUG_LOG_LOCK: Mutex<()> = Mutex::new(());

struct Sidecar {
    child: Child,
    stdin: ChildStdin,
}

#[derive(Default)]
struct SidecarState(Mutex<Option<Sidecar>>);

#[derive(Default)]
struct NativeCloseState(AtomicU64);

fn debug_log(message: &str) {
    use std::io::Write;
    let Ok(_guard) = DEBUG_LOG_LOCK.lock() else {
        return;
    };
    let path = shell_log_path();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if path
        .metadata()
        .is_ok_and(|metadata| metadata.len() >= MAX_SHELL_LOG_BYTES)
    {
        let archive = path.with_extension("log.1");
        let _ = std::fs::remove_file(&archive);
        let _ = std::fs::rename(&path, archive);
    }
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        let _ = writeln!(file, "{} | SHELL | {}", utc_timestamp(), message);
    }
}

fn shell_log_path() -> PathBuf {
    let arguments: Vec<String> = std::env::args().collect();
    arguments
        .windows(2)
        .find(|pair| pair[0] == "--instance")
        .map(|pair| PathBuf::from(&pair[1]))
        .map(|root| root.join("DreamingFishUpdater/logs/player-shell.log"))
        .unwrap_or_else(|| std::env::temp_dir().join("dfs-player-sidecar.log"))
}

fn utc_timestamp() -> String {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    format_utc_timestamp(millis)
}

fn format_utc_timestamp(total_millis: u128) -> String {
    let total_seconds = (total_millis / 1_000).min(i64::MAX as u128) as i64;
    let millis = total_millis % 1_000;
    let days = total_seconds.div_euclid(86_400);
    let seconds = total_seconds.rem_euclid(86_400);
    let hour = seconds / 3_600;
    let minute = (seconds % 3_600) / 60;
    let second = seconds % 60;
    let (year, month, day) = civil_from_days(days);
    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}.{millis:03}Z")
}

fn civil_from_days(days_since_epoch: i64) -> (i64, i64, i64) {
    let shifted = days_since_epoch + 719_468;
    let era = if shifted >= 0 {
        shifted
    } else {
        shifted - 146_096
    } / 146_097;
    let day_of_era = shifted - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let mut year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_phase = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_phase + 2) / 5 + 1;
    let month = month_phase + if month_phase < 10 { 3 } else { -9 };
    if month <= 2 {
        year += 1;
    }
    (year, month, day)
}

fn redacted_argument_names(arguments: &[String]) -> String {
    arguments
        .iter()
        .filter(|value| value.starts_with("--"))
        .map(String::as_str)
        .collect::<Vec<_>>()
        .join(",")
}

fn is_explicit_exit_message(line: &str) -> bool {
    serde_json::from_str::<serde_json::Value>(line)
        .ok()
        .and_then(|value| {
            value
                .get("type")
                .and_then(|kind| kind.as_str())
                .map(str::to_owned)
        })
        .is_some_and(|kind| kind == "exit")
}

fn append_stderr_line(lines: &Arc<Mutex<VecDeque<String>>>, line: String) {
    if let Ok(mut guard) = lines.lock() {
        guard.push_back(line);
        while guard.len() > 40 {
            guard.pop_front();
        }
    }
}

fn format_sidecar_crash(status: Option<ExitStatus>, lines: &VecDeque<String>) -> String {
    let status_text = status
        .map(|value| format!("更新引擎退出状态：{value}"))
        .unwrap_or_else(|| "更新引擎的输出通道意外关闭".to_string());
    let stderr = lines.iter().cloned().collect::<Vec<_>>().join("\n");
    if stderr.is_empty() {
        return format!("{status_text}\n没有收到错误输出。可以点击“重试”重新启动更新引擎。");
    }
    let mut detail = format!("{status_text}\n\n{stderr}");
    if detail.chars().count() > 8_000 {
        detail = detail
            .chars()
            .rev()
            .take(8_000)
            .collect::<String>()
            .chars()
            .rev()
            .collect();
        detail.insert_str(0, "…错误输出过长，仅保留末尾内容…\n");
    }
    detail
}

fn exe_dir() -> Result<PathBuf, String> {
    std::env::current_exe()
        .map_err(|error| format!("无法定位程序目录: {error}"))?
        .parent()
        .map(Path::to_path_buf)
        .ok_or_else(|| "无法定位程序目录".to_string())
}

fn java_executable(base: &Path) -> Result<PathBuf, String> {
    let development_override = development_override("DFS_JAVA");
    resolve_java_executable(
        base,
        development_override.as_deref(),
        ALLOW_DEVELOPMENT_OVERRIDES,
    )
}

fn resolve_java_executable(
    base: &Path,
    development_override: Option<&Path>,
    allow_development_override: bool,
) -> Result<PathBuf, String> {
    if allow_development_override {
        if let Some(value) = development_override.filter(|value| value.is_file()) {
            return Ok(value.to_path_buf());
        }
    }
    let name = if cfg!(windows) { "java.exe" } else { "java" };
    let candidate = base.join("runtime").join("bin").join(name);
    if candidate.is_file() {
        Ok(candidate)
    } else {
        Err(format!(
            "未找到内置 Java 运行时: {}。开发环境可设置 DFS_JAVA 指向 java 可执行文件。",
            candidate.display()
        ))
    }
}

fn sidecar_jar(base: &Path) -> Result<PathBuf, String> {
    let development_override = development_override("DFS_SIDECAR_JAR");
    resolve_sidecar_jar(
        base,
        development_override.as_deref(),
        ALLOW_DEVELOPMENT_OVERRIDES,
    )
}

fn instance_root_from_args() -> Result<PathBuf, String> {
    let args: Vec<String> = std::env::args().collect();
    let value = args
        .windows(2)
        .find(|pair| pair[0] == "--instance")
        .map(|pair| PathBuf::from(&pair[1]))
        .ok_or_else(|| "启动参数中没有 Minecraft 实例目录".to_string())?;
    value
        .canonicalize()
        .map_err(|error| format!("无法读取 Minecraft 实例目录: {error}"))
}

#[tauri::command]
fn read_local_image(path: String) -> Result<String, String> {
    let instance_root = instance_root_from_args()?;
    read_local_image_from_root(Path::new(&path), &instance_root)
}

fn read_startup_music_from_root(instance_root: &Path) -> Result<Option<String>, String> {
    let root = instance_root
        .canonicalize()
        .map_err(|error| format!("无法读取 Minecraft 实例目录: {error}"))?;
    // Keep the persistent path first. The legacy names are accepted so a music
    // file from the former JavaFX player can be reused without renaming it.
    let candidates = [
        "DreamingFishUpdater/startup-music.mp3",
        "DreamingFishUpdater/bg_music.mp3",
        "DreamingFishUpdater/audio/bg_music.mp3",
        ".dreamingfish-bootstrap/startup-music.mp3",
        ".dreamingfish-bootstrap/bg_music.mp3",
        ".dreamingfish-bootstrap/audio/bg_music.mp3",
    ];
    for relative in candidates {
        let candidate = root.join(relative);
        if !candidate.is_file() {
            continue;
        }
        let is_mp3 = candidate
            .extension()
            .and_then(|extension| extension.to_str())
            .map(|extension| extension.eq_ignore_ascii_case("mp3"))
            .unwrap_or(false);
        if !is_mp3 {
            continue;
        }
        let canonical = candidate
            .canonicalize()
            .map_err(|error| format!("无法读取启动音乐: {error}"))?;
        if !canonical.starts_with(&root) {
            continue;
        }
        let metadata =
            std::fs::metadata(&canonical).map_err(|error| format!("无法读取启动音乐: {error}"))?;
        if metadata.len() > MAX_STARTUP_MUSIC_BYTES {
            return Err("启动音乐不能超过 20 MiB".to_string());
        }
        let bytes =
            std::fs::read(&canonical).map_err(|error| format!("无法读取启动音乐: {error}"))?;
        return Ok(Some(format!(
            "data:audio/mpeg;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(bytes)
        )));
    }
    Ok(None)
}

fn read_music_track_from_root(
    instance_root: &Path,
    file_name: &str,
) -> Result<Option<String>, String> {
    let relative = Path::new(file_name);
    if relative.is_absolute()
        || relative
            .components()
            .any(|component| matches!(component, std::path::Component::ParentDir))
    {
        return Err("音乐文件名必须是音乐目录内的相对路径".to_string());
    }
    if relative
        .extension()
        .and_then(|value| value.to_str())
        .map(|value| value.eq_ignore_ascii_case("mp3"))
        != Some(true)
    {
        return Err("音乐文件仅支持 MP3 格式".to_string());
    }
    let root = instance_root
        .canonicalize()
        .map_err(|error| format!("无法读取 Minecraft 实例目录: {error}"))?;
    for directory in [
        root.join(".dreamingfish-bootstrap/music"),
        root.join("DreamingFishUpdater/music"),
    ] {
        let candidate = directory.join(relative);
        if !candidate.is_file() {
            continue;
        }
        let canonical = candidate
            .canonicalize()
            .map_err(|error| format!("无法读取音乐文件: {error}"))?;
        if !canonical.starts_with(&directory) {
            continue;
        }
        let metadata =
            std::fs::metadata(&canonical).map_err(|error| format!("无法读取音乐文件: {error}"))?;
        if metadata.len() > 20 * 1024 * 1024 {
            return Err("音乐文件不能超过 20 MiB".to_string());
        }
        let bytes =
            std::fs::read(&canonical).map_err(|error| format!("无法读取音乐文件: {error}"))?;
        return Ok(Some(format!(
            "data:audio/mpeg;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(bytes)
        )));
    }
    Ok(None)
}

#[tauri::command]
fn read_music_track(file_name: String) -> Result<Option<String>, String> {
    read_music_track_from_root(&instance_root_from_args()?, &file_name)
}

#[tauri::command]
fn read_startup_music() -> Result<Option<String>, String> {
    read_startup_music_from_root(&instance_root_from_args()?)
}

fn read_local_image_from_root(path: &Path, instance_root: &Path) -> Result<String, String> {
    let canonical = path
        .canonicalize()
        .map_err(|error| format!("无法读取背景图片: {error}"))?;
    let canonical_root = instance_root
        .canonicalize()
        .map_err(|error| format!("无法读取 Minecraft 实例目录: {error}"))?;
    if !canonical.starts_with(&canonical_root) || !canonical.is_file() {
        return Err("背景图片必须位于当前 Minecraft 实例目录内".to_string());
    }
    let mime = match canonical
        .extension()
        .and_then(|value| value.to_str())
        .map(|value| value.to_ascii_lowercase())
        .as_deref()
    {
        Some("png") => "image/png",
        Some("jpg") | Some("jpeg") => "image/jpeg",
        Some("webp") => "image/webp",
        _ => return Err("背景图片格式仅支持 PNG、JPEG 或 WebP".to_string()),
    };
    let metadata =
        std::fs::metadata(&canonical).map_err(|error| format!("无法读取背景图片: {error}"))?;
    if metadata.len() > 20 * 1024 * 1024 {
        return Err("背景图片不能超过 20 MiB".to_string());
    }
    let bytes = std::fs::read(&canonical).map_err(|error| format!("无法读取背景图片: {error}"))?;
    Ok(format!(
        "data:{mime};base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    ))
}

fn resolve_sidecar_jar(
    base: &Path,
    development_override: Option<&Path>,
    allow_development_override: bool,
) -> Result<PathBuf, String> {
    if allow_development_override {
        if let Some(value) = development_override.filter(|value| value.is_file()) {
            return Ok(value.to_path_buf());
        }
    }
    let mut candidates = vec![base.join("player-sidecar.jar")];
    if allow_development_override {
        candidates.push(base.join("app").join("player-sidecar.jar"));
    }
    for candidate in candidates {
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    Err(format!(
        "未找到更新引擎: {}。开发环境可设置 DFS_SIDECAR_JAR。",
        base.join("player-sidecar.jar").display()
    ))
}

fn development_override(name: &str) -> Option<PathBuf> {
    if !ALLOW_DEVELOPMENT_OVERRIDES {
        return None;
    }
    std::env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

#[tauri::command]
fn spawn_sidecar(app: AppHandle, state: State<'_, SidecarState>) -> Result<(), String> {
    debug_log("spawn_sidecar invoked");
    {
        let mut guard = state.0.lock().map_err(|_| "更新引擎状态锁定失败")?;
        if guard
            .as_mut()
            .is_some_and(|sidecar| sidecar.child.try_wait().ok().flatten().is_none())
        {
            return Ok(());
        }
        *guard = None;
    }

    let base = exe_dir()?;
    let java = java_executable(&base)?;
    let jar = sidecar_jar(&base)?;
    let mut raw_args: Vec<String> = std::env::args().skip(1).collect();
    if raw_args.is_empty() {
        raw_args.push("--preview".to_string());
    }
    debug_log(&format!(
        "sidecar command prepared; arg_names={}",
        redacted_argument_names(&raw_args)
    ));

    let mut command = Command::new(&java);
    command
        .arg("-Dfile.encoding=UTF-8")
        .arg("-Dstdin.encoding=UTF-8")
        .arg("-Dstdout.encoding=UTF-8")
        .arg("-Dstderr.encoding=UTF-8")
        .arg("-jar")
        .arg(&jar)
        .args(&raw_args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // CREATE_NO_WINDOW: the Java sidecar must not open a console window.
        command.creation_flags(0x08000000);
    }

    let mut child = command.spawn().map_err(|error| {
        debug_log(&format!("spawn failed: {error}"));
        format!("无法启动更新引擎 {}: {error}", java.to_string_lossy())
    })?;
    debug_log(&format!("sidecar pid={}", child.id()));
    let stdin = child.stdin.take().ok_or_else(|| "更新引擎输入通道不可用")?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "更新引擎输出通道不可用")?;
    let stderr = child
        .stderr
        .take()
        .ok_or_else(|| "更新引擎错误通道不可用")?;

    {
        let mut guard = state.0.lock().map_err(|_| "更新引擎状态锁定失败")?;
        *guard = Some(Sidecar { child, stdin });
    }

    let stderr_lines = Arc::new(Mutex::new(VecDeque::new()));
    let stderr_capture = Arc::clone(&stderr_lines);
    let stderr_thread = std::thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            match line {
                Ok(line) => append_stderr_line(&stderr_capture, line),
                Err(_) => break,
            }
        }
    });

    let out_app = app.clone();
    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        let mut explicit_exit = false;
        for line in reader.lines() {
            match line {
                Ok(line) => {
                    explicit_exit |= is_explicit_exit_message(&line);
                    let _ = out_app.emit("sidecar-line", line);
                }
                Err(_) => break,
            }
        }
        let status = if let Some(state) = out_app.try_state::<SidecarState>() {
            if let Ok(mut guard) = state.0.lock() {
                guard
                    .take()
                    .and_then(|mut sidecar| match sidecar.child.try_wait() {
                        Ok(Some(status)) => Some(status),
                        _ => {
                            let _ = sidecar.child.kill();
                            sidecar.child.wait().ok()
                        }
                    })
            } else {
                None
            }
        } else {
            None
        };
        let _ = stderr_thread.join();
        if explicit_exit {
            debug_log("sidecar exited after explicit exit message");
            return;
        }
        debug_log("sidecar stdout closed unexpectedly");
        let detail = stderr_lines
            .lock()
            .ok()
            .map(|lines| format_sidecar_crash(status, &lines))
            .unwrap_or_else(|| "更新引擎异常退出。可以点击“重试”重新启动。".to_string());
        let _ = out_app.emit("sidecar-crashed", detail);
    });

    Ok(())
}

#[tauri::command]
fn send_command(state: State<'_, SidecarState>, line: String) -> Result<(), String> {
    let mut guard = state.0.lock().map_err(|_| "更新引擎状态锁定失败")?;
    let sidecar = guard.as_mut().ok_or_else(|| "更新引擎尚未启动")?;
    sidecar
        .stdin
        .write_all(line.as_bytes())
        .and_then(|_| sidecar.stdin.write_all(b"\n"))
        .and_then(|_| sidecar.stdin.flush())
        .map_err(|error| format!("无法写入更新引擎: {error}"))
}

fn write_close_command(mut writer: impl Write) -> std::io::Result<()> {
    writer.write_all(b"{\"command\":\"close\"}\n")?;
    writer.flush()
}

fn forward_native_close(app: &AppHandle) -> bool {
    let Some(state) = app.try_state::<SidecarState>() else {
        return false;
    };
    let Ok(mut guard) = state.0.lock() else {
        return false;
    };
    let Some(sidecar) = guard.as_mut() else {
        return false;
    };
    write_close_command(&mut sidecar.stdin).is_ok()
}

fn unix_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn should_force_native_close(previous_millis: u64, current_millis: u64) -> bool {
    previous_millis != 0 && current_millis.saturating_sub(previous_millis) <= 3_000
}

#[tauri::command]
fn window_show(app: AppHandle) -> Result<(), String> {
    app.get_webview_window("main")
        .ok_or_else(|| "找不到主窗口".to_string())?
        .show()
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn window_minimize(app: AppHandle) -> Result<(), String> {
    app.get_webview_window("main")
        .ok_or_else(|| "找不到主窗口".to_string())?
        .minimize()
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn window_toggle_maximize(app: AppHandle) -> Result<(), String> {
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "找不到主窗口".to_string())?;
    if window.is_maximized().unwrap_or(false) {
        window.unmaximize().map_err(|error| error.to_string())
    } else {
        window.maximize().map_err(|error| error.to_string())
    }
}

#[tauri::command]
fn window_close(app: AppHandle) -> Result<(), String> {
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "找不到主窗口".to_string())?;
    window.close().map_err(|error| error.to_string())
}

#[tauri::command]
fn window_start_drag(app: AppHandle) -> Result<(), String> {
    app.get_webview_window("main")
        .ok_or_else(|| "找不到主窗口".to_string())?
        .start_dragging()
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn open_path(path: String) -> Result<(), String> {
    open::that(&path).map_err(|error| format!("无法打开目录: {error}"))
}

#[tauri::command]
fn open_external(uri: String) -> Result<(), String> {
    open::that(&uri).map_err(|error| format!("无法打开链接: {error}"))
}

#[tauri::command]
fn quit_application(app: AppHandle) {
    app.exit(0);
}

pub fn run() {
    let app = tauri::Builder::default()
        .manage(SidecarState::default())
        .manage(NativeCloseState::default())
        .invoke_handler(tauri::generate_handler![
            spawn_sidecar,
            send_command,
            window_show,
            window_minimize,
            window_toggle_maximize,
            window_close,
            window_start_drag,
            open_path,
            open_external,
            read_local_image,
            read_startup_music,
            read_music_track,
            quit_application
        ])
        .build(tauri::generate_context!())
        .expect("error while building DreamingFish Updater");

    app.run(|app_handle, event| match event {
        tauri::RunEvent::WindowEvent {
            label,
            event: tauri::WindowEvent::CloseRequested { api, .. },
            ..
        } if label == "main" => {
            // Alt+F4 and OS close requests must follow the same Java decision
            // path as the custom title-bar button. Otherwise the shell would
            // kill the sidecar and Bootstrap might incorrectly take fallback.
            // Only hold the native close when Java actually received the
            // request. With no sidecar or broken stdin, allow the OS close to
            // proceed so a damaged shell can never trap the user.
            let close_state = app_handle.state::<NativeCloseState>();
            let now = unix_millis();
            let previous = close_state.0.swap(now, Ordering::SeqCst);
            if should_force_native_close(previous, now) {
                return;
            }
            if forward_native_close(app_handle) {
                api.prevent_close();
            } else {
                close_state.0.store(0, Ordering::SeqCst);
            }
        }
        tauri::RunEvent::Exit => {
            if let Some(state) = app_handle.try_state::<SidecarState>() {
                if let Ok(mut guard) = state.0.lock() {
                    if let Some(sidecar) = guard.as_mut() {
                        let _ = sidecar.child.kill();
                        let _ = sidecar.child.wait();
                    }
                }
            }
        }
        _ => {}
    });
}

#[cfg(test)]
mod tests {
    use super::{
        format_sidecar_crash, format_utc_timestamp, is_explicit_exit_message,
        read_local_image_from_root, read_startup_music_from_root, redacted_argument_names,
        resolve_java_executable, resolve_sidecar_jar, should_force_native_close,
        write_close_command, MAX_STARTUP_MUSIC_BYTES,
    };
    use std::collections::VecDeque;
    use std::io::Cursor;
    use std::path::PathBuf;

    fn fixture() -> (PathBuf, PathBuf) {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "dfs-sidecar-path-test-{}-{}",
            std::process::id(),
            unique
        ));
        let base = root.join("signed-program");
        let external = root.join("untrusted");
        let java_name = if cfg!(windows) { "java.exe" } else { "java" };
        std::fs::create_dir_all(base.join("runtime").join("bin")).unwrap();
        std::fs::create_dir_all(&external).unwrap();
        std::fs::write(base.join("runtime").join("bin").join(java_name), b"signed").unwrap();
        std::fs::write(base.join("player-sidecar.jar"), b"signed").unwrap();
        std::fs::write(external.join(java_name), b"untrusted").unwrap();
        std::fs::write(external.join("player-sidecar.jar"), b"untrusted").unwrap();
        (base, external)
    }

    #[test]
    fn release_resolution_ignores_external_overrides() {
        let (base, external) = fixture();
        let java_name = if cfg!(windows) { "java.exe" } else { "java" };

        assert_eq!(
            resolve_java_executable(&base, Some(&external.join(java_name)), false).unwrap(),
            base.join("runtime").join("bin").join(java_name)
        );
        assert_eq!(
            resolve_sidecar_jar(&base, Some(&external.join("player-sidecar.jar")), false).unwrap(),
            base.join("player-sidecar.jar")
        );

        let _ = std::fs::remove_dir_all(base.parent().unwrap());
    }

    #[test]
    fn debug_resolution_keeps_explicit_overrides() {
        let (base, external) = fixture();
        let java_name = if cfg!(windows) { "java.exe" } else { "java" };

        assert_eq!(
            resolve_java_executable(&base, Some(&external.join(java_name)), true).unwrap(),
            external.join(java_name)
        );
        assert_eq!(
            resolve_sidecar_jar(&base, Some(&external.join("player-sidecar.jar")), true).unwrap(),
            external.join("player-sidecar.jar")
        );

        let _ = std::fs::remove_dir_all(base.parent().unwrap());
    }

    #[test]
    fn local_image_reader_rejects_files_outside_the_instance() {
        let (base, external) = fixture();
        let inside = base.join("cover.png");
        let outside = external.join("cover.png");
        std::fs::write(&inside, b"image").unwrap();
        std::fs::write(&outside, b"image").unwrap();

        assert!(read_local_image_from_root(&inside, &base)
            .unwrap()
            .starts_with("data:image/png;base64,"));
        assert!(read_local_image_from_root(&outside, &base).is_err());

        let _ = std::fs::remove_dir_all(base.parent().unwrap());
    }

    #[test]
    fn startup_music_reader_accepts_only_instance_mp3_and_size_limit() {
        let (base, external) = fixture();
        assert!(read_startup_music_from_root(&base).unwrap().is_none());
        std::fs::create_dir_all(base.join(".dreamingfish-bootstrap")).unwrap();
        std::fs::write(
            base.join(".dreamingfish-bootstrap/startup-music.mp3"),
            b"mp3",
        )
        .unwrap();
        assert!(read_startup_music_from_root(&base)
            .unwrap()
            .unwrap()
            .starts_with("data:audio/mpeg;base64,"));
        std::fs::write(external.join("startup-music.mp3"), b"outside").unwrap();
        std::fs::write(
            base.join(".dreamingfish-bootstrap/startup-music.mp3"),
            vec![0_u8; (MAX_STARTUP_MUSIC_BYTES + 1) as usize],
        )
        .unwrap();
        assert!(read_startup_music_from_root(&base).is_err());
        let _ = std::fs::remove_dir_all(base.parent().unwrap());
    }

    #[test]
    fn native_close_writes_one_complete_json_command() {
        let mut output = Cursor::new(Vec::new());
        write_close_command(&mut output).unwrap();
        assert_eq!(output.into_inner(), b"{\"command\":\"close\"}\n");
    }

    #[test]
    fn second_native_close_within_three_seconds_forces_exit() {
        assert!(!should_force_native_close(0, 10_000));
        assert!(should_force_native_close(10_000, 12_000));
        assert!(!should_force_native_close(10_000, 14_001));
    }

    #[test]
    fn explicit_exit_and_abnormal_eof_are_distinguishable() {
        assert!(is_explicit_exit_message(r#"{"type":"exit"}"#));
        assert!(!is_explicit_exit_message(
            r#"{"type":"error","detail":"exit"}"#
        ));
        let detail = format_sidecar_crash(None, &VecDeque::from(["fatal JVM error".to_string()]));
        assert!(detail.contains("fatal JVM error"));
        assert!(detail.contains("意外关闭"));
    }

    #[test]
    fn release_debug_summary_never_contains_argument_values() {
        let arguments = vec![
            "--bootstrap-token".to_string(),
            "super-secret-token".to_string(),
            "--instance".to_string(),
            "C:\\Users\\name\\instance".to_string(),
            "--player-name".to_string(),
            "PrivatePlayer".to_string(),
        ];
        let summary = redacted_argument_names(&arguments);
        assert_eq!(summary, "--bootstrap-token,--instance,--player-name");
        assert!(!summary.contains("super-secret-token"));
        assert!(!summary.contains("PrivatePlayer"));
    }

    #[test]
    fn shell_log_timestamp_contains_a_complete_utc_date() {
        assert_eq!(format_utc_timestamp(0), "1970-01-01T00:00:00.000Z");
        assert_eq!(
            format_utc_timestamp(1_786_579_200_123),
            "2026-08-13T00:00:00.123Z"
        );
    }
}
