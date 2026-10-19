#![allow(dead_code)]
#[path = "../../../../admin-desktop/src-tauri/src/workbench.rs"]
mod workbench;
use std::{io::{Read,Write},net::{TcpListener,TcpStream},sync::Arc,time::Duration};

fn post(address:std::net::SocketAddr, token:Option<&str>)->String {
    let mut socket=TcpStream::connect(address).unwrap();
    socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let desktop=token.map(|t|format!("X-DFS-Desktop: {t}\r\n")).unwrap_or_default();
    write!(socket,"POST /api/projects/demo/scan HTTP/1.1\r\nHost: {address}\r\nOrigin: http://{address}\r\n{desktop}Prefer: respond-async\r\nX-DFS-Request-Id: 00000000-0000-4000-8000-000000000001\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}").unwrap();
    let mut output=String::new();socket.read_to_string(&mut output).unwrap();output
}
fn main(){
    let listener=TcpListener::bind("127.0.0.1:0").unwrap();let address=listener.local_addr().unwrap();
    let upstream=std::thread::spawn(move||{
        for incoming in listener.incoming().take(2){
            let mut socket=incoming.unwrap();socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            let mut raw=Vec::new();let mut byte=[0];
            while !raw.ends_with(b"\r\n\r\n"){socket.read_exact(&mut byte).unwrap();raw.push(byte[0]);assert!(raw.len()<32768);}
            let mut body_bytes=[0;2];socket.read_exact(&mut body_bytes).unwrap();
            let text=String::from_utf8(raw).unwrap().to_lowercase();
            let prefer=text.contains("prefer: respond-async\r\n");
            let request_id=text.contains("x-dfs-request-id: 00000000-0000-4000-8000-000000000001\r\n");
            let body=serde_json::json!({"prefer":prefer,"requestId":request_id}).to_string();
            let status=if prefer&&request_id{"202 Accepted"}else{"200 OK"};
            write!(socket,"HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
        }
    });
    let direct=post(address,None);
    let proxy=workbench::Workbench::start(url::Url::parse(&format!("http://{address}/")).unwrap(),Arc::new(|_|None)).unwrap();
    let via=post(proxy.url.socket_addrs(||None).unwrap()[0],Some(&proxy.token));
    println!("DIRECT {} body={}",direct.lines().next().unwrap(),direct.split("\r\n\r\n").nth(1).unwrap());
    println!("PROXY {} body={}",via.lines().next().unwrap(),via.split("\r\n\r\n").nth(1).unwrap());
    assert!(direct.starts_with("HTTP/1.1 202"));assert!(via.starts_with("HTTP/1.1 200"));
    drop(proxy);upstream.join().unwrap();
}
