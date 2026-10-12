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

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    #[serde(default)]
    pub profiles: Vec<Profile>,
}

impl Config {
    pub fn load(root: &Path) -> Result<Self, String> {
        let mut path = root.join("connections.json");
        if !path.exists() {
            path = root.join("connections.json.previous");
            if !path.exists() {
                return Ok(Self::default());
            }
        }
        let bytes = fs::read(&path).map_err(|e| e.to_string())?;
        if bytes.len() > 128 * 1024 {
            return Err("连接配置过大".into());
        }
        let mut cfg: Self =
            serde_json::from_slice(&bytes).map_err(|e| format!("无法读取连接配置：{e}"))?;
        if cfg.profiles.len() > 30 {
            return Err("连接配置条目过多".into());
        }
        let mut ids = std::collections::HashSet::new();
        for p in &mut cfg.profiles {
            valid_id(&p.id)?;
            p.normalize()?;
            if !ids.insert(p.id.clone()) {
                return Err("连接配置包含重复标识".into());
            }
        }
        Ok(cfg)
    }
    pub fn save(&self, root: &Path) -> Result<(), String> {
        let temporary = root.join("connections.json.tmp");
        let current = root.join("connections.json");
        fs::write(
            &temporary,
            serde_json::to_vec_pretty(self).map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;
        #[cfg(windows)]
        {
            // Replacement is recoverable: keep the previous valid config until the new one is installed.
            let old = root.join("connections.json.previous");
            if current.exists() {
                if old.exists() {
                    fs::remove_file(&old).map_err(|e| e.to_string())?;
                }
                fs::rename(&current, &old).map_err(|e| e.to_string())?;
            }
            if let Err(e) = fs::rename(&temporary, &current) {
                let _ = fs::rename(&old, &current);
                return Err(e.to_string());
            }
        }
        #[cfg(not(windows))]
        fs::rename(&temporary, &current).map_err(|e| e.to_string())?;
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
