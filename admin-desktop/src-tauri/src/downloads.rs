use std::{
    collections::VecDeque,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
    time::Duration,
};
use url::Url;

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Request {
    pub project_id: String,
    pub release_id: String,
    pub platform: String,
    pub file_name: String,
}

impl Request {
    pub fn validate(&self) -> Result<(), String> {
        let id = self.project_id.as_bytes();
        if id.is_empty()
            || id.len() > 64
            || !id[0].is_ascii_lowercase() && !id[0].is_ascii_digit()
            || !id.iter().all(|b| {
                b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'.' | b'_' | b'-')
            })
        {
            return Err("部署包项目标识无效".into());
        }
        if self.release_id.is_empty()
            || self.release_id.len() > 200
            || self.release_id.chars().any(char::is_control)
        {
            return Err("部署包版本标识无效".into());
        }
        if !matches!(self.platform.as_str(), "windows-x64" | "linux-x64") {
            return Err("部署包平台无效".into());
        }
        Ok(())
    }

    pub fn suggested_name(&self) -> String {
        let name: String = self
            .file_name
            .chars()
            .take(180)
            .map(|c| {
                if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        if name.is_empty() || name.trim_matches('.').is_empty() {
            return "dreamingfish-player-deployment.zip".into();
        }
        if name.to_ascii_lowercase().ends_with(".zip") {
            name
        } else {
            format!("{name}.zip")
        }
    }
}

#[derive(Clone, serde::Serialize)]
pub struct Saved {
    pub id: String,
    pub path: String,
    pub size: u64,
}

#[derive(Default)]
pub struct Downloads {
    busy: AtomicBool,
    saved: Mutex<VecDeque<(String, PathBuf)>>,
}

pub struct DownloadGuard<'a>(&'a AtomicBool);
impl Drop for DownloadGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

impl Downloads {
    pub fn begin(&self) -> Result<DownloadGuard<'_>, String> {
        self.busy
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .map_err(|_| "请先完成当前部署包的保存".to_string())?;
        Ok(DownloadGuard(&self.busy))
    }

    pub fn remember(&self, path: PathBuf, size: u64) -> Result<Saved, String> {
        let id = uuid::Uuid::new_v4().to_string();
        let mut saved = self.saved.lock().map_err(|e| e.to_string())?;
        saved.push_back((id.clone(), path.clone()));
        while saved.len() > 32 {
            saved.pop_front();
        }
        Ok(Saved {
            id,
            path: path.to_string_lossy().into(),
            size,
        })
    }

    pub fn parent_for(&self, id: &str) -> Result<PathBuf, String> {
        let saved = self.saved.lock().map_err(|e| e.to_string())?;
        let path = saved
            .iter()
            .find(|(receipt, _)| receipt == id)
            .map(|(_, path)| path)
            .ok_or("保存记录已过期，请按显示的路径打开文件夹")?;
        path.parent()
            .map(Path::to_path_buf)
            .ok_or_else(|| "部署包保存目录无效".into())
    }
}

struct PartialFile {
    path: PathBuf,
    file: Option<File>,
}

impl PartialFile {
    fn create(destination: &Path) -> Result<Self, String> {
        let parent = destination.parent().ok_or("请选择完整的部署包保存路径")?;
        let path = parent.join(format!(".dfs-deployment-{}.part", uuid::Uuid::new_v4()));
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .map_err(|e| format!("无法在所选文件夹保存部署包：{e}"))?;
        Ok(Self {
            path,
            file: Some(file),
        })
    }

    fn commit(mut self, destination: &Path) -> Result<(), String> {
        self.file
            .as_ref()
            .unwrap()
            .sync_all()
            .map_err(|e| format!("部署包写入磁盘失败：{e}"))?;
        drop(self.file.take());
        fs::rename(&self.path, destination)
            .map_err(|e| format!("无法保存部署包到 {}：{e}", destination.display()))
    }
}

impl Drop for PartialFile {
    fn drop(&mut self) {
        drop(self.file.take());
        let _ = fs::remove_file(&self.path);
    }
}

/// Use the authenticated loopback proxy so local, HTTPS and SSH connections share
/// the same upstream session. Only this fixed deployment route can be requested.
pub fn save(
    base: &Url,
    desktop_token: &str,
    session_token: &str,
    request: &Request,
    destination: &Path,
    still_current: impl Fn() -> bool,
) -> Result<u64, String> {
    request.validate()?;
    if !destination.is_absolute()
        || !destination
            .extension()
            .and_then(|ext| ext.to_str())
            .is_some_and(|ext| ext.eq_ignore_ascii_case("zip"))
    {
        return Err("请选择以 .zip 结尾的完整部署包保存路径".into());
    }
    if !still_current() {
        return Err("管理连接已切换，请重新保存部署包".into());
    }
    let mut target = base.clone();
    target.set_path("/api/projects/");
    target
        .path_segments_mut()
        .map_err(|_| "部署包连接地址无效")?
        .pop_if_empty()
        .push(&request.project_id)
        .push("deployment")
        .push("download");
    let client = reqwest::blocking::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(86400))
        .build()
        .map_err(|e| e.to_string())?;
    let mut response = client
        .post(target)
        .header("X-DFS-Desktop", desktop_token)
        .header("X-DFS-Token", session_token)
        .header("Origin", base.origin().ascii_serialization())
        .header("Content-Type", "application/json")
        .body(
            serde_json::json!({"releaseId":request.release_id,"platform":request.platform})
                .to_string(),
        )
        .send()
        .map_err(|e| format!("部署包下载连接失败，请检查连接后重试：{e}"))?;
    if response.status() != reqwest::StatusCode::OK {
        let status = response.status();
        let mut body = String::new();
        let _ = response.take(64 * 1024).read_to_string(&mut body);
        let message = serde_json::from_str::<serde_json::Value>(&body)
            .ok()
            .and_then(|value| {
                value
                    .get("message")
                    .and_then(|v| v.as_str())
                    .map(str::to_owned)
            });
        return Err(message.unwrap_or_else(|| format!("部署包生成失败 ({status})")));
    }
    if response
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(';').next())
        .map(str::trim)
        != Some("application/zip")
    {
        return Err("管理端没有返回 ZIP 部署包，请检查连接后重试".into());
    }
    let expected = response.content_length();
    let mut signature = [0u8; 4];
    response
        .read_exact(&mut signature)
        .map_err(|e| format!("部署包没有完整下载：{e}"))?;
    if signature != *b"PK\x03\x04" && signature != *b"PK\x05\x06" {
        return Err("下载内容不是有效的 ZIP 部署包，未保存此次内容".into());
    }
    let mut partial = PartialFile::create(destination)?;
    let file = partial.file.as_mut().unwrap();
    file.write_all(&signature)
        .map_err(|e| format!("部署包写入失败：{e}"))?;
    let mut size = 4u64;
    let mut buffer = [0u8; 128 * 1024];
    loop {
        if !still_current() {
            return Err("管理连接已切换，未保存此次部署包".into());
        }
        let count = response
            .read(&mut buffer)
            .map_err(|e| format!("部署包下载中断，未保存此次内容：{e}"))?;
        if count == 0 {
            break;
        }
        file.write_all(&buffer[..count])
            .map_err(|e| format!("部署包写入失败：{e}"))?;
        size += count as u64;
    }
    if expected.is_some_and(|expected| size != expected) {
        return Err("部署包没有完整下载，未保存此次内容，请重试".into());
    }
    if !still_current() {
        return Err("管理连接已切换，未保存此次部署包".into());
    }
    partial.commit(destination)?;
    Ok(size)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::workbench::Workbench;
    use std::{io::Cursor, sync::Arc};
    use tiny_http::{Header, Response, Server};

    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root =
                std::env::temp_dir().join(format!("dfs-download-test-{}", uuid::Uuid::new_v4()));
            fs::create_dir_all(&root).unwrap();
            Self(root)
        }
        fn target(&self) -> PathBuf {
            self.0.join("deployment.zip")
        }
        fn no_partial_files(&self) {
            assert!(fs::read_dir(&self.0).unwrap().all(|e| !e
                .unwrap()
                .file_name()
                .to_string_lossy()
                .ends_with(".part")));
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn request() -> Request {
        Request {
            project_id: "demo".into(),
            release_id: "release".into(),
            platform: "windows-x64".into(),
            file_name: "demo.zip".into(),
        }
    }
    fn header(name: &str, value: &str) -> Header {
        Header::from_bytes(name, value).unwrap()
    }
    fn zip(bytes: Vec<u8>) -> Response<Cursor<Vec<u8>>> {
        Response::from_data(bytes).with_header(header("Content-Type", "application/zip"))
    }

    #[test]
    fn native_save_uses_the_same_authenticated_proxy_session_and_atomically_replaces_the_file() {
        let fixture = Fixture::new();
        fs::write(fixture.target(), b"old valid deployment").unwrap();
        let upstream = Server::http("127.0.0.1:0").unwrap();
        let base = Url::parse(&format!("http://{}/", upstream.server_addr())).unwrap();
        let proxy = Workbench::start(base, Arc::new(|_| None)).unwrap();
        let payload = [b"PK\x03\x04".as_slice(), &vec![19u8; 2 * 1024 * 1024]].concat();
        let expected = payload.clone();
        let worker = std::thread::spawn(move || {
            let login = upstream
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            assert_eq!(login.url(), "/api/auth/login");
            login
                .respond(Response::from_string("{}").with_header(header(
                    "Set-Cookie",
                    "dfs_session=fixture; Path=/; HttpOnly",
                )))
                .unwrap();
            let mut download = upstream
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap();
            assert_eq!(download.url(), "/api/projects/demo/deployment/download");
            assert!(download.headers().iter().any(
                |h| h.field.equiv("Cookie") && h.value.as_str().contains("dfs_session=fixture")
            ));
            assert!(download
                .headers()
                .iter()
                .any(|h| h.field.equiv("X-DFS-Token") && h.value.as_str() == "csrf-token"));
            let mut body = String::new();
            download.as_reader().read_to_string(&mut body).unwrap();
            let body: serde_json::Value = serde_json::from_str(&body).unwrap();
            assert_eq!(body["releaseId"], "release");
            assert_eq!(body["platform"], "windows-x64");
            download.respond(zip(payload)).unwrap();
        });
        let login = reqwest::blocking::Client::new()
            .post(proxy.url.join("api/auth/login").unwrap())
            .header("X-DFS-Desktop", &proxy.token)
            .body("{}")
            .send()
            .unwrap();
        assert_eq!(login.status(), 200);
        drop(login);
        let size = save(
            &proxy.url,
            &proxy.token,
            "csrf-token",
            &request(),
            &fixture.target(),
            || true,
        )
        .unwrap();
        assert_eq!(size, expected.len() as u64);
        assert_eq!(fs::read(fixture.target()).unwrap(), expected);
        fixture.no_partial_files();
        worker.join().unwrap();
    }

    #[test]
    fn interrupted_download_keeps_the_existing_zip_and_removes_its_partial_file() {
        use std::net::TcpListener;
        let fixture = Fixture::new();
        fs::write(fixture.target(), b"original").unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = Url::parse(&format!("http://{}/", listener.local_addr().unwrap())).unwrap();
        let worker = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut data = Vec::new();
            let mut byte = [0u8; 1];
            while !data.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                data.push(byte[0]);
            }
            let headers = String::from_utf8(data).unwrap();
            let length: usize = headers
                .lines()
                .find_map(|line| {
                    line.split_once(':')
                        .filter(|(name, _)| name.eq_ignore_ascii_case("content-length"))
                        .map(|(_, value)| value.trim().parse().unwrap())
                })
                .unwrap();
            stream.read_exact(&mut vec![0u8; length]).unwrap();
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: 1000\r\nConnection: close\r\n\r\nPK\x03\x04partial").unwrap();
        });
        let error = save(
            &base,
            "nonce",
            "csrf",
            &request(),
            &fixture.target(),
            || true,
        )
        .unwrap_err();
        assert!(error.contains("下载中断"), "{error}");
        assert_eq!(fs::read(fixture.target()).unwrap(), b"original");
        fixture.no_partial_files();
        worker.join().unwrap();
    }

    #[test]
    fn generation_or_disk_commit_failures_preserve_existing_content() {
        for changed in [false, true] {
            let fixture = Fixture::new();
            if changed {
                fs::write(fixture.target(), b"original").unwrap();
            } else {
                fs::create_dir(fixture.target()).unwrap();
            }
            let upstream = Server::http("127.0.0.1:0").unwrap();
            let base = Url::parse(&format!("http://{}/", upstream.server_addr())).unwrap();
            let worker = std::thread::spawn(move || {
                upstream
                    .recv_timeout(Duration::from_secs(5))
                    .unwrap()
                    .unwrap()
                    .respond(zip(b"PK\x03\x04fixture".to_vec()))
                    .unwrap();
            });
            let calls = std::sync::atomic::AtomicUsize::new(0);
            let result = save(
                &base,
                "nonce",
                "csrf",
                &request(),
                &fixture.target(),
                || !changed || calls.fetch_add(1, Ordering::SeqCst) < 2,
            );
            assert!(result.is_err());
            if changed {
                assert_eq!(fs::read(fixture.target()).unwrap(), b"original");
            } else {
                assert!(fixture.target().is_dir());
            }
            fixture.no_partial_files();
            worker.join().unwrap();
        }
    }

    #[test]
    fn backend_errors_and_non_zip_responses_are_not_saved() {
        for response in [
            Response::from_string("{\"message\":\"未发布玩家端程序\"}").with_status_code(400),
            Response::from_string("<html>login</html>"),
        ] {
            let fixture = Fixture::new();
            let upstream = Server::http("127.0.0.1:0").unwrap();
            let base = Url::parse(&format!("http://{}/", upstream.server_addr())).unwrap();
            let worker = std::thread::spawn(move || {
                upstream
                    .recv_timeout(Duration::from_secs(5))
                    .unwrap()
                    .unwrap()
                    .respond(response)
                    .unwrap();
            });
            assert!(save(
                &base,
                "nonce",
                "csrf",
                &request(),
                &fixture.target(),
                || true
            )
            .is_err());
            assert!(!fixture.target().exists());
            fixture.no_partial_files();
            worker.join().unwrap();
        }
    }

    #[test]
    fn malformed_zip_is_not_saved_even_when_the_server_labels_it_as_zip() {
        let fixture = Fixture::new();
        let upstream = Server::http("127.0.0.1:0").unwrap();
        let base = Url::parse(&format!("http://{}/", upstream.server_addr())).unwrap();
        let worker = std::thread::spawn(move || {
            upstream
                .recv_timeout(Duration::from_secs(5))
                .unwrap()
                .unwrap()
                .respond(zip(b"not a zip".to_vec()))
                .unwrap();
        });
        assert!(save(
            &base,
            "nonce",
            "csrf",
            &request(),
            &fixture.target(),
            || true
        )
        .unwrap_err()
        .contains("有效的 ZIP"));
        assert!(!fixture.target().exists());
        fixture.no_partial_files();
        worker.join().unwrap();
    }

    #[test]
    fn one_native_download_at_a_time_and_only_successful_receipts_can_reveal_folders() {
        let downloads = Downloads::default();
        let busy = downloads.begin().unwrap();
        assert!(downloads.begin().is_err());
        drop(busy);
        assert!(downloads.begin().is_ok());
        let fixture = Fixture::new();
        let first = downloads.remember(fixture.target(), 8).unwrap();
        assert_eq!(downloads.parent_for(&first.id).unwrap(), fixture.0);
        assert!(downloads
            .parent_for(&fixture.target().to_string_lossy())
            .is_err());
        for _ in 0..32 {
            downloads.remember(fixture.target(), 8).unwrap();
        }
        assert!(downloads.parent_for(&first.id).is_err());
    }

    #[test]
    fn download_request_cannot_target_an_arbitrary_url_or_platform() {
        let mut req = request();
        req.project_id = "../secrets".into();
        assert!(req.validate().is_err());
        req = request();
        req.project_id = "demo.v2-pack_1".into();
        assert!(req.validate().is_ok());
        req = request();
        req.platform = "http://other.example/".into();
        assert!(req.validate().is_err());
        req = request();
        req.release_id.clear();
        assert!(req.validate().is_err());
        req = request();
        req.file_name = "../../bad/name".into();
        assert!(!req.suggested_name().contains('/'));
        assert!(req.suggested_name().ends_with(".zip"));
    }
}
