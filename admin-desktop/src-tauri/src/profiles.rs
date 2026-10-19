use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::{Path, PathBuf},
};
use url::Url;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    #[serde(default)]
    pub id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub relocation_required: bool,
    #[serde(default, skip_serializing)]
    pub confirm_relocation: bool,
    #[serde(flatten)]
    pub connection: Connection,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum Connection {
    Local {
        home: String,
    },
    Remote {
        url: String,
    },
    Ssh {
        target: String,
        #[serde(rename = "remotePort")]
        remote_port: u16,
    },
}

pub fn valid_id(id: &str) -> Result<(), String> {
    if !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
    {
        Ok(())
    } else {
        Err("连接记录标识无效".into())
    }
}

pub fn remote_url(value: &str) -> Result<Url, String> {
    let u = Url::parse(value.trim())
        .map_err(|_| "请填写完整的管理地址，例如 https://admin.example.com/")?;
    if !u.username().is_empty()
        || u.password().is_some()
        || u.query().is_some()
        || u.fragment().is_some()
    {
        return Err("管理地址只填写地址，不包含密码、查询参数或锚点".into());
    }
    let local = matches!(u.host(), Some(url::Host::Domain("localhost")))
        || matches!(u.host(),Some(url::Host::Ipv4(ip)) if ip.is_loopback())
        || matches!(u.host(),Some(url::Host::Ipv6(ip)) if ip.is_loopback());
    if u.scheme() != "https" && !(u.scheme() == "http" && local) {
        return Err("远程地址请使用 HTTPS；也可以使用 SSH 连接或本机转发地址".into());
    }
    if u.host_str().is_none() {
        return Err("管理地址缺少服务器名称".into());
    }
    Ok(u)
}

pub fn ssh_target(value: &str) -> Result<String, String> {
    let v = value.trim();
    if v.is_empty()
        || v.len() > 128
        || !v.as_bytes()[0].is_ascii_alphanumeric()
        || !v
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-' | b'.' | b'@'))
    {
        return Err("请填写已有 SSH 配置名称或 user@host".into());
    }
    Ok(v.to_string())
}

impl Profile {
    pub fn normalize(&mut self) -> Result<(), String> {
        self.name = self.name.trim().to_string();
        if self.name.is_empty()
            || self.name.chars().count() > 60
            || self.name.chars().any(char::is_control)
        {
            return Err("连接名称应为 1–60 个字符".into());
        }
        match &mut self.connection {
            Connection::Local { home } => {
                *home = home.trim().into();
                if !PathBuf::from(&home).is_absolute() {
                    return Err("请选择完整的管理数据目录".into());
                }
            }
            Connection::Remote { url } => *url = remote_url(url)?.to_string(),
            Connection::Ssh {
                target,
                remote_port,
            } => {
                *target = ssh_target(target)?;
                if *remote_port == 0 {
                    return Err("服务器管理端口应为 1–65535".into());
                }
            }
        }
        Ok(())
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    #[serde(default = "legacy_version")]
    pub version: u32,
    #[serde(default)]
    pub profiles: Vec<Profile>,
}

fn legacy_version() -> u32 { 1 }
impl Default for Config {
    fn default() -> Self { Self { version: 2, profiles: Vec::new() } }
}

/// Config v2 stores homes inside the desktop data root as relative paths. UI values are
/// always resolved absolute paths; external directories retain their explicit identity.
pub fn resolve_home(root: &Path, home: &str) -> Result<PathBuf, String> {
    let path = Path::new(home);
    if path.is_absolute() { return Ok(dunce::canonicalize(path).unwrap_or_else(|_|path.to_path_buf())); }
    if home.is_empty() || path.components().any(|c| !matches!(c, std::path::Component::Normal(_))) {
        return Err("便携管理目录必须是数据根目录内的相对路径".into());
    }
    let candidate=root.join(path);Ok(dunce::canonicalize(&candidate).unwrap_or(candidate))
}

fn same_home(a:&Path,b:&Path)->bool {
    let a=dunce::canonicalize(a).unwrap_or_else(|_|a.into());let b=dunce::canonicalize(b).unwrap_or_else(|_|b.into());
    #[cfg(windows)] {a.to_string_lossy().eq_ignore_ascii_case(&b.to_string_lossy())}
    #[cfg(not(windows))] {a==b}
}
fn same_file(a:&Path,b:&Path)->bool {
    use std::io::Read;
    let (Ok(mut a),Ok(mut b))=(fs::File::open(a),fs::File::open(b))else{return false;};
    let (mut x,mut y)=([0u8;65536],[0u8;65536]);
    loop{let(Ok(n),Ok(m))=(a.read(&mut x),b.read(&mut y))else{return false;};if n!=m||x[..n]!=y[..m]{return false;}if n==0{return true;}}
}
fn shared_identity(a:&Path,b:&Path)->bool {
    let keys=a.join("data/keys");
    if let Ok(entries)=fs::read_dir(keys){for entry in entries.flatten(){let other=b.join("data/keys").join(entry.file_name());if entry.path().is_file()&&other.is_file()&&same_file(&entry.path(),&other){return true;}}}
    let left=a.join("data/management.db");let right=b.join("data/management.db");left.is_file()&&right.is_file()&&same_file(&left,&right)
}

impl Config {
    fn read(path: &Path, root: &Path) -> Result<Self, String> {
        let bytes = fs::read(path).map_err(|e| format!("无法读取 {}：{e}", path.display()))?;
        if bytes.len() > 128 * 1024 { return Err("连接配置过大".into()); }
        let mut cfg: Self = serde_json::from_slice(&bytes)
            .map_err(|e| format!("无法解析 {}：{e}", path.display()))?;
        if !matches!(cfg.version, 1 | 2) { return Err("不支持的连接配置版本，请升级桌面端".into()); }
        if cfg.profiles.len() > 30 { return Err("连接配置条目过多".into()); }
        let mut ids = std::collections::HashSet::new();
        for p in &mut cfg.profiles {
            valid_id(&p.id)?;
            if let Connection::Local { home } = &mut p.connection {
                if cfg.version == 1 && !Path::new(home).is_absolute() {
                    return Err("旧连接配置必须使用绝对管理目录".into());
                }
                let resolved = resolve_home(root, home)?;
                // An unversioned absolute default cannot identify which copy was intended.
                if cfg.version == 1 && !same_home(&resolved,&root.join("management")) &&
                    (!resolved.exists() || shared_identity(&resolved,&root.join("management"))) {
                    p.relocation_required = true;
                }
                *home = resolved.to_string_lossy().into();
            }
            p.normalize()?;
            if !ids.insert(p.id.clone()) { return Err("连接配置包含重复标识".into()); }
        }
        cfg.version = 2;
        Ok(cfg)
    }
    pub fn load_with_notice(root: &Path) -> Result<(Self, Option<String>), String> {
        let current = root.join("connections.json");
        let previous = root.join("connections.json.previous");
        match Self::read(&current, root) {
            Ok(config) => Ok((config, None)),
            Err(error) => {
                if !current.try_exists().map_err(|e| e.to_string())? && !previous.try_exists().map_err(|e| e.to_string())? {
                    return Ok((Self::default(), None));
                }
                Self::read(&previous, root).map(|cfg| (cfg, Some(format!("{error}；已从 {} 恢复连接，原件和备份均保留。", previous.display()))))
                    .map_err(|backup| format!("{error}；备份也不可用：{backup}。已保留原件，请恢复或重新定位连接配置。"))
            }
        }
    }
    pub fn load(root: &Path) -> Result<Self, String> { Self::load_with_notice(root).map(|r| r.0) }
    pub fn save(&self, root: &Path) -> Result<(), String> {
        let mut stored = self.clone();
        stored.version = 2;
        for p in &mut stored.profiles {
            if let Connection::Local { home } = &mut p.connection {
                let resolved = resolve_home(root, home)?;
                let canonical_root=dunce::canonicalize(root).unwrap_or_else(|_|root.to_path_buf());
                if let Ok(relative) = resolved.strip_prefix(&canonical_root) {
                    if !relative.as_os_str().is_empty() {
                        *home = relative.to_string_lossy().replace('\\', "/");
                    }
                }
            }
        }
        let temporary = root.join("connections.json.tmp");
        let current = root.join("connections.json");
        let old = root.join("connections.json.previous");
        let bytes = serde_json::to_vec_pretty(&stored).map_err(|e| e.to_string())?;
        let mut file = fs::File::create(&temporary).map_err(|e| e.to_string())?;
        use std::io::Write;
        file.write_all(&bytes).and_then(|_| file.sync_all()).map_err(|e| e.to_string())?;
        drop(file);
        // A corrupt current must never replace the last valid recovery copy.
        if current.exists() {
            if Self::read(&current, root).is_ok() {
                if old.exists() { fs::remove_file(&old).map_err(|e| e.to_string())?; }
                fs::rename(&current, &old).map_err(|e| e.to_string())?;
            } else {
                let evidence = (0..).map(|n| root.join(format!("connections.json.corrupt-{n}")))
                    .find(|p| !p.exists()).unwrap();
                fs::rename(&current, evidence).map_err(|e| e.to_string())?;
            }
        }
        if let Err(error) = fs::rename(&temporary, &current) {
            if !current.exists() && old.exists() { let _ = fs::copy(&old, &current); }
            return Err(error.to_string());
        }
        Self::read(&current, root)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn urls_do_not_store_credentials_or_expose_plain_remote_http() {
        for value in [
            "https://u:p@example.com/",
            "http://example.com/",
            "file:///C:/",
            "https://example.com/?token=a",
            "https://example.com/#a",
        ] {
            assert!(remote_url(value).is_err(), "{value}");
        }
        for value in [
            "https://example.com/",
            "http://127.0.0.1:18080/",
            "http://[::1]:8080/",
            "http://localhost:18080/",
        ] {
            assert!(remote_url(value).is_ok(), "{value}");
        }
    }
    #[test]
    fn ssh_arguments_cannot_be_options_or_shell_commands() {
        for value in [
            "-oProxyCommand=bad",
            "host;shutdown",
            "host name",
            "../config",
            "x\n-oForward",
        ] {
            assert!(ssh_target(value).is_err());
        }
        assert_eq!(ssh_target(" my-server ").unwrap(), "my-server");
        assert!(ssh_target("user@example.com").is_ok());
    }
    #[test]
    fn profile_ids_cannot_escape_webview_data_directory() {
        for id in ["..", "../x", "x/y", "x\\y", ""] {
            assert!(valid_id(id).is_err());
        }
    }
    #[test]
    fn profiles_round_trip_but_do_not_accept_password_fields() {
        let p: Profile = serde_json::from_str(
            r#"{"id":"test","name":"服务器","kind":"ssh","target":"my-server","remotePort":18080}"#,
        )
        .unwrap();
        let serialized = serde_json::to_string(&p).unwrap();
        assert!(serde_json::from_str::<Profile>(&serialized).is_ok());
        assert!(serde_json::from_str::<Profile>(r#"{"id":"test","name":"test","kind":"remote","url":"https://example.com/","password":"secret"}"#).is_err());
    }
}
