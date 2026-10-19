//! Real production proxy with a stdin-owned lifetime for the HTTP contract regression.
#![allow(dead_code)]
#[path = "../../../admin-desktop/src-tauri/src/workbench.rs"]
mod workbench;
fn main() {
    let base = url::Url::parse(&std::env::args().nth(1).expect("loopback upstream required")).unwrap();
    assert_eq!(base.host_str(), Some("127.0.0.1"));
    let proxy = workbench::Workbench::start(base, std::sync::Arc::new(|_| None)).unwrap();
    println!("{}", serde_json::json!({"url":proxy.url.as_str(),"token":proxy.token}));
    let mut command=String::new();
    std::io::stdin().read_line(&mut command).unwrap();
    drop(proxy);
}
