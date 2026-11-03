use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, io::Write, path::Path};

const MAX_BYTES: u64 = 1024 * 1024;
const MAX_SCOPES: usize = 100;
const MAX_ENTRIES: usize = 10;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Method { Export, Webdav, S3 }

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Values {
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub output_directory: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub base_url: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub username: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub endpoint: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub region: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub bucket: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub prefix: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub access_key_id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub addressing_style: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Entry { pub id: String, pub method: Method, pub saved_at: u64, pub values: Values }

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct File { version: u32, scopes: BTreeMap<String, Vec<Entry>> }

pub fn authorize(label: &str, url: &url::Url, origin: &url::Origin, supplied: &str, expected: &str) -> Result<(), String> {
    if label != "workspace" || url.origin() != *origin || supplied.is_empty() || supplied != expected {
        return Err("管理连接已切换，请重新打开工作区后访问填写历史".into());
    }
    Ok(())
}

fn validate(entries: &[Entry]) -> Result<(), String> {
    if entries.len() > MAX_ENTRIES { return Err("每个项目最多保存 10 条填写历史".into()); }
    let mut ids = std::collections::HashSet::new();
    for entry in entries {
        if entry.id.is_empty() || entry.id.len() > 80
            || !entry.id.bytes().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'-' | b'_'))
            || !ids.insert(&entry.id) || entry.saved_at == 0 || entry.saved_at > 8_640_000_000_000_000 {
            return Err("填写历史标识或时间无效".into());
        }
        let v = &entry.values;
        for value in [&v.output_directory, &v.base_url, &v.username, &v.endpoint, &v.region,
                      &v.bucket, &v.prefix, &v.access_key_id, &v.addressing_style] {
            if value.len() > 8192 || value.chars().count() > 2048 || value.chars().any(char::is_control) {
                return Err("填写历史字段过长或包含控制字符".into());
            }
        }
        for address in [&v.base_url, &v.endpoint] {
            if !address.is_empty() {
                let url = url::Url::parse(address).map_err(|_| "填写历史中的地址无效")?;
                if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none()
                    || !url.username().is_empty() || url.password().is_some() || url.query().is_some() || url.fragment().is_some() {
                    return Err("填写历史中的地址不能包含密码、查询参数或锚点".into());
                }
            }
        }
        if !v.addressing_style.is_empty() && !matches!(v.addressing_style.as_str(), "PATH" | "VIRTUAL_HOST") {
            return Err("填写历史中的寻址方式无效".into());
        }
    }
    Ok(())
}
fn scope(connection: &str, project: &str) -> Result<String, String> {
    if connection.is_empty() || connection.len() > 4096 || project.is_empty() || project.len() > 8192 {
        return Err("填写历史所属连接或项目无效".into());
    }
    serde_json::to_string(&(connection, project)).map_err(|e| e.to_string())
}
fn read_file(root: &Path) -> Result<File, String> {
    let path = root.join("hosting-history.json");
    match fs::symlink_metadata(&path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(File { version: 1, scopes: BTreeMap::new() }),
        Err(e) => return Err(format!("无法读取填写历史：{e}")),
        Ok(meta) if !meta.file_type().is_file() || meta.len() > MAX_BYTES => return Err("填写历史文件不安全或过大，原文件已保留".into()),
        Ok(_) => {}
    }
    let bytes = fs::read(&path).map_err(|e| format!("无法读取填写历史：{e}"))?;
    if bytes.len() as u64 > MAX_BYTES { return Err("填写历史文件过大，原文件已保留".into()); }
    let file: File = serde_json::from_slice(&bytes).map_err(|_| "填写历史格式异常，原文件已保留")?;
    if file.version != 1 || file.scopes.len() > MAX_SCOPES { return Err("填写历史版本或数量不受支持，原文件已保留".into()); }
    for (key, entries) in &file.scopes {
        if key.len() > 16 * 1024 { return Err("填写历史所属项目无效，原文件已保留".into()); }
        validate(entries)?;
    }
    Ok(file)
}
pub fn load(root: &Path, connection: &str, project: &str) -> Result<Vec<Entry>, String> {
    let key = scope(connection, project)?;
    Ok(read_file(root)?.scopes.remove(&key).unwrap_or_default())
}
pub fn save(root: &Path, connection: &str, project: &str, entries: Vec<Entry>) -> Result<(), String> {
    validate(&entries)?;
    let key = scope(connection, project)?;
    let mut file = read_file(root)?;
    if entries.is_empty() { file.scopes.remove(&key); } else { file.scopes.insert(key, entries); }
    if file.scopes.len() > MAX_SCOPES { return Err("填写历史中的项目过多，请先删除不再使用的记录".into()); }
    let bytes = serde_json::to_vec(&file).map_err(|e| e.to_string())?;
    if bytes.len() as u64 > MAX_BYTES { return Err("填写历史已满，请删除部分记录后再保存".into()); }
    fs::create_dir_all(root).map_err(|e| e.to_string())?;
    let temporary = root.join(format!("hosting-history-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut output = fs::OpenOptions::new().write(true).create_new(true).open(&temporary).map_err(|e| e.to_string())?;
        output.write_all(&bytes).and_then(|_| output.sync_all()).map_err(|e| e.to_string())?;
        drop(output);
        fs::rename(&temporary, root.join("hosting-history.json")).map_err(|e| e.to_string())
    })();
    if result.is_err() { let _ = fs::remove_file(&temporary); }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fixture(std::path::PathBuf);
    impl Fixture { fn new() -> Self { Self(std::env::temp_dir().join(format!("dfs-hosting-history-{}", uuid::Uuid::new_v4()))) } }
    impl Drop for Fixture { fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); } }
    fn entry() -> Entry {
        Entry { id: "record-1".into(), method: Method::S3, saved_at: 1,
            values: Values { endpoint: "https://oss.example.test".into(), bucket: "pack".into(),
                access_key_id: "public-test-id".into(), addressing_style: "VIRTUAL_HOST".into(), ..Values::default() } }
    }
    #[test]
    fn history_survives_reopening_and_keeps_connections_and_projects_separate() {
        let fixture = Fixture::new();
        save(&fixture.0, "connection-a", "project-a", vec![entry()]).unwrap();
        assert_eq!(load(&fixture.0, "connection-a", "project-a").unwrap()[0].values.access_key_id, "public-test-id");
        assert!(load(&fixture.0, "connection-b", "project-a").unwrap().is_empty());
        assert!(load(&fixture.0, "connection-a", "project-b").unwrap().is_empty());
        save(&fixture.0, "connection-b", "project-a", vec![entry()]).unwrap();
        save(&fixture.0, "connection-a", "project-a", vec![]).unwrap();
        assert!(load(&fixture.0, "connection-a", "project-a").unwrap().is_empty());
        assert_eq!(load(&fixture.0, "connection-b", "project-a").unwrap().len(), 1);
    }
    #[test]
    fn secret_fields_and_credentials_in_urls_are_rejected() {
        for field in ["password", "secretAccessKey", "sessionToken"] {
            let mut raw = serde_json::to_value(entry()).unwrap();
            raw["values"][field] = serde_json::json!("must-not-be-stored");
            assert!(serde_json::from_value::<Entry>(raw).is_err(), "{field}");
        }
        let fixture = Fixture::new();
        for url in ["https://user:secret@example.test/", "https://example.test/?token=secret", "file:///C:/private"] {
            let mut bad = entry(); bad.values.endpoint = url.into();
            assert!(save(&fixture.0, "connection", "project", vec![bad]).is_err());
            assert!(!fixture.0.join("hosting-history.json").exists());
        }
    }
    #[test]
    fn invalid_or_oversized_history_preserves_the_previous_file() {
        let fixture = Fixture::new();
        save(&fixture.0, "connection", "project", vec![entry()]).unwrap();
        let path = fixture.0.join("hosting-history.json");
        let before = fs::read(&path).unwrap();
        assert!(save(&fixture.0, "connection", "project", vec![entry(); 11]).is_err());
        assert_eq!(fs::read(&path).unwrap(), before);
        fs::write(&path, b"invalid json").unwrap();
        assert!(save(&fixture.0, "connection", "project", vec![entry()]).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"invalid json");
    }
    #[test]
    fn stale_connections_and_other_pages_cannot_access_native_history() {
        let url = url::Url::parse("http://127.0.0.1:19000/workspace.html").unwrap();
        assert!(authorize("workspace", &url, &url.origin(), "current-token", "current-token").is_ok());
        assert!(authorize("main", &url, &url.origin(), "current-token", "current-token").is_err());
        assert!(authorize("workspace", &url, &url.origin(), "old-token", "current-token").is_err());
        assert!(authorize("workspace", &url, &url.origin(), "", "").is_err());
        let other = url::Url::parse("http://127.0.0.1:19001/workspace.html").unwrap();
        assert!(authorize("workspace", &other, &url.origin(), "current-token", "current-token").is_err());
    }
    #[test]
    fn a_new_process_can_read_the_persisted_history() {
        let fixture = Fixture::new();
        save(&fixture.0, "persistent-connection", "project", vec![entry()]).unwrap();
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "hosting_history::tests::reopen_child", "--nocapture"])
            .env("DFS_TEST_HOSTING_REOPEN", &fixture.0).output().unwrap();
        assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
        assert!(String::from_utf8_lossy(&output.stdout).contains("1 passed"), "reopen helper did not run");
    }
    #[test]
    fn reopen_child() {
        let Some(root) = std::env::var_os("DFS_TEST_HOSTING_REOPEN") else { return; };
        let entries = load(Path::new(&root), "persistent-connection", "project").unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].values.access_key_id, "public-test-id");
    }
}
