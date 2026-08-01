use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::Mutex;

use tauri::{AppHandle, Emitter, Manager, State};

struct Sidecar {
    child: Child,
    stdin: ChildStdin,
}

#[derive(Default)]
struct SidecarState(Mutex<Option<Sidecar>>);

fn debug_log(message: &str) {
    use std::io::Write;
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(std::env::temp_dir().join("dfs-player-sidecar.log"))
    {
        let _ = writeln!(file, "{}", message);
    }
}

fn exe_dir() -> Result<PathBuf, String> {
    std::env::current_exe()
        .map_err(|error| format!("无法定位程序目录: {error}"))?
        .parent()
        .map(Path::to_path_buf)
        .ok_or_else(|| "无法定位程序目录".to_string())
}

fn java_executable(base: &Path) -> Result<PathBuf, String> {
    if let Ok(value) = std::env::var("DFS_JAVA") {
        if !value.is_empty() && Path::new(&value).is_file() {
            return Ok(PathBuf::from(value));
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
    if let Ok(value) = std::env::var("DFS_SIDECAR_JAR") {
        if !value.is_empty() && Path::new(&value).is_file() {
            return Ok(PathBuf::from(value));
        }
    }
    let candidates = [
        base.join("player-sidecar.jar"),
        base.join("app").join("player-sidecar.jar"),
    ];
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

#[tauri::command]
fn spawn_sidecar(app: AppHandle, state: State<'_, SidecarState>) -> Result<(), String> {
    debug_log("spawn_sidecar invoked");
    {
        let mut guard = state.0.lock().map_err(|_| "更新引擎状态锁定失败")?;
        if guard.as_mut().is_some_and(|sidecar| {
            sidecar.child.try_wait().ok().flatten().is_none()
        }) {
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
        "java={} jar={} args={:?}",
        java.display(),
        jar.display(),
        raw_args
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
        format!(
            "无法启动更新引擎 {}: {error}",
            java.to_string_lossy()
        )
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

    let out_app = app.clone();
    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            match line {
                Ok(line) => {
                    let _ = out_app.emit("sidecar-line", line);
                }
                Err(_) => break,
            }
        }
        let _ = debug_log("sidecar stdout closed");
        let _ = out_app.emit("sidecar-exited", ());
        out_app.exit(0);
    });

    std::thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            match line {
                Ok(line) => {
                    let _ = app.emit("sidecar-error", line);
                }
                Err(_) => break,
            }
        }
    });

    Ok(())
}

#[tauri::command]
fn send_command(state: State<'_, SidecarState>, line: String) -> Result<(), String> {
    let mut guard = state.0.lock().map_err(|_| "更新引擎状态锁定失败")?;
    let sidecar = guard
        .as_mut()
        .ok_or_else(|| "更新引擎尚未启动")?;
    sidecar
        .stdin
        .write_all(line.as_bytes())
        .and_then(|_| sidecar.stdin.write_all(b"\n"))
        .and_then(|_| sidecar.stdin.flush())
        .map_err(|error| format!("无法写入更新引擎: {error}"))
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
        .invoke_handler(tauri::generate_handler![
            spawn_sidecar,
            send_command,
            window_minimize,
            window_toggle_maximize,
            window_close,
            window_start_drag,
            open_path,
            open_external,
            quit_application
        ])
        .build(tauri::generate_context!())
        .expect("error while building DreamingFish Updater");

    app.run(|app_handle, event| {
        if let tauri::RunEvent::Exit = event {
            if let Some(state) = app_handle.try_state::<SidecarState>() {
                if let Ok(mut guard) = state.0.lock() {
                    if let Some(sidecar) = guard.as_mut() {
                        let _ = sidecar.child.kill();
                        let _ = sidecar.child.wait();
                    }
                }
            }
        }
    });
}
