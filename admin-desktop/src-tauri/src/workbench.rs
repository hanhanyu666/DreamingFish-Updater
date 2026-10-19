use bytes::Bytes;
use futures_util::stream;
use http_body_util::{BodyExt, Full, StreamBody, combinators::UnsyncBoxBody};
use hyper::{body::{Frame,Incoming}, Request as HttpRequest, Response as HttpResponse};
use hyper_util::rt::{TokioIo,TokioTimer};
use std::{convert::Infallible, io, sync::{Arc,Mutex,atomic::{AtomicBool,AtomicUsize,AtomicU64,Ordering}}, time::{Duration,Instant}};
use tokio::sync::{watch,Semaphore};
use url::Url;

#[cfg(test)]
use reqwest::blocking::Client;
#[cfg(test)]
use tiny_http::{Header,Request,Response,Server};

type Assets=Arc<dyn Fn(&str)->Option<(Vec<u8>,String)>+Send+Sync>;
type ProxyError=Box<dyn std::error::Error+Send+Sync>;
type ProxyBody=UnsyncBoxBody<Bytes,ProxyError>;
#[derive(Default)]
pub struct Metrics { pub active:AtomicUsize,pub rejected:AtomicU64,pub stalled:AtomicU64 }
#[derive(Clone,Copy)]
struct Deadlines { headers:Duration,stall:Duration,total:Duration }
impl Deadlines {
    fn for_path(path:&str)->Self {
        let long=["/stage","/upload","/scan","/publish","/backup","/distribution","/deployment","/program","/instance","/commit","/check-imports","/undo","/restore","/remove-batch"].iter().any(|p|path.contains(p));
        if long { Self {headers:Duration::from_secs(1200),stall:Duration::from_secs(60),total:Duration::from_secs(std::env::var("DFS_PROXY_TRANSFER_SECONDS").ok().and_then(|v|v.parse::<u64>().ok()).unwrap_or(3600))} }
        else { Self {headers:Duration::from_secs(30),stall:Duration::from_secs(30),total:Duration::from_secs(90)} }
    }
}
#[derive(Default)]
struct WriteProgress { blocked:AtomicBool,since:Mutex<Option<Instant>> }
struct TrackedSocket {socket:tokio::net::TcpStream,progress:Arc<WriteProgress>}
impl tokio::io::AsyncRead for TrackedSocket {
 fn poll_read(mut self:std::pin::Pin<&mut Self>,cx:&mut std::task::Context<'_>,buf:&mut tokio::io::ReadBuf<'_>)->std::task::Poll<io::Result<()>>{std::pin::Pin::new(&mut self.socket).poll_read(cx,buf)}
}
impl tokio::io::AsyncWrite for TrackedSocket {
 fn poll_write(mut self:std::pin::Pin<&mut Self>,cx:&mut std::task::Context<'_>,buf:&[u8])->std::task::Poll<io::Result<usize>> {
  let result=std::pin::Pin::new(&mut self.socket).poll_write(cx,buf);
  if result.is_pending(){if !self.progress.blocked.swap(true,Ordering::Relaxed){*self.progress.since.lock().unwrap()=Some(Instant::now());}}
  else{self.progress.blocked.store(false,Ordering::Relaxed);*self.progress.since.lock().unwrap()=None;}
  result
 }
 fn poll_flush(mut self:std::pin::Pin<&mut Self>,cx:&mut std::task::Context<'_>)->std::task::Poll<io::Result<()>>{std::pin::Pin::new(&mut self.socket).poll_flush(cx)}
 fn poll_shutdown(mut self:std::pin::Pin<&mut Self>,cx:&mut std::task::Context<'_>)->std::task::Poll<io::Result<()>>{std::pin::Pin::new(&mut self.socket).poll_shutdown(cx)}
}
#[derive(serde::Serialize)]
#[serde(rename_all="camelCase")]
pub struct ConnectionMetrics {active:usize,rejected:u64,stalled:u64,request_limit:usize,connection_limit:usize}
pub struct Workbench {
    stop:watch::Sender<bool>,
    worker:Option<std::thread::JoinHandle<()>>,
    pub url:Url,
    pub token:String,
    pub metrics:Arc<Metrics>,
}
fn permitted_target(base:&Url,raw:&str)->Option<Url> {
    if !raw.starts_with("/api/")||raw.starts_with("//")||raw.contains('\\'){return None;}
    let target=base.join(raw).ok()?;
    if target.origin()!=base.origin()||!target.path().starts_with("/api/"){return None;}
    Some(target)
}
fn authorized_headers(headers:&hyper::HeaderMap,local:&Url,token:&str)->bool {
    let text=|name:&str|headers.get(name).and_then(|h|h.to_str().ok());
    if text("host")!=Some(local.as_str().trim_start_matches("http://").trim_end_matches('/')){return false;}
    if let Some(origin)=text("origin"){if origin!=local.origin().ascii_serialization(){return false;}}
    text("x-dfs-desktop")==Some(token)
}
#[cfg(test)]
fn http_header(name:&str,value:&str)->Header{Header::from_bytes(name,value).unwrap()}
#[cfg(test)]
fn header(request:&Request,name:&'static str)->Option<String>{request.headers().iter().find(|h|h.field.equiv(name)).map(|h|h.value.as_str().to_owned())}
#[cfg(test)]
fn authorized(request:&Request,local:&Url,token:&str)->bool {
    let mut headers=hyper::HeaderMap::new();
    for name in ["Host","Origin","X-DFS-Desktop"]{if let Some(value)=header(request,name){headers.insert(hyper::header::HeaderName::from_bytes(name.as_bytes()).unwrap(),value.parse().unwrap());}}
    authorized_headers(&headers,local,token)
}
fn data(bytes:impl Into<Bytes>)->ProxyBody {Full::new(bytes.into()).map_err(|never|match never{}).boxed_unsync()}
fn error(status:u16,message:&str)->HttpResponse<ProxyBody> {
    HttpResponse::builder().status(status).header("Content-Type","application/json; charset=utf-8").header("Cache-Control","no-store").header("Connection","close")
        .body(data(serde_json::json!({"error":"desktop_connection","message":message}).to_string())).unwrap()
}
impl Workbench {
    pub fn statistics(&self)->ConnectionMetrics {ConnectionMetrics {active:self.metrics.active.load(Ordering::Relaxed),rejected:self.metrics.rejected.load(Ordering::Relaxed),stalled:self.metrics.stalled.load(Ordering::Relaxed),request_limit:16,connection_limit:128}}
    pub fn start(base:Url,assets:Assets)->Result<Self,String> {
        if let Ok(value)=std::env::var("DFS_PROXY_TRANSFER_SECONDS"){let seconds=value.parse::<u64>().map_err(|_|"DFS_PROXY_TRANSFER_SECONDS 必须为 60–86400 的整数")?;if !(60..=86400).contains(&seconds){return Err("DFS_PROXY_TRANSFER_SECONDS 必须为 60–86400 的整数".into());}}
        Self::start_config(base,assets,None)
    }
    fn start_config(base:Url,assets:Assets,override_deadlines:Option<Deadlines>)->Result<Self,String> {
        let listener=std::net::TcpListener::bind("127.0.0.1:0").map_err(|e|e.to_string())?;
        let address=listener.local_addr().map_err(|e|e.to_string())?;
        listener.set_nonblocking(true).map_err(|e|e.to_string())?;
        let url=Url::parse(&format!("http://{address}/")).map_err(|e|e.to_string())?;
        let token=format!("{}{}",uuid::Uuid::new_v4().simple(),uuid::Uuid::new_v4().simple());
        let (stop,mut stopping)=watch::channel(false);
        let metrics=Arc::new(Metrics::default());
        let worker_metrics=metrics.clone();let local=url.clone();let nonce=token.clone();
        let runtime=tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().thread_name("dfs-proxy-io").build().map_err(|e|e.to_string())?;
        let worker=std::thread::Builder::new().name("dfs-workbench".into()).spawn(move||{
            runtime.block_on(async move {
                let listener=tokio::net::TcpListener::from_std(listener).expect("validated nonblocking listener");
                let client=reqwest::Client::builder().cookie_store(true).redirect(reqwest::redirect::Policy::none())
                    .connect_timeout(Duration::from_secs(15)).pool_max_idle_per_host(8).pool_idle_timeout(Duration::from_secs(30)).build().expect("valid HTTP client");
                let connections=Arc::new(Semaphore::new(128));let requests=Arc::new(Semaphore::new(16));
                let mut tasks=tokio::task::JoinSet::new();
                loop {
                    tokio::select! {
                        biased;
                        _=stopping.changed()=>break,
                        Some(_)=tasks.join_next(),if !tasks.is_empty()=>{},
                        accepted=listener.accept()=>{
                            let Ok((socket,_))=accepted else{break;};
                            let Ok(permit)=connections.clone().try_acquire_owned() else {
                                worker_metrics.rejected.fetch_add(1,Ordering::Relaxed);
                                // No unbounded wait queue or per-rejection task.
                                use tokio::io::AsyncWriteExt;
                                let mut socket=socket;
                                let _=tokio::time::timeout(Duration::from_millis(100),socket.write_all(b"HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\nRetry-After: 2\r\n\r\n")).await;continue;
                            };
                            let (local,base,nonce,client,assets,metrics,requests)=(local.clone(),base.clone(),nonce.clone(),client.clone(),assets.clone(),worker_metrics.clone(),requests.clone());
                            let mut cancel=stopping.clone();
                            tasks.spawn(async move {
                                let _permit=permit;
                                let io_progress=Arc::new(WriteProgress::default());
                                let tracked=TrackedSocket {socket,progress:io_progress.clone()};
                                let stall_metrics=metrics.clone();
                                let service=hyper::service::service_fn(move|request| {
                                    let (local,base,nonce,client,assets,metrics,requests)=(local.clone(),base.clone(),nonce.clone(),client.clone(),assets.clone(),metrics.clone(),requests.clone());
                                    async move {Ok::<_,Infallible>(serve(request,&local,&base,&nonce,client,assets,metrics,requests,override_deadlines).await)}
                                });
                                let mut builder=hyper::server::conn::http1::Builder::new();
                                builder.timer(TokioTimer::new()).header_read_timeout(Duration::from_secs(15)).max_buf_size(32*1024);
                                let connection=builder.serve_connection(TokioIo::new(tracked),service);
                                let stalled=async move {loop {tokio::time::sleep(Duration::from_millis(200)).await;if io_progress.blocked.load(Ordering::Relaxed)&&io_progress.since.lock().unwrap().is_some_and(|t|t.elapsed()>override_deadlines.map(|d|d.stall).unwrap_or(Duration::from_secs(30))){stall_metrics.stalled.fetch_add(1,Ordering::Relaxed);break;}}};
                                tokio::select! {_=cancel.changed()=>{},_=connection=>{},_=stalled=>{}}
                            });
                        }
                    }
                }
                drop(listener);tasks.abort_all();while tasks.join_next().await.is_some(){}
            });
            runtime.shutdown_timeout(Duration::from_secs(2));
        }).map_err(|e|e.to_string())?;
        Ok(Self {stop,worker:Some(worker),url,token,metrics})
    }
}
impl Drop for Workbench {
    fn drop(&mut self){let _=self.stop.send(true);if let Some(worker)=self.worker.take(){let _=worker.join();}}
}
struct RequestPermit { _permit:tokio::sync::OwnedSemaphorePermit,metrics:Arc<Metrics> }
impl Drop for RequestPermit {fn drop(&mut self){self.metrics.active.fetch_sub(1,Ordering::Relaxed);}}
async fn serve(request:HttpRequest<Incoming>,local:&Url,base:&Url,token:&str,client:reqwest::Client,assets:Assets,metrics:Arc<Metrics>,requests:Arc<Semaphore>,override_deadlines:Option<Deadlines>)->HttpResponse<ProxyBody> {
    let raw=request.uri().to_string();
    if matches!(*request.method(),hyper::Method::GET|hyper::Method::HEAD)&&(request.headers().contains_key("transfer-encoding")||request.headers().get("content-length").is_some_and(|value|value!="0")){return error(400,"读取请求不能包含上传正文");}
    if raw.starts_with("/api/") {
        if !authorized_headers(request.headers(),local,token){return error(403,"工作区请求来源无效");}
        let Some(target)=permitted_target(base,&raw) else{return error(400,"管理请求地址无效");};
        let Ok(permit)=requests.try_acquire_owned() else{metrics.rejected.fetch_add(1,Ordering::Relaxed);let mut response=error(503,"工作区请求已满，请稍后重试；写操作请先查询结果");response.headers_mut().insert("Retry-After","2".parse().unwrap());return response;};
        metrics.active.fetch_add(1,Ordering::Relaxed);
        let guard=RequestPermit {_permit:permit,metrics:metrics.clone()};
        forward(request,client,target,metrics,guard,override_deadlines).await
    } else {
        let path=raw.split('?').next().unwrap_or("/");let key=if path=="/"{"workspace.html"}else{path.trim_start_matches('/')};
        let allowed=key=="workspace.html"||key.starts_with("assets/")||key.starts_with("icons/")||key.starts_with("player-preview/");
        if !allowed||key.contains("..")||key.contains('%')||key.contains('\\'){return error(404,"页面不存在");}
        let Some((bytes,mime))=assets(key)else{return error(404,"工作区资源不存在，请重新构建桌面端");};
        let ancestors=if key.starts_with("player-preview/"){ "'self'" }else{ "'none'" };
        HttpResponse::builder().header("Content-Type",mime).header("Cache-Control",if key.starts_with("assets/"){"public, max-age=31536000, immutable"}else{"no-store"})
            .header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy",format!("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; media-src 'self' blob:; font-src 'self'; connect-src 'self' ipc: http://ipc.localhost; frame-src 'self'; frame-ancestors {ancestors}; object-src 'none'; base-uri 'none'"))
            .body(data(bytes)).unwrap()
    }
}
async fn forward(request:HttpRequest<Incoming>,client:reqwest::Client,target:Url,metrics:Arc<Metrics>,guard:RequestPermit,override_deadlines:Option<Deadlines>)->HttpResponse<ProxyBody> {
    let deadlines=override_deadlines.unwrap_or_else(||Deadlines::for_path(target.path()));let started=Instant::now();
    let upload_done=Arc::new(AtomicBool::new(true));let upload_progress=Arc::new(Mutex::new(Instant::now()));
    let mut outbound=client.request(request.method().clone(),target.clone()).header("Origin",target.origin().ascii_serialization());
    for name in ["Content-Type","Content-Length","X-DFS-Token","Range","Accept","Prefer","X-DFS-Request-Id"] {if let Some(value)=request.headers().get(name){outbound=outbound.header(name,value);}}
    if !matches!(*request.method(),hyper::Method::GET|hyper::Method::HEAD) {
        let incoming=request.into_body();let m=metrics.clone();
        upload_done.store(false,Ordering::Relaxed);let completed=upload_done.clone();let progress=upload_progress.clone();
        let stream=stream::try_unfold(incoming,move|mut incoming|{let m=m.clone();let completed=completed.clone();let progress=progress.clone();async move {
            match tokio::time::timeout(deadlines.stall,incoming.frame()).await {
                Ok(Some(Ok(frame)))=>match frame.into_data(){Ok(bytes)=>{*progress.lock().unwrap()=Instant::now();Ok(Some((bytes,incoming)))},Err(_)=>Ok(None)},
                Ok(Some(Err(error)))=>Err(Box::new(error)as ProxyError),Ok(None)=>{completed.store(true,Ordering::Relaxed);*progress.lock().unwrap()=Instant::now();Ok(None)},
                Err(_)=>{m.stalled.fetch_add(1,Ordering::Relaxed);Err(Box::new(io::Error::new(io::ErrorKind::TimedOut,"upload stalled"))as ProxyError)}
            }
        }});
        outbound=outbound.body(reqwest::Body::wrap_stream(stream));
    }
    let header_deadline=async {loop {
        tokio::time::sleep(Duration::from_millis(25)).await;
        let budget=if upload_done.load(Ordering::Relaxed){deadlines.headers}else{deadlines.stall};
        if upload_progress.lock().unwrap().elapsed()>budget {break;}
    }};
    let response=tokio::select! {
        response=outbound.send()=>match response {Ok(response)=>response,Err(_)=>return error(502,"管理连接已中断，写操作结果尚不能确认；请复查原任务")},
        _=header_deadline=>{metrics.stalled.fetch_add(1,Ordering::Relaxed);return error(504,"管理响应或上传已停滞，写操作结果尚不能确认；请复查原任务");},
        _=tokio::time::sleep(deadlines.total)=>return error(504,"请求达到总期限，写操作结果待复查")
    };
    // A large or binary response receives the transfer budget after headers, so a
    // progressing download cannot inherit the short JSON API's 90-second deadline.
    let deadlines=if override_deadlines.is_none()&&(response.content_length().is_some_and(|size|size>256*1024)||response.headers().contains_key("content-disposition")||response.headers().get("content-type").and_then(|value|value.to_str().ok()).is_some_and(|mime|mime.starts_with("audio/")||mime=="application/octet-stream"||mime=="application/zip")){Deadlines{headers:deadlines.headers,stall:Duration::from_secs(60),total:Duration::from_secs(std::env::var("DFS_PROXY_TRANSFER_SECONDS").ok().and_then(|v|v.parse::<u64>().ok()).unwrap_or(3600))}}else{deadlines};
    let mut builder=HttpResponse::builder().status(response.status());
    for name in ["content-type","content-length","content-disposition","content-range","accept-ranges","etag","retry-after"] {if let Some(value)=response.headers().get(name){builder=builder.header(name,value);}}
    builder=builder.header("Cache-Control","no-store");
    let stream=stream::try_unfold((response,guard),move|(mut response,guard)|{let metrics=metrics.clone();async move {
        let remaining=deadlines.total.saturating_sub(started.elapsed()).min(deadlines.stall);
        match tokio::time::timeout(remaining,response.chunk()).await {
            Ok(Ok(Some(bytes)))=>Ok(Some((Frame::data(bytes),(response,guard)))),
            Ok(Ok(None))=>Ok(None),Ok(Err(error))=>Err(Box::new(error)as ProxyError),
            Err(_)=>{metrics.stalled.fetch_add(1,Ordering::Relaxed);Err(Box::new(io::Error::new(io::ErrorKind::TimedOut,"response stalled"))as ProxyError)}
        }
    }});
    builder.body(StreamBody::new(stream).boxed_unsync()).unwrap()
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
    fn async_task_identity_survives_the_proxy_and_the_result_is_queryable() {
        let upstream = Server::http("127.0.0.1:0").unwrap();
        let workbench = fixture(&upstream);
        let id = "00000000-0000-4000-8000-000000000001";
        let worker = std::thread::spawn(move || {
            let mut request = upstream.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
            assert_eq!(header(&request, "Prefer").as_deref(), Some("respond-async"));
            assert_eq!(header(&request, "X-DFS-Request-Id").as_deref(), Some(id));
            assert_eq!(header(&request, "X-DFS-Token").as_deref(), Some("csrf"));
            assert!(header(&request, "X-DFS-Desktop").is_none());
            let mut body = String::new();
            request.as_reader().read_to_string(&mut body).unwrap();
            assert_eq!(body, "{}");
            request.respond(Response::from_string(format!(r#"{{"taskId":"{id}","state":"QUEUED"}}"#))
                .with_status_code(202).with_header(http_header("Content-Type", "application/json"))).unwrap();
            let query = upstream.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
            assert_eq!(query.url(), format!("/api/tasks/{id}"));
            query.respond(Response::from_string(format!(r#"{{"id":"{id}","state":"SUCCEEDED","result":{{"previewId":"scan"}}}}"#))
                .with_header(http_header("Content-Type", "application/json"))).unwrap();
        });
        let client = Client::builder().timeout(Duration::from_secs(5)).build().unwrap();
        let accepted = client.post(workbench.url.join("api/projects/demo/scan").unwrap())
            .header("X-DFS-Desktop", &workbench.token).header("X-DFS-Token", "csrf")
            .header("Prefer", "respond-async").header("X-DFS-Request-Id", id)
            .header("Content-Type", "application/json").body("{}").send().unwrap();
        assert_eq!(accepted.status().as_u16(), 202);
        let body: serde_json::Value = serde_json::from_str(&accepted.text().unwrap()).unwrap();
        assert_eq!(body["taskId"], id);
        let response = client.get(workbench.url.join(&format!("api/tasks/{id}")).unwrap())
            .header("X-DFS-Desktop", &workbench.token).send().unwrap();
        let result: serde_json::Value = serde_json::from_str(&response.text().unwrap()).unwrap();
        assert_eq!(result["state"], "SUCCEEDED");
        worker.join().unwrap();
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
        let address=std::net::SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST),workbench.url.port().unwrap());drop(workbench);
        let deadline=std::time::Instant::now()+Duration::from_secs(3);
        while std::net::TcpStream::connect_timeout(&address,Duration::from_millis(100)).is_ok(){assert!(std::time::Instant::now()<deadline);std::thread::sleep(Duration::from_millis(50));}
    }
    fn timed_fixture(upstream:&Server)->Workbench {
        Workbench::start_config(Url::parse(&format!("http://{}/",upstream.server_addr())).unwrap(),Arc::new(|_|None),Some(Deadlines{headers:Duration::from_millis(150),stall:Duration::from_millis(150),total:Duration::from_secs(5)})).unwrap()
    }
    #[test]
    fn stalled_headers_have_a_deadline_and_report_unknown_write_results() {
        let upstream=Server::http("127.0.0.1:0").unwrap();let workbench=timed_fixture(&upstream);
        let result=Client::new().post(workbench.url.join("api/projects/test/publish").unwrap()).header("X-DFS-Desktop",&workbench.token).body("{}").send().unwrap();
        assert_eq!(result.status(),504);assert!(result.text().unwrap().contains("尚不能确认"));
        assert_eq!(workbench.metrics.stalled.load(Ordering::Relaxed),1);
    }
    #[test]
    fn disconnect_cancels_twenty_stalled_sessions_and_joins_every_owner() {
        for _ in 0..20 {
            let upstream=Server::http("127.0.0.1:0").unwrap();let workbench=fixture(&upstream);
            let url=workbench.url.join("api/state").unwrap();let token=workbench.token.clone();let metrics=workbench.metrics.clone();
            let client=std::thread::spawn(move||Client::new().get(url).header("X-DFS-Desktop",token).timeout(Duration::from_secs(5)).send());
            let held=upstream.recv_timeout(Duration::from_secs(2)).unwrap().unwrap();
            assert_eq!(metrics.active.load(Ordering::Relaxed),1);
            let start=Instant::now();drop(workbench);assert!(start.elapsed()<Duration::from_secs(2));
            let result=client.join().unwrap();assert!(result.is_err()||result.unwrap().status().is_server_error());assert_eq!(metrics.active.load(Ordering::Relaxed),0);drop(held);
        }
    }
    #[test]
    fn stalled_response_body_is_aborted_but_continuous_slow_progress_finishes() {
        use std::io::{Read,Write};
        for stall in [false,true] {
            let upstream=std::net::TcpListener::bind("127.0.0.1:0").unwrap();let address=upstream.local_addr().unwrap();
            let workbench=Workbench::start_config(Url::parse(&format!("http://{address}/")).unwrap(),Arc::new(|_|None),Some(Deadlines{headers:Duration::from_secs(1),stall:Duration::from_millis(150),total:Duration::from_secs(5)})).unwrap();
            let thread=std::thread::spawn(move||{
                let (mut socket,_)=upstream.accept().unwrap();let mut buf=[0;4096];let _=socket.read(&mut buf);
                socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n").unwrap();
                for n in 0..20 {if socket.write_all(b"x").is_err(){break;}std::thread::sleep(if stall&&n==0{Duration::from_millis(400)}else{Duration::from_millis(25)});}
            });
            let response=Client::new().get(workbench.url.join("api/state").unwrap()).header("X-DFS-Desktop",&workbench.token).send().unwrap();
            let body=response.bytes();if stall{assert!(body.is_err());}else{assert_eq!(body.unwrap().len(),20);}
            drop(workbench);thread.join().unwrap();
        }
    }
    #[test]
    fn disconnect_aborts_an_incomplete_upload_without_waiting_for_body_eof() {
        use std::io::Write;
        let upstream=Server::http("127.0.0.1:0").unwrap();let workbench=fixture(&upstream);
        let address=format!("127.0.0.1:{}",workbench.url.port().unwrap());
        let mut socket=std::net::TcpStream::connect(&address).unwrap();
        write!(socket,"PUT /api/projects/test/files/stage HTTP/1.1\r\nHost: {address}\r\nX-DFS-Desktop: {}\r\nContent-Length: 1048576\r\n\r\npartial",workbench.token).unwrap();
        let held=upstream.recv_timeout(Duration::from_secs(2)).unwrap().unwrap();
        let metrics=workbench.metrics.clone();let start=Instant::now();drop(workbench);
        assert!(start.elapsed()<Duration::from_secs(2));assert_eq!(metrics.active.load(Ordering::Relaxed),0);drop(held);
    }

    #[test]
    fn a_downstream_reader_that_stops_is_closed_without_retaining_a_forwarder(){
        use std::io::{Read,Write};
        let upstream=std::net::TcpListener::bind("127.0.0.1:0").unwrap();let address=upstream.local_addr().unwrap();
        let workbench=Workbench::start_config(Url::parse(&format!("http://{address}/")).unwrap(),Arc::new(|_|None),Some(Deadlines{headers:Duration::from_secs(2),stall:Duration::from_millis(150),total:Duration::from_secs(10)})).unwrap();
        let sender=std::thread::spawn(move||{let(mut socket,_)=upstream.accept().unwrap();socket.set_write_timeout(Some(Duration::from_secs(2))).unwrap();let mut request=[0u8;4096];let _=socket.read(&mut request);let _=socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 33554432\r\n\r\n");let chunk=[b'x';65536];for _ in 0..512{if socket.write_all(&chunk).is_err(){break;}}});
        let mut downstream=std::net::TcpStream::connect(format!("127.0.0.1:{}",workbench.url.port().unwrap())).unwrap();
        write!(downstream,"GET /api/large HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nX-DFS-Desktop: {}\r\n\r\n",workbench.url.port().unwrap(),workbench.token).unwrap();
        let deadline=Instant::now()+Duration::from_secs(5);while workbench.metrics.stalled.load(Ordering::Relaxed)==0&&Instant::now()<deadline{std::thread::sleep(Duration::from_millis(25));}
        assert!(workbench.metrics.stalled.load(Ordering::Relaxed)>0);
        while workbench.metrics.active.load(Ordering::Relaxed)>0&&Instant::now()<deadline{std::thread::sleep(Duration::from_millis(25));}
        assert_eq!(workbench.metrics.active.load(Ordering::Relaxed),0);drop(downstream);drop(workbench);sender.join().unwrap();
    }

}
