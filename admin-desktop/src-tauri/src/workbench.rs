use reqwest::blocking::{Body, Client};
use std::{io::{self, Read}, sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}}, time::Duration};
use tiny_http::{Header, Request, Response, Server, StatusCode};
use url::Url;

type Assets = Arc<dyn Fn(&str) -> Option<(Vec<u8>, String)> + Send + Sync>;

pub struct Workbench {
    server: Arc<Server>,
    stopped: Arc<AtomicBool>,
    pub url: Url,
    pub token: String,
}

fn header(request: &Request, name: &'static str) -> Option<String> {
    request.headers().iter().find(|h| h.field.equiv(name)).map(|h| h.value.as_str().to_owned())
}
fn http_header(name: &str, value: &str) -> Header { Header::from_bytes(name, value).expect("static HTTP header") }
fn error(request: Request, status: u16, message: &str) {
    let body = serde_json::json!({"error":"desktop_connection","message":message}).to_string();
    let _ = request.respond(Response::from_string(body).with_status_code(status)
        .with_header(http_header("Content-Type", "application/json; charset=utf-8"))
        .with_header(http_header("Cache-Control", "no-store")));
}

fn permitted_target(base: &Url, raw: &str) -> Option<Url> {
    if !raw.starts_with("/api/") || raw.starts_with("//") || raw.contains('\\') { return None; }
    let target = base.join(raw).ok()?;
    if target.origin() != base.origin() || !target.path().starts_with("/api/") { return None; }
    Some(target)
}

fn authorized(request: &Request, local: &Url, token: &str) -> bool {
    if header(request, "Host").as_deref() != Some(local.as_str().trim_start_matches("http://").trim_end_matches('/')) { return false; }
    if let Some(origin) = header(request, "Origin") {
        if origin != local.origin().ascii_serialization() { return false; }
    }
    header(request, "X-DFS-Desktop").as_deref() == Some(token)
}

struct UploadReader(Arc<Mutex<Option<Request>>>);
impl Read for UploadReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut request = self.0.lock().map_err(|_| io::Error::other("request lock poisoned"))?;
        request.as_mut().ok_or_else(|| io::Error::other("request closed"))?.as_reader().read(buf)
    }
}

impl Workbench {
    pub fn start(base: Url, assets: Assets) -> Result<Self, String> {
        let server = Arc::new(Server::http("127.0.0.1:0").map_err(|e| e.to_string())?);
        let address = server.server_addr().to_ip().ok_or("工作区监听地址无效")?;
        let url = Url::parse(&format!("http://127.0.0.1:{}/", address.port())).map_err(|e| e.to_string())?;
        let token = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
        let client = Client::builder().cookie_store(true).redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(15)).timeout(None).build().map_err(|e| e.to_string())?;
        let stopped = Arc::new(AtomicBool::new(false));
        for _ in 0..8 {
            let server = Arc::downgrade(&server); let local = url.clone(); let upstream = base.clone();
            let token = token.clone(); let client = client.clone(); let assets = assets.clone(); let stopped = stopped.clone();
            std::thread::spawn(move || {
                while !stopped.load(Ordering::SeqCst) {
                    let Some(handle) = server.upgrade() else { break; };
                    let received = handle.recv_timeout(Duration::from_millis(200));
                    drop(handle);
                    let request = match received { Ok(Some(r)) => r, Ok(None) => continue, Err(_) => break };
                    if stopped.load(Ordering::SeqCst) { break; }
                    if request.url().starts_with("/api/") {
                        if !authorized(&request, &local, &token) { error(request, 403, "工作区请求来源无效"); continue; }
                        if let Some(target) = permitted_target(&upstream, request.url()) { forward(request, &client, target); }
                        else { error(request, 400, "管理请求地址无效"); }
                    } else {
                        let raw = request.url().split('?').next().unwrap_or("/");
                        let key = if raw == "/" { "workspace.html" } else { raw.trim_start_matches('/') };
                        let allowed = key == "workspace.html" || key.starts_with("assets/") || key.starts_with("icons/") || key.starts_with("player-preview/");
                        if !allowed || key.contains("..") || key.contains('%') || key.contains('\\') { error(request, 404, "页面不存在"); continue; }
                        if let Some((bytes, mime)) = assets(key) {
                            let ancestors = if key.starts_with("player-preview/") { "'self'" } else { "'none'" };
                            let response = Response::from_data(bytes)
                                .with_header(http_header("Content-Type", &mime)).with_header(http_header("Cache-Control", "no-store"))
                                .with_header(http_header("X-Content-Type-Options", "nosniff"))
                                .with_header(http_header("Content-Security-Policy", &format!("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; media-src 'self' blob:; font-src 'self'; connect-src 'self' ipc: http://ipc.localhost; frame-src 'self'; frame-ancestors {ancestors}; object-src 'none'; base-uri 'none'")));
                            let _ = request.respond(response);
                        } else { error(request, 404, "工作区资源不存在，请重新构建桌面端"); }
                    }
                }
            });
        }
        Ok(Self { server, stopped, url, token })
    }
}
impl Drop for Workbench {
    fn drop(&mut self) { self.stopped.store(true, Ordering::SeqCst); self.server.unblock(); }
}

fn forward(request: Request, client: &Client, target: Url) {
    let method = match reqwest::Method::from_bytes(request.method().as_str().as_bytes()) { Ok(m) => m, Err(_) => { error(request, 405, "请求方法无效"); return; } };
    let content_length = request.body_length();
    let mut outbound = client.request(method.clone(), target.clone()).header("Origin", target.origin().ascii_serialization());
    for name in ["Content-Type", "X-DFS-Token", "Range", "Accept"] {
        if let Some(value) = header(&request, name) { outbound = outbound.header(name, value); }
    }
    let shared = Arc::new(Mutex::new(Some(request)));
    if !matches!(method, reqwest::Method::GET | reqwest::Method::HEAD) {
        let reader = UploadReader(shared.clone());
        outbound = outbound.body(match content_length { Some(size) => Body::sized(reader, size as u64), None => Body::new(reader) });
    }
    let result = outbound.send();
    let request = match shared.lock().ok().and_then(|mut r| r.take()) { Some(r) => r, None => return };
    match result {
        Ok(response) => {
            let status = response.status().as_u16();
            let length = response.content_length().and_then(|v| usize::try_from(v).ok());
            let headers = response.headers().iter().filter(|(name,_)| matches!(name.as_str(), "content-type"|"content-disposition"|"content-range"|"accept-ranges"))
                .filter_map(|(name,value)| Header::from_bytes(name.as_str(), value.as_bytes()).ok()).collect();
            let _ = request.respond(Response::new(StatusCode(status), headers, response, length, None));
        }
        Err(_) => error(request, 502, "管理服务连接中断，请检查服务或重新连接"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn api_target_is_bound_to_selected_server() {
        let base = Url::parse("https://admin.example.com/").unwrap();
        assert!(permitted_target(&base, "/api/projects/test/files?path=mods%2Fa.jar").is_some());
        for value in ["https://other.example.com/api/state", "//other.example.com/api/state", "/api/../secrets", "/api\\other", "/other"] { assert!(permitted_target(&base, value).is_none()); }
    }
    #[test]
    fn no_nonce_or_foreign_origin_cannot_access_api() {
        let local = Url::parse("http://127.0.0.1:14567/").unwrap();
        let good = tiny_http::TestRequest::new().with_header(http_header("Host","127.0.0.1:14567")).with_header(http_header("X-DFS-Desktop","private")).into();
        assert!(authorized(&good, &local, "private"));
        assert!(!authorized(&good, &local, "wrong"));
        let foreign = tiny_http::TestRequest::new().with_header(http_header("Host","127.0.0.1:14567")).with_header(http_header("X-DFS-Desktop","private")).with_header(http_header("Origin","https://other.example.com")).into();
        assert!(!authorized(&foreign, &local, "private"));
    }

    fn fixture(upstream: &Server) -> Workbench {
        let address = upstream.server_addr().to_ip().unwrap();
        Workbench::start(Url::parse(&format!("http://{address}/")).unwrap(), Arc::new(|key| {
            (key == "workspace.html").then(|| (b"<main>workspace</main>".to_vec(), "text/html".into()))
        })).unwrap()
    }

    #[test]
    fn api_cookies_stay_in_private_connection_and_csrf_origin_is_upstream() {
        let upstream = Server::http("127.0.0.1:0").unwrap();
        let base = format!("http://{}", upstream.server_addr());
        let workbench = fixture(&upstream);
        let thread = std::thread::spawn(move || {
            let mut login = upstream.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
            assert_eq!(header(&login,"Origin").as_deref(),Some(base.as_str()));
            assert_eq!(header(&login,"X-DFS-Token").as_deref(),Some("csrf"));
            assert!(header(&login,"Cookie").is_none());
            assert!(header(&login,"X-DFS-Desktop").is_none());
            let mut body = String::new();login.as_reader().read_to_string(&mut body).unwrap();
            assert_eq!(body,"{\"username\":\"fixture\"}");
            login.respond(Response::from_string("{}").with_header(http_header("Set-Cookie","session=private; HttpOnly; Path=/"))).unwrap();
            let state = upstream.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
            assert_eq!(header(&state,"Cookie").as_deref(),Some("session=private"));
            state.respond(Response::from_string("{\"ok\":true}")).unwrap();
        });
        let client = Client::new();
        let login = client.post(workbench.url.join("api/auth/login").unwrap()).header("X-DFS-Desktop",&workbench.token)
            .header("X-DFS-Token","csrf").header("Cookie","session=attacker").body("{\"username\":\"fixture\"}").send().unwrap();
        assert_eq!(login.status(),200);assert!(login.headers().get("Set-Cookie").is_none());
        let result = client.get(workbench.url.join("api/state").unwrap()).header("X-DFS-Desktop",&workbench.token).send().unwrap();
        assert_eq!(result.text().unwrap(),"{\"ok\":true}");thread.join().unwrap();
    }

    #[test]
    fn streaming_binary_upload_and_response_remain_byte_exact() {
        let upstream = Server::http("127.0.0.1:0").unwrap();
        let workbench = fixture(&upstream);
        let payload:Vec<u8> = (0..3*1024*1024).map(|n|(n%251)as u8).collect();
        let expected = payload.clone();
        let thread = std::thread::spawn(move || {
            let mut request = upstream.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
            assert_eq!(request.method().as_str(),"PUT");
            let mut bytes=Vec::new();request.as_reader().read_to_end(&mut bytes).unwrap();assert_eq!(bytes,expected);
            request.respond(Response::from_data(bytes).with_header(http_header("Content-Disposition","attachment; filename=fixture.bin"))).unwrap();
        });
        let response = Client::new().put(workbench.url.join("api/projects/test/files/stage?path=file.bin").unwrap())
            .header("X-DFS-Desktop",&workbench.token).body(payload.clone()).send().unwrap();
        assert_eq!(response.status(),200);assert!(response.headers().contains_key("Content-Disposition"));
        assert_eq!(response.bytes().unwrap().as_ref(),payload.as_slice());thread.join().unwrap();
    }

    #[test]
    fn untrusted_requests_are_rejected_and_disconnect_closes_owned_port() {
        let upstream=Server::http("127.0.0.1:0").unwrap();let workbench=fixture(&upstream);
        let client=Client::new();let endpoint=workbench.url.join("api/state").unwrap();
        assert_eq!(client.get(endpoint.clone()).send().unwrap().status(),403);
        assert_eq!(client.get(endpoint).header("X-DFS-Desktop",&workbench.token).header("Origin","https://foreign.example").send().unwrap().status(),403);
        assert_eq!(client.get(workbench.url.join("private/file").unwrap()).send().unwrap().status(),404);
        let address=workbench.server.server_addr().to_ip().unwrap();drop(workbench);
        let deadline=std::time::Instant::now()+Duration::from_secs(3);
        while std::net::TcpStream::connect_timeout(&address,Duration::from_millis(100)).is_ok(){assert!(std::time::Instant::now()<deadline);std::thread::sleep(Duration::from_millis(50));}
    }
}
