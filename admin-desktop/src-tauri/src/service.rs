use crate::profiles::{Connection, Profile};
use std::{
    fs::{self, OpenOptions},
    io::{BufRead, BufReader, Read, Seek, SeekFrom, Write},
    net::{TcpListener, TcpStream},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::mpsc,
    time::{Duration, Instant},
};
use tauri::Manager;
use url::Url;

pub struct ConnectionResult {
    pub url: Url,
    pub service: OwnedService,
}
pub struct OwnedService {
    child: Option<Child>,
    messages: Option<mpsc::Receiver<serde_json::Value>>,
    java: bool,
    _job: Option<crate::job::Job>,
}

fn command(program: impl AsRef<std::ffi::OsStr>) -> Command {
    let mut cmd = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    cmd
}

fn logs(root: &Path, profile: &Profile) -> Result<(PathBuf, std::fs::File), String> {
    let path = root.join("logs").join(format!("{}.log", profile.id));
    let log = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .map_err(|e| e.to_string())?;
    Ok((path, log))
}

fn core(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let mut candidates = vec![app
        .path()
        .resource_dir()
        .map_err(|e| e.to_string())?
        .join("core")];
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            candidates.push(parent.join("core"));
        }
    }
    if cfg!(debug_assertions) {
        candidates.push(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources/core"));
    }
    candidates
        .into_iter()
        .map(|path| dunce::simplified(&path).to_path_buf())
        .find(|p| {
            p.join("dfs-admin.jar").is_file()
                && p.join("support/bootstrap-agent.jar").is_file()
                && java_exe(p).is_ok()
        })
        .ok_or("未找到内置管理核心，请使用完整桌面发行包".into())
}

fn java_exe(core: &Path) -> Result<PathBuf, String> {
    let path = core.join(if cfg!(windows) {
        "runtime/bin/java.exe"
    } else {
        "runtime/bin/java"
    });
    if path.is_file() {
        Ok(path)
    } else {
        Err("未找到内置 Java 运行环境".into())
    }
}

fn tail(path: &Path) -> String {
    let read_tail = || -> std::io::Result<String> {
        let mut file = fs::File::open(path)?;
        let start = file.metadata()?.len().saturating_sub(4000);
        file.seek(SeekFrom::Start(start))?;
        let mut bytes = Vec::new();
        file.take(4000).read_to_end(&mut bytes)?;
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    };
    read_tail().unwrap_or_default()
}

/// The management home receives project keys and releases, so it must not be a folder of unrelated files.
pub fn require_management_home(home: &Path) -> Result<(), String> {
    if home.join("management-settings.json").is_file()
        || home.join("data").join("management.db").is_file()
    {
        return Ok(());
    }
    // A home opened before but never saved holds only the service's own files, such as its lock.
    const OWN: [&str; 4] = ["management-settings.json", "management-web-auth.json", "desktop-backend.lock", "data"];
    let occupied = fs::read_dir(home).is_ok_and(|entries| {
        entries.flatten().any(|e| !OWN.iter().any(|own| e.file_name().to_string_lossy().eq_ignore_ascii_case(own)))
    });
    if occupied {
        return Err(format!(
            "“{}”里已有其他文件。管理数据目录会写入项目记录、签名密钥和发布文件，请选择一个空文件夹，或以前用过的管理端目录。",
            home.display()
        ));
    }
    Ok(())
}

fn ready_url(message: &serde_json::Value, child_id: u32) -> Result<Url, String> {
    if message["type"] != "ready"
        || message["version"] != "0.2.8"
        || message["pid"].as_u64() != Some(child_id.into())
    {
        return Err("管理核心就绪信息无效".into());
    }
    let port = message["port"]
        .as_u64()
        .filter(|p| *p > 0 && *p <= 65535)
        .ok_or("管理核心端口无效")?;
    let url = Url::parse(message["url"].as_str().ok_or("管理核心地址缺失")?)
        .map_err(|e| e.to_string())?;
    if url.as_str() != format!("http://127.0.0.1:{port}/") {
        return Err("管理核心未使用预期的本机地址".into());
    }
    Ok(url)
}

impl OwnedService {
    pub fn ended(&mut self) -> Result<bool, String> {
        match self.child.as_mut() {
            Some(child) => child
                .try_wait()
                .map(|v| v.is_some())
                .map_err(|e| e.to_string()),
            None => Ok(false), // A direct remote address has no owned child.
        }
    }

    pub fn stop(&mut self) -> Result<(), String> {
        let Some(child) = self.child.as_mut() else {
            return Ok(());
        };
        if child.try_wait().map_err(|e| e.to_string())?.is_some() {
            self.child = None;
            return Ok(());
        }
        if self.java {
            if let Some(input) = child.stdin.as_mut() {
                input
                    .write_all(b"{\"command\":\"shutdown\"}\n")
                    .map_err(|e| e.to_string())?;
                input.flush().map_err(|e| e.to_string())?;
            }
            let until = Instant::now() + Duration::from_secs(16);
            while Instant::now() < until {
                if let Some(messages) = &self.messages {
                    while let Ok(msg) = messages.try_recv() {
                        if msg["type"] == "busy" {
                            return Err("管理端正在处理文件操作，请稍后断开或退出".into());
                        }
                    }
                }
                if child.try_wait().map_err(|e| e.to_string())?.is_some() {
                    self.child = None;
                    return Ok(());
                }
                std::thread::sleep(Duration::from_millis(50));
            }
        }
        child.kill().map_err(|e| e.to_string())?;
        child.wait().map_err(|e| e.to_string())?;
        self.child = None;
        Ok(())
    }
}

impl Drop for OwnedService {
    fn drop(&mut self) {
        if self.stop().is_err() {
            if let Some(child) = self.child.as_mut() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

fn java_heap(value:Option<&str>)->Result<Vec<String>,String> {
    let mb=value.unwrap_or("512").parse::<u32>().map_err(|_|"DFS_JAVA_HEAP_MB 必须是 128–16384 的整数")?;
    if !(128..=16384).contains(&mb){return Err("DFS_JAVA_HEAP_MB 必须是 128–16384 的整数".into());}
    Ok(vec!["-Xms64m".into(),format!("-Xmx{mb}m")])
}

pub fn connect(
    app: &tauri::AppHandle,
    root: &Path,
    profile: &Profile,
    cancelled: impl Fn() -> bool,
) -> Result<ConnectionResult, String> {
    match &profile.connection {
        Connection::Remote { url } => Ok(ConnectionResult {
            url: crate::profiles::remote_url(url)?,
            service: OwnedService {
                child: None,
                messages: None,
                java: false,
                _job: None,
            },
        }),
        Connection::Local { home } => {
            if profile.relocation_required { return Err(format!("旧连接目录身份需确认：{}。请在连接页重新选择实际管理数据目录后保存。", home)); }
            let home = crate::profiles::resolve_home(root, home)?;
            if !home.is_dir() { return Err(format!("已保存的管理目录 {} 不存在，请重新选择目录，避免创建空项目。", home.display())); }
            require_management_home(&home)?;
            fs::create_dir_all(&home).map_err(|e| format!("无法打开管理数据目录：{e}"))?;
            let packaged = core(app)?;
            let (path, mut log) = logs(root, profile)?;
            let _ = writeln!(log, "Management core: {}", packaged.display());
            let mut child = command(java_exe(&packaged)?)
                .args(java_heap(std::env::var("DFS_JAVA_HEAP_MB").ok().as_deref())?)
                .arg("-Dfile.encoding=UTF-8")
                .arg("-Dstdin.encoding=UTF-8")
                .arg("-Dstdout.encoding=UTF-8")
                .arg("-Dstderr.encoding=UTF-8")
                .arg(format!("-Ddfs.home={}", home.display()))
                .arg(format!(
                    "-Ddfs.bootstrapAgent={}",
                    packaged.join("support/bootstrap-agent.jar").display()
                ))
                .args(["-jar"])
                .arg(packaged.join("dfs-admin.jar"))
                .arg("desktop")
                .current_dir(&home)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(log)
                .spawn()
                .map_err(|e| format!("无法启动本机管理端：{e}"))?;
            let job = crate::job::Job::own(&mut child)?;
            let output = child.stdout.take().ok_or("管理核心输出通道不可用")?;
            let (tx, rx) = mpsc::channel();
            std::thread::spawn(move || {
                for line in BufReader::new(output).lines().map_while(Result::ok) {
                    if line.len() > 65536 {
                        break;
                    }
                    if let Ok(value) = serde_json::from_str::<serde_json::Value>(&line) {
                        if tx.send(value).is_err() {
                            break;
                        }
                    }
                }
            });
            let mut owned = OwnedService {
                child: Some(child),
                messages: Some(rx),
                java: true,
                _job: Some(job),
            };
            let deadline = Instant::now() + Duration::from_secs(40);
            while Instant::now() < deadline {
                if cancelled() {
                    return Err("已取消打开工作区".into());
                }
                match owned
                    .messages
                    .as_ref()
                    .unwrap()
                    .recv_timeout(Duration::from_millis(100))
                {
                    Ok(message) => {
                        let url = ready_url(&message, owned.child.as_ref().unwrap().id())?;
                        if let Some(expected)=option_env!("DFS_BUILD_ID"){
                            if message["buildId"].as_str()!=Some(expected){return Err("内置管理核心与桌面构建标识不一致，请用同一轮产物重新打包".into());}
                        }
                        return Ok(ConnectionResult {
                            url,
                            service: owned,
                        });
                    }
                    Err(mpsc::RecvTimeoutError::Disconnected) => {
                        return Err(format!("本机管理端启动失败。{}", tail(&path)))
                    }
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                }
                if owned
                    .child
                    .as_mut()
                    .unwrap()
                    .try_wait()
                    .map_err(|e| e.to_string())?
                    .is_some()
                {
                    return Err(format!("本机管理端已退出。{}", tail(&path)));
                }
            }
            Err("启动本机管理端超时，请查看运行日志".into())
        }
        Connection::Ssh {
            target,
            remote_port,
        } => {
            let target = crate::profiles::ssh_target(target)?;
            let socket = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
            let port = socket.local_addr().map_err(|e| e.to_string())?.port();
            drop(socket);
            let (path, log) = logs(root, profile)?;
            #[cfg(windows)]
            let ssh = PathBuf::from(
                std::env::var_os("SystemRoot").unwrap_or_else(|| "C:\\Windows".into()),
            )
            .join("System32/OpenSSH/ssh.exe");
            #[cfg(not(windows))]
            let ssh = PathBuf::from("ssh");
            let mut child = command(ssh)
                .args([
                    "-N",
                    "-o",
                    "BatchMode=yes",
                    "-o",
                    "StrictHostKeyChecking=yes",
                    "-o",
                    "ControlMaster=no",
                    "-o",
                    "ControlPath=none",
                    "-o",
                    "ControlPersist=no",
                    "-o",
                    "ForkAfterAuthentication=no",
                    "-o",
                    "ExitOnForwardFailure=yes",
                    "-o",
                    "ConnectTimeout=12",
                    "-o",
                    "ServerAliveInterval=30",
                    "-o",
                    "ServerAliveCountMax=3",
                    "-L",
                ])
                .arg(format!("127.0.0.1:{port}:127.0.0.1:{remote_port}"))
                .arg("--")
                .arg(target)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(log)
                .spawn()
                .map_err(|e| format!("无法启动系统 OpenSSH：{e}"))?;
            let job = crate::job::Job::own(&mut child)?;
            let mut owned = OwnedService {
                child: Some(child),
                messages: None,
                java: false,
                _job: Some(job),
            };
            let deadline = Instant::now() + Duration::from_secs(30);
            while Instant::now() < deadline {
                if cancelled() {
                    return Err("已取消 SSH 连接".into());
                }
                if owned
                    .child
                    .as_mut()
                    .unwrap()
                    .try_wait()
                    .map_err(|e| e.to_string())?
                    .is_some()
                {
                    return Err(format!(
                        "SSH 连接失败，请检查已有配置和认证代理。{}",
                        tail(&path)
                    ));
                }
                if forwarded_admin(port) {
                    return Ok(ConnectionResult {
                        url: Url::parse(&format!("http://127.0.0.1:{port}/")).unwrap(),
                        service: owned,
                    });
                }
                std::thread::sleep(Duration::from_millis(150));
            }
            Err("SSH 已连接，但服务器管理端没有响应，请检查管理端口".into())
        }
    }
}

fn forwarded_admin(port: u16) -> bool {
    let Ok(mut stream) = TcpStream::connect_timeout(
        &format!("127.0.0.1:{port}").parse().unwrap(),
        Duration::from_millis(400),
    ) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(1)));
    if write!(
        stream,
        "GET /api/auth/status HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
    )
    .is_err()
    {
        return false;
    }
    let mut response = String::new();
    if stream.take(8192).read_to_string(&mut response).is_err() {
        return false;
    }
    response.starts_with("HTTP/1.1 200")
        && response
            .split_once("\r\n\r\n")
            .and_then(|(_, body)| serde_json::from_str::<serde_json::Value>(body).ok())
            .is_some_and(|value| {
                value.get("registered").is_some() && value.get("authenticated").is_some()
            })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn java_heap_is_structured_and_rejects_shell_or_vm_arguments(){
        assert_eq!(java_heap(None).unwrap(),vec!["-Xms64m","-Xmx512m"]);
        for value in ["0","127","16385","512 -javaagent:bad","512&bad","", "-Xmx1g"]{assert!(java_heap(Some(value)).is_err());}
    }
    #[test]
    fn only_the_owned_process_can_report_loopback_ready() {
        let good = serde_json::json!({"type":"ready","version":"0.2.8","pid":42,"port":20080,"url":"http://127.0.0.1:20080/"});
        assert!(ready_url(&good, 42).is_ok());
        assert!(ready_url(&good, 43).is_err());
        let mut bad = good.clone();
        bad["url"] = serde_json::json!("http://example.com:20080/");
        assert!(ready_url(&bad, 42).is_err());
        bad = good;
        bad["port"] = serde_json::json!(0);
        assert!(ready_url(&bad, 42).is_err());
    }

    #[test]
    fn management_home_must_be_empty_or_already_managed() {
        let root = std::env::temp_dir().join(format!("dfs-home-check-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        assert!(require_management_home(&root.join("missing")).is_ok());
        let downloads = root.join("downloads");
        fs::create_dir_all(&downloads).unwrap();
        assert!(require_management_home(&downloads).is_ok());
        fs::write(downloads.join("setup.exe"), b"x").unwrap();
        assert!(require_management_home(&downloads).is_err());
        fs::write(downloads.join("management-settings.json"), b"{}").unwrap();
        assert!(require_management_home(&downloads).is_ok());
        let reopened = root.join("reopened");
        fs::create_dir_all(reopened.join("data")).unwrap();
        fs::write(reopened.join("desktop-backend.lock"), b"").unwrap();
        fs::write(reopened.join("management-web-auth.json"), b"{}").unwrap();
        assert!(require_management_home(&reopened).is_ok());
        fs::write(reopened.join("notes.txt"), b"x").unwrap();
        assert!(require_management_home(&reopened).is_err());
        let legacy = root.join("legacy");
        fs::create_dir_all(legacy.join("data")).unwrap();
        fs::write(legacy.join("data").join("management.db"), b"").unwrap();
        fs::write(legacy.join("dfs-admin.jar"), b"").unwrap();
        assert!(require_management_home(&legacy).is_ok());
        let _ = fs::remove_dir_all(&root);
    }
}
